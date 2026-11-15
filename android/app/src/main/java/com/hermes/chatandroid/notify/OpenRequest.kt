package com.hermes.chatandroid.notify

import android.content.Intent

/** 알림을 눌러 앱을 열 때 이동할 곳. */
sealed interface OpenRequest {
    data class Channel(val channelId: Int) : OpenRequest
    data object Approvals : OpenRequest
    /** 정적 바로가기: approvals | kanban | cron */
    data class Tab(val route: String) : OpenRequest
    /** Live 진행 알림 탭. */
    data object Live : OpenRequest

    companion object {
        fun from(intent: Intent?): OpenRequest? = when {
            intent == null -> null
            intent.hasExtra(Notifications.EXTRA_CHANNEL) -> Channel(intent.getIntExtra(Notifications.EXTRA_CHANNEL, 0))
            intent.getBooleanExtra(Notifications.EXTRA_APPROVALS, false) -> Approvals
            intent.getBooleanExtra(com.hermes.chatandroid.live.LiveService.EXTRA_OPEN_LIVE, false) -> Live
            intent.getStringExtra("open_tab") != null -> Tab(intent.getStringExtra("open_tab")!!)
            else -> null
        }
    }
}
