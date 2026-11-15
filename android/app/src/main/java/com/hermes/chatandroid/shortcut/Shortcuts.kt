package com.hermes.chatandroid.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hermes.chatandroid.MainActivity
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.notify.Notifications
import com.hermes.chatandroid.ui.bots.BotAvatarPresets

/**
 * 진입 단축(T-189): 봇 대화를 열 때마다 그 봇을 동적 바로가기로 올리고(런처가 최근 순으로 정리),
 * 빠른 설정 타일이 열 "마지막 대화"를 기록한다.
 */
object Shortcuts {
    private const val PREFS = "shortcuts"

    fun channelIntent(context: Context, channelId: Int): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Notifications.EXTRA_CHANNEL, channelId)
        }

    /** 채팅을 열었을 때 호출. [bot] 이 있으면(DM) 바로가기도 갱신. */
    fun chatOpened(context: Context, channelId: Int, title: String, bot: Bot?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("last_channel", channelId).putString("last_title", title).apply()
        if (bot == null) return
        runCatching {
            val info = ShortcutInfoCompat.Builder(context, "bot_${bot.id}")
                .setShortLabel(bot.displayName)
                .setLongLabel("${bot.displayName} 대화")
                .setIcon(IconCompat.createWithAdaptiveBitmap(avatarBitmap(context, bot)))
                .setIntent(channelIntent(context, channelId))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
        }
    }

    fun lastChannel(context: Context): Pair<Int, String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getInt("last_channel", -1)
        return if (id < 0) null else id to p.getString("last_title", "").orEmpty()
    }

    /** 적응형 아이콘용 108px 비트맵: 프리셋 이미지면 그대로, 아니면 tone 색 바탕에 이모지. */
    private fun avatarBitmap(context: Context, bot: Bot): Bitmap {
        val size = 216
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tones = intArrayOf(0xFF5B8DEF.toInt(), 0xFF2CB67D.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt(), 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt())
        c.drawColor(bot.tone?.takeIf { it in 1..6 }?.let { tones[it - 1] } ?: 0xFF5B4BDB.toInt())
        val preset = BotAvatarPresets.find(bot.avatarText)
        if (preset != null) {
            val src = BitmapFactory.decodeResource(context.resources, preset.drawableRes)
            // 적응형 아이콘은 가운데 약 66%만 보이므로 그 안전 영역에 맞춘다(픽셀아트라 필터링 끔)
            val inset = (size * 0.17f).toInt()
            c.drawBitmap(src, null, Rect(inset, inset, size - inset, size - inset), Paint().apply { isFilterBitmap = false })
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.36f; textAlign = Paint.Align.CENTER }
            val r = RectF(0f, 0f, size.toFloat(), size.toFloat())
            c.drawText(bot.avatarText, r.centerX(), r.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return bmp
    }
}
