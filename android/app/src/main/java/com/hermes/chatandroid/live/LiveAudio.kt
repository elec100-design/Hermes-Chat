package com.hermes.chatandroid.live

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Live 모드 오디오 (T-187). 메인 스레드 전용.
 * - 라우팅: 세션 동안 통화 모드 + 블루투스 헤드셋(글라스) 고정. 마이크와 TTS 가 같은 SCO 경로를 써서
 *   HFP↔A2DP 전환 지연(수 초)이 없다. BT 가 없으면 폰 마이크·스피커.
 * - 인식: 시스템 SpeechRecognizer(ko-KR). 침묵으로 문장이 끝나면 결과가 온다.
 * - TTS: 문장 큐. 큐가 비면 [onSpeechDrained].
 */
class LiveAudio(context: Context) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()
    private var speaking = 0
    private var routed = false
    private var usedLegacySco = false

    var onSpeechDrained: () -> Unit = {}

    /** 현재 블루투스 헤드셋으로 라우팅됐는지(화면 표시용). */
    var bluetoothRouted = false
        private set

    fun start() {
        if (tts == null) {
            tts = TextToSpeech(ctx) { status ->
                main.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    tts?.language = Locale.KOREAN
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    flushPending()
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { utteranceFinished() } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { main.post { utteranceFinished() } }
                override fun onStop(id: String?, interrupted: Boolean) { main.post { utteranceFinished() } }
            })
        }
        routeToHeadset()
    }

    private fun routeToHeadset() {
        if (routed) return
        routed = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val bt = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            bluetoothRouted = bt != null && am.setCommunicationDevice(bt)
        } else {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoAvailableOffCall) {
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                usedLegacySco = true
                bluetoothRouted = true
            }
        }
    }

    private fun releaseRoute() {
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        if (usedLegacySco) {
            @Suppress("DEPRECATION") am.isBluetoothScoOn = false
            @Suppress("DEPRECATION") am.stopBluetoothSco()
            usedLegacySco = false
        }
        am.mode = AudioManager.MODE_NORMAL
        bluetoothRouted = false
    }

    // ---- 인식 ----

    interface Listener {
        fun onReady() {}
        fun onPartial(text: String) {}
        /** 말이 끝남(결과 전). 사진 촬영을 여기서 시작해 지연을 겹친다. */
        fun onEndOfSpeech() {}
        fun onResult(text: String)
        /** 무발화/인식 실패 — 절대 전송하지 않는다. */
        fun onSilence()
        fun onError(message: String)
    }

    fun listen(listener: Listener) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.onEndOfSpeech()
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                val t = first(results)
                if (t.isNullOrBlank()) listener.onSilence() else listener.onResult(t)
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onSilence()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                        // 직전 세션이 덜 끝남 — 새 인식기로 잠시 후 재시도.
                        recognizer?.destroy(); recognizer = null
                        main.postDelayed({ listener.onSilence() }, 400)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onError("마이크 권한이 없습니다")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> listener.onError("음성 인식 네트워크 오류")
                    else -> listener.onError("음성 인식 오류($error)")
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        r.startListening(intent)
    }

    /** 지금까지 들은 것으로 바로 결과를 낸다(탭 = 즉시 전송). */
    fun finishListening() { recognizer?.stopListening() }

    fun cancelListening() { recognizer?.cancel() }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    // ---- TTS ----

    val isSpeaking: Boolean get() = speaking > 0 || pendingSpeech.isNotEmpty()

    fun speak(text: String) {
        val t = LiveText.forSpeech(text)
        if (t.isEmpty()) return
        pendingSpeech.addLast(t)
        flushPending()
    }

    private fun flushPending() {
        val engine = tts ?: return
        if (!ttsReady) return
        while (pendingSpeech.isNotEmpty()) {
            val t = pendingSpeech.removeFirst()
            speaking++
            engine.speak(t, TextToSpeech.QUEUE_ADD, null, "live-${System.nanoTime()}")
        }
    }

    private fun utteranceFinished() {
        if (speaking > 0) speaking--
        if (speaking == 0 && pendingSpeech.isEmpty()) onSpeechDrained()
    }

    fun stopSpeaking() {
        pendingSpeech.clear()
        speaking = 0
        tts?.stop()
    }

    fun release() {
        stopSpeaking()
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null; ttsReady = false
        releaseRoute()
    }
}
