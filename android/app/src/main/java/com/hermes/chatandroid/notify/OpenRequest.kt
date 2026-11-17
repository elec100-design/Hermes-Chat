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
    /** 초대 딥링크(`hermes://invite?url=...&code=...`). */
    data class Invite(val url: String?, val code: String) : OpenRequest

    companion object {
        fun from(intent: Intent?): OpenRequest? {
            if (intent == null) return null
            val data = intent.data
            if (data != null && (data.scheme == "hermes" || data.scheme == "hermeschat") && data.host == "invite") {
                val code = data.getQueryParameter("code")?.trim().orEmpty()
                if (code.isNotEmpty()) return Invite(data.getQueryParameter("url")?.trim(), code)
            }
            return when {
                intent.hasExtra(Notifications.EXTRA_CHANNEL) -> Channel(intent.getIntExtra(Notifications.EXTRA_CHANNEL, 0))
                intent.getBooleanExtra(Notifications.EXTRA_APPROVALS, false) -> Approvals
                intent.getBooleanExtra(com.hermes.chatandroid.live.LiveService.EXTRA_OPEN_LIVE, false) -> Live
                intent.getStringExtra("open_tab") != null -> Tab(intent.getStringExtra("open_tab")!!)
                else -> null
            }
        }
    }
}
