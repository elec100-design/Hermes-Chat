package com.hermes.chatandroid.shortcut

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.hermes.chatandroid.MainActivity

/** 빠른 설정 타일 "Hermes" — 마지막으로 연 봇 대화를 바로 연다(없으면 앱 홈). */
class HermesTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = Shortcuts.lastChannel(this)?.second?.takeIf { it.isNotBlank() } ?: "대화 열기"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val last = Shortcuts.lastChannel(this)
        val intent = if (last != null) Shortcuts.channelIntent(this, last.first)
        else Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
