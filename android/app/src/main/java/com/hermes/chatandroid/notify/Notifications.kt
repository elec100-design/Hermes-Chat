package com.hermes.chatandroid.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermes.chatandroid.HermesApp
import com.hermes.chatandroid.MainActivity
import com.hermes.chatandroid.R
import java.util.concurrent.TimeUnit

/** SharedPreferences 기반 기준점 저장소. */
class PrefsNotifyStore(context: Context) : NotifyStore {
    private val p = context.getSharedPreferences("notify", Context.MODE_PRIVATE)
    override var initialized: Boolean
        get() = p.getBoolean("initialized", false)
        set(v) { p.edit().putBoolean("initialized", v).apply() }
    override fun lastSeen(channelId: Int) = p.getInt("seen_$channelId", 0)
    override fun setLastSeen(channelId: Int, messageId: Int) { p.edit().putInt("seen_$channelId", messageId).apply() }
    override fun preview(channelId: Int): String? = p.getString("preview_$channelId", null)
    override fun setPreview(channelId: Int, preview: String) { p.edit().putString("preview_$channelId", preview).apply() }
    override var notifiedApprovals: Set<Int>
        get() = p.getStringSet("approvals", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) { p.edit().putStringSet("approvals", v.map { it.toString() }.toSet()).apply() }
}

object Notifications {
    const val CH_REPLY = "bot_replies"
    const val CH_APPROVAL = "approvals"
    const val EXTRA_CHANNEL = "open_channel_id"
    const val EXTRA_APPROVALS = "open_approvals"
    private const val WORK = "bot-notify"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REPLY, "봇 답장", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "봇이 답을 마쳤을 때"
        })
        nm.createNotificationChannel(NotificationChannel(CH_APPROVAL, "승인 요청", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "봇이 실행 전 승인을 요청할 때"
        })
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openIntent(context: Context, requestCode: Int, fill: Intent.() -> Unit): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            fill()
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @android.annotation.SuppressLint("MissingPermission") // canPost 로 확인
    fun post(context: Context, events: List<NotifyEvent>) {
        if (events.isEmpty() || !canPost(context)) return
        val nm = NotificationManagerCompat.from(context)
        for (e in events) when (e) {
            is NotifyEvent.Reply -> {
                val body = e.message.error?.let { "실패: $it" } ?: BotNotifier.plain(e.message.body)
                val title = if (e.count > 1) "${e.title} · 새 답 ${e.count}개" else e.title
                val n = NotificationCompat.Builder(context, CH_REPLY)
                    .setSmallIcon(R.drawable.ic_stat_hermes)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(openIntent(context, e.channelId) { putExtra(EXTRA_CHANNEL, e.channelId) })
                    .build()
                nm.notify("reply", e.channelId, n)
            }
            is NotifyEvent.Approval -> {
                val a = e.approval
                val text = listOfNotNull(e.botName, a.risk?.let { "위험도 $it" }).joinToString(" · ")
                val n = NotificationCompat.Builder(context, CH_APPROVAL)
                    .setSmallIcon(R.drawable.ic_stat_hermes)
                    .setContentTitle("승인 요청: ${a.title}")
                    .setContentText(text.ifBlank { a.detail.orEmpty() })
                    .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text.ifBlank { null }, a.detail).joinToString("\n")))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(openIntent(context, 100_000 + a.id) { putExtra(EXTRA_APPROVALS, true) })
                    .build()
                nm.notify("approval", a.id, n)
            }
        }
    }

    /** 15분 주기 백그라운드 확인(WorkManager 최소 주기). 꺼져 있으면 취소. */
    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HermesApp).container
        val s = container.settingsRepository.current
        if (!s.notificationsEnabled || s.demoMode) return Result.success()
        val api = container.botApi() ?: return Result.success()
        return try {
            val (events, _) = container.notifier.check(api)
            Notifications.post(applicationContext, events)
            Result.success()
        } catch (e: Exception) {
            // Tailscale 꺼짐 등 — 다음 주기에 다시
            Result.success()
        }
    }
}
