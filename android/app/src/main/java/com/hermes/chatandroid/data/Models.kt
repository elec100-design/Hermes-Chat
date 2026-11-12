package com.hermes.chatandroid.data

/** 게이트웨이 세션. [updatedAt] 은 epoch 초(없으면 0). */
data class Session(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val updatedAt: Double = 0.0,
    val source: String? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: preview?.takeIf { it.isNotBlank() } ?: "새 대화"
}

data class SessionPage(val sessions: List<Session>, val hasMore: Boolean)

/** [role]: "user" | "assistant" | "system" | "tool". [id] 는 서버 Int/String 을 문자열로 정규화. */
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Double = 0.0,
) {
    val isUser: Boolean get() = role == "user"
}

/** Bridge /profiles 항목. */
data class BridgeProfile(val name: String, val port: Int, val apiEnabled: Boolean = true)

/** 설정에 저장되는 프로필 = 같은 호스트의 게이트웨이 포트. */
data class ProfileEntry(val name: String, val port: Int)

// ---- Hermes-bot ----

data class BotMember(
    val id: Int,
    val slug: String,
    val name: String? = null,
    val avatar: String? = null,
    val health: String? = null,
    val tone: Int? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotEmpty() } ?: slug
    val avatarText: String get() = avatar?.takeIf { it.isNotEmpty() } ?: "🤖"
}

data class Bot(
    val id: Int,
    val slug: String,
    val name: String? = null,
    val profileName: String? = null,
    val avatar: String? = null,
    val soulSummary: String? = null,
    val health: String? = null,
    val status: String? = null,
    val dmChannelId: Int? = null,
    val preview: String? = null,
    val tone: Int? = null,
    val readOnly: Boolean? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotEmpty() } ?: slug
    val avatarText: String get() = avatar?.takeIf { it.isNotEmpty() } ?: "🤖"
    val isOnline: Boolean get() = health == "online"
    val previewLine: String
        get() = preview?.takeIf { it.isNotEmpty() } ?: soulSummary?.takeIf { it.isNotEmpty() } ?: "@$slug"
}

data class BotChannel(
    val id: Int,
    val slug: String? = null,
    val title: String,
    val topic: String? = null,
    val botChat: Boolean? = null,
    val status: String? = null,
    val updatedAt: String? = null,
    val members: List<BotMember> = emptyList(),
    val preview: String? = null,
)

data class BotMessage(
    val id: Int,
    val channelId: Int? = null,
    val seq: Int? = null,
    /** "user" | "bot" | "system" */
    val role: String,
    val author: String? = null,
    val body: String = "",
    /** "queued" | "running" | "done" | "failed" */
    val status: String? = null,
    val jobId: Int? = null,
    val authorBotId: Int? = null,
    val attachments: String? = null,
    val error: String? = null,
    val createdAt: String? = null,
    val displayName: String? = null,
    val avatar: String? = null,
    val tone: Int? = null,
) {
    val isUser: Boolean get() = role == "user"
    val isSystem: Boolean get() = role == "system"
    val isInFlight: Boolean get() = status == "queued" || status == "running"
}

data class BotApproval(
    val id: Int,
    val kind: String? = null,
    val title: String,
    val detail: String? = null,
    val action: String? = null,
    val risk: String? = null,
    val status: String? = null,
    val botId: Int? = null,
    val channelId: Int? = null,
    val createdAt: String? = null,
)

data class BotPostResult(
    val messageId: Int? = null,
    val replyMessageId: Int? = null,
    val jobId: Int? = null,
    val bot: String? = null,
    val error: String? = null,
)

/** 게이트웨이 채팅 스트림 이벤트. */
sealed interface StreamEvent {
    /** 본문 조각 (이어 붙인다). */
    data class Delta(val text: String) : StreamEvent
    /** assistant.completed — 전체 본문. */
    data class Completed(val content: String) : StreamEvent
    /** tool.* 이벤트 (선택적 표시용). */
    data class Tool(val name: String?) : StreamEvent
    data class Error(val message: String) : StreamEvent
    data object Done : StreamEvent
}

/** Hermes-bot 잡 SSE 이벤트. */
sealed interface BotStreamEvent {
    data class Delta(val text: String) : BotStreamEvent
    /** status: "done" | "failed" */
    data class End(val status: String, val error: String?) : BotStreamEvent
    data class Error(val message: String) : BotStreamEvent
}
