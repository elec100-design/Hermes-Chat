package com.hermes.chatandroid.notify

import android.content.Intent

/** 알림을 눌러 앱을 열 때 이동할 곳. */
sealed interface OpenRequest {
    data class Channel(val channelId: Int) : OpenRequest
    data object Approvals : OpenRequest

    companion object {
        fun from(intent: Intent?): OpenRequest? = when {
            intent == null -> null
            intent.hasExtra(Notifications.EXTRA_CHANNEL) -> Channel(intent.getIntExtra(Notifications.EXTRA_CHANNEL, 0))
            intent.getBooleanExtra(Notifications.EXTRA_APPROVALS, false) -> Approvals
            else -> null
        }
    }
}
