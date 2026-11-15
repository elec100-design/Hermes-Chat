package com.hermes.chatandroid.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hermes.chatandroid.HermesApp
import com.hermes.chatandroid.MainActivity
import com.hermes.chatandroid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Live 대화를 화면이 꺼져도 유지하는 포그라운드 서비스(마이크 + 연결 기기).
 * 미디어 세션으로 글라스/이어폰 탭(재생·일시정지·다음)을 받아 [LiveController.primary] 로 넘긴다.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var media: MediaSession? = null

    private val live get() = (application as HermesApp).container.live

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { live.stop(); return START_NOT_STICKY }
            ACTION_PRIMARY -> { live.primary(); return START_NOT_STICKY }
        }
        createChannel(this)
        // connectedDevice 는 블루투스 권한이 있어야 한다(없이 선언하면 Android 14+ 에서 SecurityException).
        val btGranted = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val types = (if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
            (if (btGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        ServiceCompat.startForeground(this, NOTIF_ID, notification("듣는 중"), types)
        if (media != null) return START_NOT_STICKY
        startMediaSession()
        scope.launch {
            live.ui.map { it.phase }.distinctUntilChanged().collect { p ->
                val text = when (p) {
                    LivePhase.LISTENING -> "듣는 중"
                    LivePhase.THINKING -> "생각 중"
                    LivePhase.SPEAKING -> "답하는 중"
                    LivePhase.WAITING -> "대기 — 탭하면 다시 듣기"
                    LivePhase.IDLE -> "종료"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun startMediaSession() {
        media = MediaSession(this, "HermesLive").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val ev = if (Build.VERSION.SDK_INT >= 33) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (ev?.action != KeyEvent.ACTION_DOWN) return true
                    when (ev.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        -> { live.primary(); return true }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            // 재생 중 상태여야 시스템이 미디어 버튼을 이 세션으로 보낸다.
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            isActive = true
        }
    }

    private fun notification(status: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_hermes)
        .setContentTitle("Hermes Live · ${live.ui.value.target?.title ?: ""}")
        .setContentText(status)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_LIVE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(0, "탭", servicePending(ACTION_PRIMARY, 1))
        .addAction(0, "종료", servicePending(ACTION_STOP, 2))
        .build()

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, LiveService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        media?.release(); media = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "live"
        private const val NOTIF_ID = 4201
        private const val ACTION_STOP = "live.stop"
        private const val ACTION_PRIMARY = "live.primary"
        const val EXTRA_OPEN_LIVE = "open_live"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LiveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Live 대화", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live 대화가 진행 중일 때"
                },
            )
        }
    }
}
