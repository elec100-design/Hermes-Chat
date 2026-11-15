package com.hermes.chatandroid.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.meta.wearable.dat.camera.photo.types.PhotoCaptureData
import com.meta.wearable.dat.camera.photo.types.PhotoError
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import com.meta.wearable.dat.camera.photo.types.PhotoState
import com.meta.wearable.dat.camera.removeCamera
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
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

enum class GlassesUpdate { GLASSES_APP, FIRMWARE }

/** 글라스에서 찍는 크기 — BT 전송(약 40KB/s 실측)이 지연의 대부분이라 글라스 쪽에서 줄인다. */
enum class PhotoSize(val label: String, val resolution: PhotoResolution, val quality: PhotoQuality) {
    FAST("빠름", PhotoResolution.MEDIUM, PhotoQuality.MEDIUM),
    BALANCED("보통", PhotoResolution.LARGE, PhotoQuality.MEDIUM),
    BEST("최고화질", PhotoResolution.FULL, PhotoQuality.HIGH),
}

/** 글라스에서 찍은 사진(업로드용 JPEG). [width]×[height] = 올리는 크기, source = 글라스가 보낸 크기. */
class GlassesPhoto(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    /** 글라스→폰 수신 시간·크기(화면 표시·튜닝용). */
    var transferMs: Long = 0
    var receivedKB: Int = 0
}

data class GlassesState(
    val initialized: Boolean = false,
    val registration: RegistrationState? = null,
    val deviceCount: Int = 0,
    val cameraPermission: Boolean? = null,
    val session: DeviceSessionState? = null,
    /** 사진 받는 중(0..1). null = 촬영 중 아님. */
    val transfer: Float? = null,
    val error: String? = null,
    /** 글라스 쪽 업데이트가 필요해 세션이 안 열림 — Meta AI 앱의 업데이트 화면으로 보낸다. */
    val update: GlassesUpdate? = null,
) {
    val registered get() = registration == RegistrationState.REGISTERED
    /** 세션이 열려 있으면 요청 시 촬영 가능(카메라는 촬영 순간에만 붙인다). */
    val cameraReady get() = session == DeviceSessionState.STARTED
}

/**
 * Meta Wearables DAT 래퍼(T-188).
 * 세션만 열어 두고, 촬영 요청 때만 카메라를 붙여 **단독 고화질 사진**(`camera.photo`, FULL·HIGH)을 받는다.
 * 11-15 실측: in-stream `capturePhoto()` 는 1440×1080 이라 글 판독이 안 됨 → 단독 촬영이 기본,
 * 실패하면(실험 기능 미승인 등) in-stream 사진으로 대체.
 * 회전: DAT 사진은 EXIF 회전을 디코더가 적용하지 않아 옆으로 눕는다 → 직접 회전.
 */
class GlassesCamera(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    private var session: DeviceSession? = null
    private var jobs = mutableListOf<Job>()
    private val captureLock = Mutex()

    private var autoConnect = false

    /**
     * Live 시작·촬영 직전에 부른다: 초기화하고, 등록·카메라 권한이 이미 갖춰져 있으면 세션까지 연다
     * (설정 화면을 거치지 않아도, 글라스가 세션을 끊은 뒤에도 다시 붙게). BLUETOOTH_CONNECT 허용 후에만 호출.
     */
    fun ensureConnected() {
        autoConnect = true
        if (!_state.value.initialized) initialize() else scope.launch { refreshPermission() }
    }

    /** BLUETOOTH_CONNECT 허용 후 1회. 실패는 state.error 로. */
    fun initialize() {
        if (_state.value.initialized) return
        Wearables.initialize(appContext)
            .onSuccess {
                _state.update { it.copy(initialized = true, error = null) }
                scope.launch {
                    Wearables.registrationState.collect { r ->
                        _state.update { it.copy(registration = r) }
                        if (autoConnect && r == RegistrationState.REGISTERED) refreshPermission()
                    }
                }
                scope.launch {
                    Wearables.devices.collect { d ->
                        _state.update { it.copy(deviceCount = d.size) }
                        d.forEach(::watchCompatibility)
                    }
                }
                scope.launch { refreshPermission() }
            }
            .onFailure { e, _ -> fail("DAT 초기화 실패: ${e.description}") }
    }

    private val watched = mutableSetOf<com.meta.wearable.dat.core.types.DeviceIdentifier>()

    private fun watchCompatibility(id: com.meta.wearable.dat.core.types.DeviceIdentifier) {
        if (!watched.add(id)) return
        val flow = Wearables.devicesMetadata[id] ?: return
        scope.launch {
            flow.collect { dev ->
                if (dev.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
                    _state.update { it.copy(update = GlassesUpdate.FIRMWARE) }
                }
            }
        }
    }

    /** Meta AI 앱의 글라스 업데이트 화면을 연다. */
    fun openUpdate(activity: Activity) {
        val r = when (_state.value.update) {
            GlassesUpdate.FIRMWARE -> Wearables.openFirmwareUpdate(activity)
            else -> Wearables.openDATGlassesAppUpdate(activity)
        }
        r.onFailure { e, _ -> fail("업데이트 화면 열기 실패: ${e.description}") }
    }

    /** Meta AI 앱으로 넘어가 이 앱을 등록한다(개발자 모드 필요). */
    fun register(activity: Activity) = Wearables.startRegistration(activity)

    suspend fun refreshPermission() {
        if (!_state.value.initialized) return
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { s ->
                _state.update { it.copy(cameraPermission = s == PermissionStatus.Granted) }
                if (autoConnect) connect()
            }
            .onFailure { e, _ -> fail("카메라 권한 확인 실패: ${e.description}") }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(cameraPermission = granted) }
        if (granted) connect()
    }

    /** 글라스 세션 시작. 이미 연결 중이면 무시. */
    fun connect() {
        val st = _state.value
        if (!st.registered || st.cameraPermission != true || session != null) return
        Wearables.createSession(AutoDeviceSelector())
            .onSuccess { s ->
                session = s
                jobs += scope.launch {
                    s.errors.collect { e ->
                        if (e == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                            _state.update { it.copy(update = GlassesUpdate.GLASSES_APP, error = null) }
                        } else {
                            fail("글라스 세션: ${e.description}")
                        }
                    }
                }
                jobs += scope.launch {
                    s.state.collect { ss ->
                        _state.update { it.copy(session = ss) }
                        when (ss) {
                            DeviceSessionState.STARTED -> _state.update { it.copy(update = null) }
                            DeviceSessionState.STOPPED -> cleanup()
                            else -> Unit
                        }
                    }
                }
                s.start()
            }
            .onFailure { e, _ -> fail("글라스 연결 실패: ${e.description}") }
    }

    /**
     * 시야 사진 1장(업로드용 긴 변 [maxSide]px·[VISION_MAX_BYTES] 이하 JPEG). 세션이 없으면 null. 동시 호출은 직렬화.
     */
    suspend fun capture(size: PhotoSize = PhotoSize.BALANCED, maxSide: Int = VISION_MAX_SIDE): GlassesPhoto? = captureLock.withLock {
        val s = session ?: return null
        if (!_state.value.cameraReady) return null
        _state.update { it.copy(transfer = 0f) }
        try {
            val t0 = System.currentTimeMillis()
            val full = captureFull(s, size)
            val bytes = full ?: captureInStream(s)
            if (bytes == null) { fail("사진을 받지 못했어요"); return null }
            val elapsed = System.currentTimeMillis() - t0
            withContext(Dispatchers.Default) { encode(bytes, maxSide) }?.also {
                it.transferMs = elapsed
                it.receivedKB = bytes.size / 1024
            }
        } finally {
            _state.update { it.copy(transfer = null) }
        }
    }

    /** 단독 고화질 촬영. Photo 는 1회용이라 매번 카메라를 붙였다 뗀다. 실패 시 null(대체 경로로). */
    private suspend fun captureFull(s: DeviceSession, size: PhotoSize): ByteArray? {
        val cam = s.addCamera(StreamConfiguration()).getOrNull() ?: run {
            Log.w(TAG, "full: addCamera 실패"); return null
        }
        return try {
            val photo = cam.photo
            val progressJob = scope.launch {
                photo.transferProgressStream.collect { p -> _state.update { it.copy(transfer = p.fraction.toFloat()) } }
            }
            try {
                val ready = withTimeoutOrNull(10_000) { photo.state.first { it == PhotoState.STARTED } }
                if (ready == null) { Log.w(TAG, "full: Photo 시작 안 됨(${photo.state.value})"); return null }
                coroutineScope {
                    val err = async { photo.errors.first() }
                    val data = async { photo.photoStream.first() }
                    Log.i(TAG, "full: capture 요청 $size")
                    val t0 = System.currentTimeMillis()
                    photo.capturePhoto(size.resolution, size.quality)
                    val result = withTimeoutOrNull(45_000) {
                        select<Any> {
                            data.onAwait { it }
                            err.onAwait { it }
                        }
                    }
                    err.cancel(); data.cancel()
                    when (result) {
                        is PhotoCaptureData -> {
                            Log.i(TAG, "full: ${result.imageData.size / 1024}KB 수신 ${System.currentTimeMillis() - t0}ms")
                            result.imageData
                        }
                        is PhotoError -> { Log.w(TAG, "full: ${result.description}"); null }
                        else -> { Log.w(TAG, "full: 시간 초과"); null }
                    }
                }
            } finally {
                progressJob.cancel()
                runCatching { photo.stop() }
            }
        } finally {
            runCatching { cam.close() }
            s.removeCamera()
        }
    }

    /** 대체: 스트림을 잠깐 열고 in-stream 사진(저해상도). */
    private suspend fun captureInStream(s: DeviceSession): ByteArray? {
        val cam = s.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 2)).getOrNull() ?: return null
        return try {
            val stream = cam.stream
            val drain = scope.launch(Dispatchers.Default) { stream.videoStream.collect { } }
            try {
                if (stream.start().isFailure) return null
                val live = withTimeoutOrNull(8_000) { stream.state.first { it == StreamState.STREAMING } }
                if (live == null) return null
                val r = stream.capturePhoto()
                when (val d = r.getOrNull()) {
                    is PhotoData.Bitmap -> ByteArrayOutputStream().use { os ->
                        d.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os); os.toByteArray()
                    }
                    is PhotoData.HEIC -> d.data.duplicate().apply { rewind() }.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    null -> { Log.w(TAG, "stream: ${r.errorOrNull()?.description}"); null }
                }
            } finally {
                drain.cancel()
            }
        } finally {
            runCatching { cam.close() }
            s.removeCamera()
        }
    }

    fun disconnect() {
        session?.stop()
        cleanup()
    }

    private fun cleanup() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session = null
        _state.update { it.copy(session = null, transfer = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(error = msg) }
    }

    companion object {
        private const val TAG = "GlassesCamera"

        /**
         * 봇(hermes-agent `vision_analyze`)이 줄이지 않고 그대로 보는 한도 — 11-16 실측·소스 확인:
         * 긴 변 1568px 또는 base64 256KB 를 넘으면 해상도를 **반으로** 줄인다(1920×1080 → 960×540, 글 판독 불가).
         * 그래서 폰에서 미리 이 한도에 맞춰 보낸다.
         */
        const val VISION_MAX_SIDE = 1568
        const val VISION_MAX_BYTES = 190 * 1024
        private val JPEG_QUALITIES = intArrayOf(92, 88, 84, 80, 76, 72, 68, 62)

        /**
         * 이미지 바이트(JPEG/HEIC) → 바로 세운 업로드용 JPEG.
         * EXIF 회전을 직접 적용한다. EXIF 가 없는데 가로로 누운 사진이면 글라스 카메라(세로 센서)
         * 기준으로 시계 방향 90° 돌린다(11-15 실측: 1440×1080 이 왼쪽으로 누워 옴).
         */
        fun encode(bytes: ByteArray, maxSide: Int): GlassesPhoto? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            // 디코드 단계에서 대략 줄여 메모리 절약(12MP 원본).
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
            val degrees = rotationFor(orientation, src.width, src.height)
            Log.i(TAG, "photo ${bounds.outWidth}x${bounds.outHeight} exif=$orientation rotate=$degrees")
            val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (degrees != 0) postRotate(degrees.toFloat()) }
            val out = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            // 화질을 낮춰 가며 용량 한도 안에 드는 첫 결과를 쓴다(한도를 넘으면 봇 쪽에서 해상도를 반으로 줄인다).
            var jpeg = ByteArray(0)
            for (q in JPEG_QUALITIES) {
                jpeg = ByteArrayOutputStream().use { os -> out.compress(Bitmap.CompressFormat.JPEG, q, os); os.toByteArray() }
                if (jpeg.size <= VISION_MAX_BYTES) break
            }
            val (w, h) = if (degrees % 180 != 0) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
            return GlassesPhoto(jpeg, out.width, out.height, w, h)
        }

        /** 순수 함수(테스트용): EXIF 값과 크기로 시계 방향 회전 각도. */
        fun rotationFor(exif: Int, width: Int, height: Int): Int = when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            ExifInterface.ORIENTATION_NORMAL -> 0
            else -> if (width > height) 90 else 0
        }
    }
}
