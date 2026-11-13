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
    val gatewayPort: Int? = null,
    /** 서버는 api_key 를 내보내지 않고 "있다/없다"만 준다. */
    val hasApiKey: Boolean? = null,
    val personaNote: String? = null,
    val icon: String? = null,
    val workdir: String? = null,
    /** "auto" 등 서버 정책 문자열. */
    val approvalPolicy: String? = null,
    val computerUse: Boolean? = null,
    val lastSeenAt: String? = null,
    val nameMismatch: String? = null,
    /** "discovered" 등. */
    val source: String? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotEmpty() } ?: slug
    val avatarText: String get() = avatar?.takeIf { it.isNotEmpty() } ?: "🤖"
    val isArchived: Boolean get() = status == "archived"
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
    /** "channel" | "dm". `/api/channels` 목록에는 channel 만 온다. */
    val kind: String? = null,
    val workdir: String? = null,
    val createdAt: String? = null,
) {
    val isDm: Boolean get() = kind == "dm"
}

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
    val parentId: Int? = null,
    val rootId: Int? = null,
    /** 아이콘 키(화이트리스트 참조용). 대개 비어 있다. */
    val iconKey: String? = null,
) {
    /** `attachments` JSON 문자열을 풀어 낸 목록(없거나 깨졌으면 빈 목록). */
    val attachmentItems: List<BotAttachment> get() = Parsers.parseAttachments(attachments)
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
    val botName: String? = null,
    val botAvatar: String? = null,
    val botTone: Int? = null,
)

/**
 * 메시지에 딸린 첨부. 업로드 응답 `{name,path,mime,size,via}` 와 메시지 `attachments` JSON 항목의 공통 형태.
 * 메시지를 보낼 때는 업로드 응답을 그대로 되돌려 보낸다(서버가 path 를 검증한다).
 */
data class BotAttachment(
    val name: String,
    val path: String,
    val mime: String? = null,
    val size: Long? = null,
    /** 업로드 경로: "bridge" | "local" (로컬이면 프로필이 못 읽을 수 있다). */
    val via: String? = null,
) {
    val isImage: Boolean get() = mime?.startsWith("image/") == true
}

/** 실행 트레이스 한 단계. [kind]: tool | tool_result | diff | screen | think | note | error. */
data class BotStep(
    val id: Int,
    val messageId: Int? = null,
    val jobId: Int? = null,
    val seq: Int = 0,
    val kind: String = "note",
    val title: String = "",
    val detail: String = "",
    /** "done" | "running" | "failed" 등. */
    val status: String? = null,
    val createdAt: String? = null,
)

/** `PATCH /api/bots/{id}`. null = 변경 안 함. 문자열은 ""로 비울 수 있다. 불리언은 서버로 0/1 로 전송. */
data class BotPatch(
    val name: String? = null,
    val avatar: String? = null,
    val icon: String? = null,
    val personaNote: String? = null,
    val workdir: String? = null,
    val readOnly: Boolean? = null,
    val approvalPolicy: String? = null,
    val computerUse: Boolean? = null,
    /** "active" | "archived". */
    val status: String? = null,
)

/** `PATCH /api/channels/{id}`. null = 변경 안 함. */
data class ChannelPatch(
    val title: String? = null,
    val topic: String? = null,
    val workdir: String? = null,
    val botChat: Boolean? = null,
    /** "active" | "archived". */
    val status: String? = null,
)

/** `POST /api/bots/discover` 요약. [source]: "bridge" | "scan". [skipped] 는 게이트웨이가 아니라 건너뛴 포트 설명들. */
data class DiscoverResult(
    val source: String? = null,
    val found: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val offline: Int = 0,
    val archived: Int = 0,
    val skipped: List<String> = emptyList(),
)

/** `POST /api/bots/health`. */
data class BotHealthSummary(val online: Int, val total: Int)

/** 작업 위치. [kind]: "folder" | "repo". */
data class Workspace(
    val id: Int,
    val kind: String = "folder",
    val label: String,
    val path: String,
    val repoUrl: String? = null,
    val defaultBranch: String? = null,
    val createdAt: String? = null,
) {
    val isRepo: Boolean get() = kind == "repo"
}

data class BrowseEntry(val name: String, val path: String)

/** `GET /api/workspaces/browse`. [parent] null = 탐색 루트(더 못 올라감). */
data class BrowseResult(val path: String, val parent: String?, val entries: List<BrowseEntry>)

/** `GET /api/workspaces/{id}/status`. git 저장소가 아니면 [isRepo]=false, 나머지 기본값. */
data class WorkspaceStatus(
    val isRepo: Boolean,
    val branch: String? = null,
    val dirty: Boolean = false,
    val files: List<String> = emptyList(),
)

/** `POST /api/workspaces/{id}/push` — 즉시 푸시가 아니라 승인 항목이 생긴다(승인 탭에서 처리). */
data class PushRequestResult(val approvalId: Int?, val status: String?, val note: String?)

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
    /** 지금까지의 실행 단계 전체 스냅샷(증분이 아님 — 매번 통째로 교체). */
    data class Steps(val steps: List<BotStep>) : BotStreamEvent
    /** status: "done" | "failed". [steps] 는 종료 시점의 최종 스냅샷. */
    data class End(val status: String, val error: String?, val steps: List<BotStep> = emptyList()) : BotStreamEvent
    data class Error(val message: String) : BotStreamEvent
}
