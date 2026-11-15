package com.hermes.chatandroid.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 글라스에서 찍은 사진(업로드용 JPEG + 화면 미리보기). */
class GlassesPhoto(val jpeg: ByteArray, val preview: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

data class GlassesState(
    val initialized: Boolean = false,
    val registration: RegistrationState? = null,
    val deviceCount: Int = 0,
    val cameraPermission: Boolean? = null,
    val session: DeviceSessionState? = null,
    val stream: StreamState? = null,
    val error: String? = null,
) {
    val registered get() = registration == RegistrationState.REGISTERED
    /** capturePhoto 가능 — 스트림이 살아 있을 때만 된다(DAT 제약). */
    val cameraReady get() = stream == StreamState.STREAMING || stream == StreamState.STARTED
}

/**
 * Meta Wearables DAT 래퍼(T-188). 카메라 스트림은 사진 촬영을 위해서만 열어 둔다 —
 * 프레임은 쓰지 않으므로 최저 프레임률로 BT 대역폭·배터리를 아낀다.
 * 사진은 [capture] 의 in-stream `capturePhoto()` (스트림 프레임보다 해상도가 높아 글 판독에 유리).
 */
class GlassesCamera(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var jobs = mutableListOf<Job>()
    private val captureLock = Mutex()

    /** BLUETOOTH_CONNECT 허용 후 1회. 실패는 state.error 로. */
    fun initialize() {
        if (_state.value.initialized) return
        Wearables.initialize(appContext)
            .onSuccess {
                _state.update { it.copy(initialized = true, error = null) }
                scope.launch { Wearables.registrationState.collect { r -> _state.update { it.copy(registration = r) } } }
                scope.launch { Wearables.devices.collect { d -> _state.update { it.copy(deviceCount = d.size) } } }
                scope.launch { refreshPermission() }
            }
            .onFailure { e, _ -> fail("DAT 초기화 실패: ${e.description}") }
    }

    /** Meta AI 앱으로 넘어가 이 앱을 등록한다(개발자 모드 필요). */
    fun register(activity: Activity) = Wearables.startRegistration(activity)

    suspend fun refreshPermission() {
        if (!_state.value.initialized) return
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { s -> _state.update { it.copy(cameraPermission = s == PermissionStatus.Granted) } }
            .onFailure { e, _ -> fail("카메라 권한 확인 실패: ${e.description}") }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(cameraPermission = granted) }
        if (granted) connect()
    }

    /** 세션 → 카메라 스트림 시작. 이미 연결 중이면 무시. */
    fun connect() {
        val st = _state.value
        if (!st.registered || st.cameraPermission != true || session != null) return
        Wearables.createSession(AutoDeviceSelector())
            .onSuccess { s ->
                session = s
                jobs += scope.launch { s.errors.collect { e -> fail("글라스 세션: ${e.description}") } }
                jobs += scope.launch {
                    s.state.collect { ss ->
                        _state.update { it.copy(session = ss) }
                        when (ss) {
                            DeviceSessionState.STARTED -> if (camera == null) startCamera(s)
                            DeviceSessionState.STOPPED -> cleanup()
                            else -> Unit
                        }
                    }
                }
                s.start()
            }
            .onFailure { e, _ -> fail("글라스 연결 실패: ${e.description}") }
    }

    private fun startCamera(s: DeviceSession) {
        s.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 2))
            .onSuccess { cam ->
                camera = cam
                val stream = cam.stream
                jobs += scope.launch { stream.state.collect { ss -> _state.update { it.copy(stream = ss) } } }
                jobs += scope.launch { stream.errorStream.collect { e -> Log.w(TAG, "stream: ${e.description}") } }
                // 프레임은 쓰지 않지만 흐름을 비워 SDK 버퍼가 쌓이지 않게 한다.
                jobs += scope.launch(Dispatchers.Default) { stream.videoStream.collect { } }
                stream.start().onFailure { e, _ -> fail("카메라 시작 실패: ${e.description}") }
            }
            .onFailure { e, _ -> fail("카메라 추가 실패: ${e.description}") }
    }

    /**
     * 시야 사진 1장. 업로드용으로 긴 변 [maxSide]px·JPEG 90 (과압축하면 글을 못 읽는다).
     * 스트림이 없으면 null. 동시 호출은 직렬화(DAT 는 한 번에 1장만 허용).
     */
    suspend fun capture(maxSide: Int = 2048): GlassesPhoto? = captureLock.withLock {
        val stream = camera?.stream ?: return null
        if (!_state.value.cameraReady) return null
        val r = stream.capturePhoto()
        val data = r.getOrNull()
        if (data == null) {
            fail("사진 실패: ${r.errorOrNull()?.description ?: "알 수 없음"}")
            return null
        }
        withContext(Dispatchers.Default) { encode(data, maxSide) }
    }

    fun disconnect() {
        session?.stop()
        cleanup()
    }

    private fun cleanup() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { camera?.close() }
        camera = null
        session = null
        _state.update { it.copy(session = null, stream = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(error = msg) }
    }

    companion object {
        private const val TAG = "GlassesCamera"

        /** PhotoData → (JPEG, 미리보기). HEIC 는 ImageDecoder 가 EXIF 회전을 적용한다. */
        fun encode(data: PhotoData, maxSide: Int): GlassesPhoto? {
            val src: Bitmap = when (data) {
                is PhotoData.Bitmap -> data.bitmap
                is PhotoData.HEIC -> {
                    val buf = data.data.duplicate().apply { rewind() }
                    runCatching {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(buf)) { d, _, _ ->
                            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }.getOrNull() ?: return null
                }
            }
            val w = src.width
            val h = src.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            val out = if (scale < 1f) Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true) else src
            val jpeg = ByteArrayOutputStream().use { os -> out.compress(Bitmap.CompressFormat.JPEG, 90, os); os.toByteArray() }
            val previewScale = minOf(1f, 480f / maxOf(out.width, out.height))
            val preview = Bitmap.createScaledBitmap(out, (out.width * previewScale).toInt(), (out.height * previewScale).toInt(), true)
            return GlassesPhoto(jpeg, preview, w, h)
        }
    }
}
