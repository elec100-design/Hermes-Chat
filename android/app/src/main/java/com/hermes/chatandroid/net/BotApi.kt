package com.hermes.chatandroid.net

import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotApproval
import com.hermes.chatandroid.data.BotAttachment
import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.data.BotHealthSummary
import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.BotPatch
import com.hermes.chatandroid.data.BotPostResult
import com.hermes.chatandroid.data.BotStep
import com.hermes.chatandroid.data.BotStreamEvent
import com.hermes.chatandroid.data.BrowseResult
import com.hermes.chatandroid.data.ChannelPatch
import com.hermes.chatandroid.data.DiscoverResult
import com.hermes.chatandroid.data.Parsers
import com.hermes.chatandroid.data.PushRequestResult
import com.hermes.chatandroid.data.Workspace
import com.hermes.chatandroid.data.WorkspaceStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** Hermes-bot(:8900) 클라이언트. [token] 이 비어 있으면 인증 헤더 없음. */
open class BotApi(
    private val baseUrl: String,
    private val token: String,
    client: OkHttpClient = defaultHttpClient(),
) {
    private val http = client
    /** 승인은 최대 200초 걸릴 수 있다. */
    private val longHttp = client.newBuilder().readTimeout(210, TimeUnit.SECONDS).build()
    private val streamHttp = client.newBuilder().readTimeout(600, TimeUnit.SECONDS).build()

    private fun req(path: String) = Request.Builder().url(joinUrl(baseUrl, path)).bearer(token)

    open suspend fun health(): Boolean = try {
        http.fetchText(Request.Builder().url(joinUrl(baseUrl, "health")).build()); true
    } catch (e: Exception) { false }

    open suspend fun fetchChannels(): List<BotChannel> =
        Parsers.parseBotChannels(http.fetchText(req("api/channels").build()))

    open suspend fun fetchBots(activeOnly: Boolean = true): List<Bot> =
        Parsers.parseBots(http.fetchText(req(if (activeOnly) "api/bots?status=active" else "api/bots").build()))

    open suspend fun fetchMessages(channelId: Int): List<BotMessage> =
        Parsers.parseBotMessages(http.fetchText(req("api/channels/$channelId/messages").build()))

    /**
     * 사용자 발화. DM 은 그 봇이, 채널은 본문의 첫 @slug 만 답한다(멘션 없으면 result.jobId == null).
     * [attachments] 는 [uploadAttachment] 응답을 그대로 넘긴다. [workdir] 는 등록된 작업 위치만 허용(아니면 400 detail).
     * 본문이 비어도 첨부가 있으면 보낼 수 있다.
     */
    open suspend fun postMessage(
        channelId: Int,
        body: String,
        attachments: List<BotAttachment> = emptyList(),
        workdir: String? = null,
    ): BotPostResult {
        val json = buildJsonObject {
            put("body", body)
            if (attachments.isNotEmpty()) put("attachments", buildJsonArray {
                attachments.forEach { a ->
                    add(buildJsonObject {
                        put("name", a.name); put("path", a.path)
                        a.mime?.let { put("mime", it) }
                        a.size?.let { put("size", it) }
                    })
                }
            })
            if (!workdir.isNullOrBlank()) put("workdir", workdir)
        }
        val text = http.fetchText(req("api/channels/$channelId/messages").post(jsonBody(json.toString())).build())
        return Parsers.parseBotPostResult(text)
    }

    open suspend fun createChannel(
        title: String,
        topic: String? = null,
        botIds: List<Int> = emptyList(),
        workdir: String? = null,
        /** null = 서버 기본(true). */
        botChat: Boolean? = null,
    ): BotChannel {
        val json = buildJsonObject {
            put("title", title)
            if (!topic.isNullOrBlank()) put("topic", topic)
            if (!workdir.isNullOrBlank()) put("workdir", workdir)
            botChat?.let { put("bot_chat", if (it) 1 else 0) }
            if (botIds.isNotEmpty()) put("bot_ids", buildJsonArray { botIds.forEach { add(it) } })
        }
        val text = http.fetchText(req("api/channels").post(jsonBody(json.toString())).build())
        return Parsers.parseJsonChannel(text) ?: throw ApiException(0, "채널 응답을 해석할 수 없습니다")
    }

    // ---- 봇 관리 ----

    /** 수정한 봇 행(api_key 없음, dm_channel_id/preview/tone 도 없음 — 호출 측에서 기존 값과 병합). */
    open suspend fun updateBot(id: Int, patch: BotPatch): Bot {
        val json = buildJsonObject {
            patch.name?.let { put("name", it) }
            patch.avatar?.let { put("avatar", it) }
            patch.icon?.let { put("icon", it) }
            patch.personaNote?.let { put("persona_note", it) }
            patch.workdir?.let { put("workdir", it) }
            patch.readOnly?.let { put("read_only", if (it) 1 else 0) }
            patch.approvalPolicy?.let { put("approval_policy", it) }
            patch.computerUse?.let { put("computer_use", if (it) 1 else 0) }
            patch.status?.let { put("status", it) }
        }
        val text = http.fetchText(req("api/bots/$id").patch(jsonBody(json.toString())).build())
        return Parsers.parseBotObject(text) ?: throw ApiException(0, "봇 응답을 해석할 수 없습니다")
    }

    /** 프로필 재검색(Bridge/포트 스캔). 오래 걸릴 수 있다. */
    open suspend fun discoverBots(): DiscoverResult =
        Parsers.parseDiscoverResult(longHttp.fetchText(req("api/bots/discover").post(jsonBody("{}")).build()))

    /** 게이트웨이 헬스 갱신 → 온라인 수 / 전체. 이후 [fetchBots] 로 health 점을 다시 읽는다. */
    open suspend fun refreshHealth(): BotHealthSummary =
        Parsers.parseHealthSummary(longHttp.fetchText(req("api/bots/health").post(jsonBody("{}")).build()))

    /** 지난 발화의 실행 트레이스(seq 오름차순). 없는 메시지는 404 detail. */
    open suspend fun fetchSteps(messageId: Int): List<BotStep> =
        Parsers.parseSteps(http.fetchText(req("api/messages/$messageId/steps").build()))

    // ---- 첨부 ----

    /** 파일을 봇이 읽을 수 있는 자리에 올린다(DM=그 봇, 채널=첫 멤버 프로필). 상한 초과/빈 채널은 400 detail. */
    open suspend fun uploadAttachment(channelId: Int, filename: String, bytes: ByteArray, mime: String? = null): BotAttachment {
        val part = bytes.toRequestBody(mime?.toMediaTypeOrNull())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", filename, part).build()
        val text = longHttp.fetchText(req("api/channels/$channelId/attachments").post(body).build())
        return Parsers.parseUpload(text) ?: throw ApiException(0, "업로드 응답에 path 가 없습니다")
    }

    // ---- 채널 관리 ----

    /** 수정된 채널 행(members/preview 없음 — 호출 측에서 병합). */
    open suspend fun updateChannel(id: Int, patch: ChannelPatch): BotChannel {
        val json = buildJsonObject {
            patch.title?.let { put("title", it) }
            patch.topic?.let { put("topic", it) }
            patch.workdir?.let { put("workdir", it) }
            patch.botChat?.let { put("bot_chat", if (it) 1 else 0) }
            patch.status?.let { put("status", it) }
        }
        val text = http.fetchText(req("api/channels/$id").patch(jsonBody(json.toString())).build())
        return Parsers.parseJsonChannel(text) ?: throw ApiException(0, "채널 응답을 해석할 수 없습니다")
    }

    open suspend fun addChannelMember(channelId: Int, botId: Int) {
        val json = buildJsonObject { put("bot_id", botId) }
        http.fetchText(req("api/channels/$channelId/members").post(jsonBody(json.toString())).build())
    }

    open suspend fun removeChannelMember(channelId: Int, botId: Int) {
        http.fetchText(req("api/channels/$channelId/members/$botId").delete().build())
    }

    /** DM 채널은 400 detail("봇 전용 대화창은 지울 수 없습니다…") → [ApiException]. */
    open suspend fun deleteChannel(channelId: Int) {
        http.fetchText(req("api/channels/$channelId").delete().build())
    }

    // ---- 작업 위치 ----

    open suspend fun fetchWorkspaces(): List<Workspace> =
        Parsers.parseWorkspaces(http.fetchText(req("api/workspaces").build()))

    /** 서버 폴더 탐색(홈/HB_BROWSE_ROOT 아래만, 하위 폴더만). [path] null = 루트. 범위 밖 400 detail. */
    open suspend fun browseWorkspaces(path: String? = null): BrowseResult {
        val q = if (path.isNullOrEmpty()) "" else "?path=" + URLEncoder.encode(path, "UTF-8")
        return Parsers.parseBrowse(http.fetchText(req("api/workspaces/browse$q").build()))
            ?: throw ApiException(0, "폴더 응답을 해석할 수 없습니다")
    }

    /** 로컬 폴더 등록. 없는 폴더/중복은 400 detail. */
    open suspend fun addWorkspaceFolder(path: String, label: String? = null): Workspace {
        val json = buildJsonObject {
            put("path", path)
            if (!label.isNullOrBlank()) put("label", label)
        }
        val text = http.fetchText(req("api/workspaces").post(jsonBody(json.toString())).build())
        return Parsers.parseWorkspaceObject(text) ?: throw ApiException(0, "작업 위치 응답을 해석할 수 없습니다")
    }

    /** GitHub 리포 clone 후 등록. [repo] = "owner/repo" 또는 GitHub URL. 시간이 걸린다. */
    open suspend fun cloneWorkspace(repo: String, branch: String? = null, label: String? = null): Workspace {
        val json = buildJsonObject {
            put("repo", repo)
            if (!branch.isNullOrBlank()) put("branch", branch)
            if (!label.isNullOrBlank()) put("label", label)
        }
        val text = longHttp.fetchText(req("api/workspaces/clone").post(jsonBody(json.toString())).build())
        return Parsers.parseWorkspaceObject(text) ?: throw ApiException(0, "작업 위치 응답을 해석할 수 없습니다")
    }

    /** 목록에서만 제거(디스크 폴더는 그대로). 서버 안내 문구(note)를 돌려준다. */
    open suspend fun removeWorkspace(id: Int): String? =
        Parsers.parseNote(http.fetchText(req("api/workspaces/$id").delete().build()))

    open suspend fun fetchWorkspaceStatus(id: Int): WorkspaceStatus =
        Parsers.parseWorkspaceStatus(http.fetchText(req("api/workspaces/$id/status").build()))

    /** 로컬 커밋(전체 add -A) → 커밋 sha. 메시지 비어 있음/변경 없음은 400 detail. */
    open suspend fun commitWorkspace(id: Int, message: String): String? {
        val json = buildJsonObject { put("message", message) }
        return Parsers.parseCommitSha(http.fetchText(req("api/workspaces/$id/commit").post(jsonBody(json.toString())).build()))
    }

    /** 푸시는 되돌릴 수 없어 즉시 실행되지 않고 승인 항목(kind=git_push)이 생긴다. */
    open suspend fun requestWorkspacePush(id: Int, branch: String? = null, botId: Int? = null): PushRequestResult {
        val json = buildJsonObject {
            if (!branch.isNullOrBlank()) put("branch", branch)
            botId?.let { put("bot_id", it) }
        }
        return Parsers.parsePushRequest(
            longHttp.fetchText(req("api/workspaces/$id/push").post(jsonBody(json.toString())).build()),
        )
    }

    open suspend fun fetchApprovals(status: String = "pending"): List<BotApproval> =
        Parsers.parseBotApprovals(http.fetchText(req("api/approvals?status=$status").build()))

    open suspend fun approve(id: Int, note: String? = null) = decide(id, "approve", note)
    open suspend fun reject(id: Int, note: String? = null) = decide(id, "reject", note)

    private suspend fun decide(id: Int, action: String, note: String?) {
        val json = buildJsonObject { if (!note.isNullOrBlank()) put("note", note) }
        longHttp.fetchText(req("api/approvals/$id/$action").post(jsonBody(json.toString())).build())
    }

    /**
     * 잡 SSE. 구독할 때마다 처음부터 재생되므로 수집 측은 구독 시 버퍼를 리셋해야 한다.
     * `end` 이벤트 이후 흐름이 완료된다. 수집 취소 = 연결 종료.
     */
    open fun jobStream(jobId: Int): Flow<BotStreamEvent> =
        streamHttp.sseFrames(req("jobs/$jobId/stream").header("Accept", "text/event-stream").build())
            .mapNotNull { mapFrame(it) }

    companion object {
        fun mapFrame(f: SseFrame): BotStreamEvent? {
            val obj = parseJsonOrNull(f.data).asObject()
            return when (f.event) {
                "steps" -> BotStreamEvent.Steps(Parsers.parseStepList(obj?.array("steps")))
                "delta" -> (obj?.text("text") ?: obj?.text("delta"))?.let { BotStreamEvent.Delta(it) }
                "end" -> BotStreamEvent.End(
                    obj?.text("status") ?: "done", obj?.text("error"), Parsers.parseStepList(obj?.array("steps")),
                )
                "error" -> BotStreamEvent.Error(obj?.text("message") ?: f.data.ifBlank { "오류" })
                else -> null
            }
        }
    }
}
