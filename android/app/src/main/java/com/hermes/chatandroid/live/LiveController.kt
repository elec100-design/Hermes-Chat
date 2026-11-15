package com.hermes.chatandroid.live

import android.content.Context
import android.graphics.Bitmap
import com.hermes.chatandroid.data.BotAttachment
import com.hermes.chatandroid.data.BotStreamEvent
import com.hermes.chatandroid.glasses.GlassesCamera
import com.hermes.chatandroid.glasses.GlassesPhoto
import com.hermes.chatandroid.net.BotApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class LivePhase { IDLE, LISTENING, THINKING, SPEAKING, WAITING }

/** 시야 사진을 붙이는 방식. */
enum class VisionMode(val label: String) {
    ALWAYS("말할 때마다"),
    ON_REQUEST("요청할 때만"),
    OFF("끔"),
}

/** Live 대화가 기록될 봇 채널. 채널(멤버 여럿)이면 [mentionSlug] 봇이 답한다. */
data class LiveTarget(val channelId: Int, val title: String, val mentionSlug: String? = null)

data class LiveUi(
    val target: LiveTarget? = null,
    val phase: LivePhase = LivePhase.IDLE,
    val partial: String = "",
    val lastUser: String = "",
    val reply: String = "",
    val photo: Bitmap? = null,
    val photoInfo: String? = null,
    /** 다음 발화에 붙을 수동 촬영 사진이 있음. */
    val photoPending: Boolean = false,
    val vision: VisionMode = VisionMode.ALWAYS,
    val bluetooth: Boolean = false,
    val error: String? = null,
)

/**
 * 핸즈프리 Live 대화 (T-187). 한 턴:
 * 듣기 → (말 끝 = 시야 사진 촬영 시작) → 인식 결과 + 사진 업로드 → 봇 채널에 전송 →
 * 잡 스트림 delta 를 문장 단위로 낭독 → 다 읽으면 다시 듣기.
 *
 * 원칙(사용자 피드백 11-15): 사진만으로는 절대 보내지 않는다. 무발화는 재청취(연속 [MAX_SILENCE]회면 대기).
 */
class LiveController(
    context: Context,
    private val botApi: () -> BotApi?,
    val glasses: GlassesCamera,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = LiveAudio(context)
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    private var turnJob: Job? = null
    private var photoTask: Deferred<GlassesPhoto?>? = null
    private var manualPhoto: GlassesPhoto? = null
    private var silenceCount = 0
    private var streamEnded = false

    /** 서비스 시작/정지 훅 — 앱이 연결한다. */
    var onActiveChanged: (Boolean) -> Unit = {}

    init {
        audio.onSpeechDrained = {
            if (_ui.value.phase == LivePhase.SPEAKING && streamEnded) listen()
        }
    }

    val active: Boolean get() = _ui.value.target != null

    fun start(target: LiveTarget) {
        val wasActive = active
        _ui.update { it.copy(target = target, error = null, reply = "", lastUser = "", partial = "") }
        audio.start()
        _ui.update { it.copy(bluetooth = audio.bluetoothRouted) }
        if (!wasActive) onActiveChanged(true)
        silenceCount = 0
        listen()
    }

    fun stop() {
        if (!active) return
        turnJob?.cancel(); turnJob = null
        photoTask?.cancel(); photoTask = null
        audio.cancelListening()
        audio.release()
        _ui.update { LiveUi(vision = it.vision) }
        onActiveChanged(false)
    }

    fun setVision(mode: VisionMode) = _ui.update { it.copy(vision = mode) }

    /** 화면 버튼·글라스 탭 공용: 대기→듣기, 듣는 중→바로 보내기, 낭독 중→끼어들기. */
    fun primary() {
        when (_ui.value.phase) {
            LivePhase.IDLE -> Unit
            LivePhase.WAITING -> { silenceCount = 0; listen() }
            LivePhase.LISTENING -> audio.finishListening()
            LivePhase.SPEAKING -> interrupt()
            LivePhase.THINKING -> Unit
        }
    }

    /** 낭독·대기 중단 후 바로 듣기. */
    fun interrupt() {
        turnJob?.cancel(); turnJob = null
        _ui.update { it.copy(phase = LivePhase.LISTENING) }
        audio.stopSpeaking()
        listen()
    }

    /** 지금 시야를 찍어 두고, 다음 발화와 함께 보낸다(사진만 보내지 않음). */
    fun takePhotoNow() {
        scope.launch {
            val p = glasses.capture()
            if (p == null) { _ui.update { it.copy(error = glasses.state.value.error ?: "글라스 카메라가 준비되지 않았습니다") }; return@launch }
            manualPhoto = p
            showPhoto(p, pending = true)
            // 듣는 중이면 안내 음성이 인식에 섞이지 않게 멈췄다가, 다 읽은 뒤 다시 듣는다.
            if (_ui.value.phase == LivePhase.LISTENING || _ui.value.phase == LivePhase.WAITING) {
                audio.cancelListening()
                photoTask?.cancel(); photoTask = null
                streamEnded = true
                _ui.update { it.copy(phase = LivePhase.SPEAKING, partial = "") }
                audio.speak("사진 받았어요. 질문해 주세요.")
                if (!audio.isSpeaking) listen()
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun listen() {
        if (!active) return
        photoTask?.cancel(); photoTask = null
        _ui.update { it.copy(phase = LivePhase.LISTENING, partial = "") }
        audio.listen(object : LiveAudio.Listener {
            override fun onPartial(text: String) = _ui.update { it.copy(partial = text) }

            override fun onEndOfSpeech() {
                // 촬영(BT 전송 수 초)을 인식 마무리와 겹쳐 지연을 줄인다. 요청형 모드는 결과를 보고 결정.
                if (_ui.value.vision == VisionMode.ALWAYS && manualPhoto == null && glasses.state.value.cameraReady) {
                    photoTask = scope.async { glasses.capture() }
                }
            }

            override fun onResult(text: String) {
                silenceCount = 0
                send(text)
            }

            override fun onSilence() {
                photoTask?.cancel(); photoTask = null
                if (!active || _ui.value.phase != LivePhase.LISTENING) return
                silenceCount++
                if (silenceCount >= MAX_SILENCE) _ui.update { it.copy(phase = LivePhase.WAITING, partial = "") }
                else listen()
            }

            override fun onError(message: String) {
                _ui.update { it.copy(phase = LivePhase.WAITING, error = message) }
            }
        })
    }

    private fun send(text: String) {
        val target = _ui.value.target ?: return
        val api = botApi() ?: run { _ui.update { it.copy(phase = LivePhase.WAITING, error = "봇 서버가 설정되지 않았습니다") }; return }
        _ui.update { it.copy(phase = LivePhase.THINKING, lastUser = text, partial = "", reply = "") }
        streamEnded = false
        turnJob = scope.launch {
            try {
                val photo = pickPhoto(text)
                val attachments = mutableListOf<BotAttachment>()
                if (photo != null) {
                    showPhoto(photo, pending = false)
                    attachments += api.uploadAttachment(
                        target.channelId, "glasses-${System.currentTimeMillis()}.jpg", photo.jpeg, "image/jpeg",
                    )
                }
                val res = api.postMessage(target.channelId, LiveText.withMention(text, target.mentionSlug), attachments)
                val jobId = res.jobId ?: throw IllegalStateException(res.error ?: "응답할 봇이 없습니다(채널은 @멘션 필요)")
                streamReply(api, target.channelId, jobId, res.replyMessageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = LivePhase.WAITING, error = e.message ?: "전송 실패") }
                audio.speak("전송에 실패했어요.")
            }
        }
    }

    private suspend fun pickPhoto(text: String): GlassesPhoto? {
        manualPhoto?.let { manualPhoto = null; photoTask?.cancel(); photoTask = null; return it }
        val pending = photoTask
        photoTask = null
        if (pending != null) return withTimeoutOrNull(PHOTO_TIMEOUT_MS) { pending.await() }
        val wants = when (_ui.value.vision) {
            VisionMode.ALWAYS -> true
            VisionMode.ON_REQUEST -> LiveText.wantsPhoto(text)
            VisionMode.OFF -> false
        }
        if (!wants || !glasses.state.value.cameraReady) return null
        return withTimeoutOrNull(PHOTO_TIMEOUT_MS) { glasses.capture() }
    }

    private suspend fun streamReply(api: BotApi, channelId: Int, jobId: Int, replyMessageId: Int?) {
        var buffer = ""
        var spokeAny = false
        var failed: String? = null
        api.jobStream(jobId).collect { ev ->
            when (ev) {
                is BotStreamEvent.Delta -> {
                    if (_ui.value.phase == LivePhase.THINKING) _ui.update { it.copy(phase = LivePhase.SPEAKING) }
                    _ui.update { it.copy(reply = it.reply + ev.text) }
                    val (sentences, rest) = LiveText.takeSentences(buffer + ev.text)
                    buffer = rest
                    sentences.forEach { audio.speak(it); spokeAny = true }
                }
                is BotStreamEvent.End -> if (ev.status == "failed") failed = ev.error ?: "봇 실행 실패"
                is BotStreamEvent.Error -> failed = ev.message
                is BotStreamEvent.Steps -> Unit
            }
        }
        if (buffer.isNotBlank()) { audio.speak(buffer); spokeAny = true }
        if (!spokeAny && failed == null) {
            // 스트림에 본문이 안 실려 온 경우 — 저장된 답을 읽는다.
            val body = runCatching { api.fetchMessages(channelId) }.getOrNull()
                ?.let { msgs -> msgs.firstOrNull { it.id == replyMessageId } ?: msgs.lastOrNull { it.role == "bot" } }
                ?.body.orEmpty()
            if (body.isNotBlank()) {
                _ui.update { it.copy(reply = body) }
                audio.speak(body); spokeAny = true
            }
        }
        if (failed != null) {
            _ui.update { it.copy(error = failed) }
            audio.speak("봇이 답하지 못했어요."); spokeAny = true
        }
        streamEnded = true
        _ui.update { it.copy(phase = LivePhase.SPEAKING) }
        if (!audio.isSpeaking) listen()
    }

    private fun showPhoto(p: GlassesPhoto, pending: Boolean) {
        _ui.update {
            it.copy(
                photo = p.preview,
                photoInfo = "원본 ${p.sourceWidth}×${p.sourceHeight} · ${p.jpeg.size / 1024}KB",
                photoPending = pending,
            )
        }
    }

    companion object {
        const val MAX_SILENCE = 4
        const val PHOTO_TIMEOUT_MS = 8_000L
    }
}
