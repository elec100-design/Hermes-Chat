package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.array
import com.hermes.chatandroid.net.asArray
import com.hermes.chatandroid.net.asBoolLenient
import com.hermes.chatandroid.net.asDoubleLenient
import com.hermes.chatandroid.net.asIntLenient
import com.hermes.chatandroid.net.asLongLenient
import com.hermes.chatandroid.net.asObject
import com.hermes.chatandroid.net.asText
import com.hermes.chatandroid.net.asTone
import com.hermes.chatandroid.net.bool
import com.hermes.chatandroid.net.int
import com.hermes.chatandroid.net.obj
import com.hermes.chatandroid.net.parseJsonOrNull
import com.hermes.chatandroid.net.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 순수 파싱 (android.* 의존 없음). 응답 형태가 달라도 가능한 한 살려서 해석한다. */
object Parsers {

    // ---- gateway ----

    private fun sessionFrom(o: JsonObject, id: String): Session {
        val ts = o["last_active"].asDoubleLenient() ?: o["started_at"].asDoubleLenient() ?: 0.0
        return Session(
            id = id,
            title = o.text("title"),
            preview = o.text("preview"),
            updatedAt = ts,
            source = o.text("source"),
        )
    }

    private fun sessionIdOf(o: JsonObject): String? =
        (o["id"] ?: o["session_id"] ?: o["sessionId"]).asText()?.takeIf { it.isNotEmpty() }

    fun parseSessionList(body: String): SessionPage {
        val root = parseJsonOrNull(body)
        val obj = root.asObject()
        val arr: JsonArray = root.asArray() ?: obj?.array("data") ?: obj?.array("sessions") ?: JsonArray(emptyList())
        val sessions = arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            sessionIdOf(o)?.let { sessionFrom(o, it) }
        }.sortedByDescending { it.updatedAt }
        return SessionPage(sessions, obj?.bool("has_more") ?: false)
    }

    /** ① {"session":{…}} ② 객체 자체 ③ {"data":{…}} ④ 깊이 3까지 id 탐색, 최상위 문자열 = id. */
    fun parseSessionResponse(body: String): Session? {
        val root = parseJsonOrNull(body) ?: return null
        (root as? JsonPrimitive)?.asText()?.takeIf { it.isNotEmpty() }?.let { return Session(it) }
        val obj = root.asObject()
        obj?.obj("session")?.let { o -> sessionIdOf(o)?.let { return sessionFrom(o, it) } }
        obj?.let { o -> sessionIdOf(o)?.let { return sessionFrom(o, it) } }
        obj?.obj("data")?.let { o -> sessionIdOf(o)?.let { return sessionFrom(o, it) } }
        return findSession(root, 0)
    }

    private fun findSession(el: JsonElement, depth: Int): Session? {
        if (depth > 3) return null
        when (el) {
            is JsonObject -> {
                sessionIdOf(el)?.let { return sessionFrom(el, it) }
                for (v in el.values) findSession(v, depth + 1)?.let { return it }
            }
            is JsonArray -> for (v in el) findSession(v, depth + 1)?.let { return it }
            else -> {}
        }
        return null
    }

    fun parseMessages(body: String): List<ChatMessage> {
        val root = parseJsonOrNull(body)
        val arr = root.asArray() ?: root.asObject()?.let { it.array("data") ?: it.array("messages") } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            ChatMessage(
                id = o.text("id") ?: "",
                role = o.text("role") ?: "assistant",
                content = o.text("content") ?: "",
                createdAt = o["timestamp"].asDoubleLenient() ?: 0.0,
            )
        }
    }

    fun parseModelIds(body: String): List<String> {
        val arr = parseJsonOrNull(body).asObject()?.array("data") ?: return emptyList()
        return arr.mapNotNull { it.asObject()?.text("id") }
    }

    // ---- bridge ----

    fun parseBridgeProfiles(body: String): List<BridgeProfile> {
        val root = parseJsonOrNull(body)
        val arr = root.asArray() ?: root.asObject()?.array("data") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            val name = o.text("name") ?: return@mapNotNull null
            val port = o.int("port") ?: return@mapNotNull null
            BridgeProfile(name, port, o.bool("api_enabled") ?: true)
        }
    }

    // ---- bot ----

    private fun dataArray(body: String): JsonArray {
        val root = parseJsonOrNull(body)
        return root.asArray() ?: root.asObject()?.array("data") ?: JsonArray(emptyList())
    }

    fun parseBotMember(o: JsonObject): BotMember? {
        val id = o.int("id") ?: return null
        return BotMember(
            id = id,
            slug = o.text("slug") ?: "bot$id",
            name = o.text("name"),
            avatar = o.text("avatar"),
            health = o.text("health"),
            tone = o["tone"].asTone(),
        )
    }

    /** 봇 행 하나. `PATCH /api/bots/{id}` 응답에는 dm_channel_id/preview/tone 이 없다(호출 측에서 병합). */
    fun parseBot(o: JsonObject): Bot? {
        val id = o.int("id") ?: return null
        return Bot(
            id = id,
            slug = o.text("slug") ?: "bot$id",
            name = o.text("name"),
            profileName = o.text("profile_name"),
            avatar = o.text("avatar"),
            soulSummary = o.text("soul_summary"),
            health = o.text("health"),
            status = o.text("status"),
            dmChannelId = o.int("dm_channel_id"),
            preview = o.text("preview"),
            tone = o["tone"].asTone(),
            readOnly = o.bool("read_only"),
            gatewayPort = o.int("gateway_port"),
            hasApiKey = o.bool("has_api_key"),
            personaNote = o.text("persona_note"),
            icon = o.text("icon"),
            workdir = o.text("workdir"),
            approvalPolicy = o.text("approval_policy"),
            computerUse = o.bool("computer_use"),
            lastSeenAt = o.text("last_seen_at"),
            nameMismatch = o.text("name_mismatch"),
            source = o.text("source"),
        )
    }

    fun parseBots(body: String): List<Bot> = dataArray(body).mapNotNull { it.asObject()?.let(::parseBot) }

    fun parseBotObject(body: String): Bot? = parseJsonOrNull(body).asObject()?.let(::parseBot)

    fun parseBotChannel(o: JsonObject): BotChannel? {
        val id = o.int("id") ?: return null
        return BotChannel(
            id = id,
            slug = o.text("slug"),
            title = o.text("title") ?: "#$id",
            topic = o.text("topic"),
            botChat = o.bool("bot_chat"),
            status = o.text("status"),
            updatedAt = o.text("updated_at"),
            members = o.array("members")?.mapNotNull { it.asObject()?.let(::parseBotMember) } ?: emptyList(),
            preview = o.text("preview"),
            kind = o.text("kind"),
            workdir = o.text("workdir"),
            createdAt = o.text("created_at"),
        )
    }

    /** 채널 행 하나(PATCH/POST 응답). */
    fun parseJsonChannel(body: String): BotChannel? = parseJsonOrNull(body).asObject()?.let(::parseBotChannel)

    fun parseBotChannels(body: String): List<BotChannel> =
        dataArray(body).mapNotNull { it.asObject()?.let(::parseBotChannel) }

    fun parseBotMessages(body: String): List<BotMessage> = dataArray(body).mapNotNull { el ->
        val o = el.asObject() ?: return@mapNotNull null
        val id = o.int("id") ?: return@mapNotNull null
        BotMessage(
            id = id,
            channelId = o.int("channel_id"),
            seq = o.int("seq"),
            role = o.text("role") ?: "bot",
            author = o.text("author"),
            body = o.text("body") ?: "",
            status = o.text("status"),
            jobId = o.int("job_id"),
            authorBotId = o.int("author_bot_id"),
            attachments = o.text("attachments"),
            error = o.text("error"),
            createdAt = o.text("created_at"),
            displayName = o.text("display_name"),
            avatar = o.text("avatar"),
            tone = o["tone"].asTone(),
            parentId = o.int("parent_id"),
            rootId = o.int("root_id"),
            iconKey = o.text("icon_key")?.takeIf { it.isNotEmpty() },
        )
    }

    fun parseBotApprovals(body: String): List<BotApproval> = dataArray(body).mapNotNull { el ->
        val o = el.asObject() ?: return@mapNotNull null
        val id = o.int("id") ?: return@mapNotNull null
        BotApproval(
            id = id,
            kind = o.text("kind"),
            title = o.text("title") ?: "#$id",
            detail = o.text("detail"),
            action = o.text("action"),
            risk = o.text("risk"),
            status = o.text("status"),
            botId = o.int("bot_id"),
            channelId = o.int("channel_id"),
            createdAt = o.text("created_at"),
        )
    }

    fun parseBotPostResult(body: String): BotPostResult {
        val o = parseJsonOrNull(body).asObject() ?: return BotPostResult()
        return BotPostResult(
            messageId = o.int("message_id"),
            replyMessageId = o.int("reply_message_id"),
            jobId = o.int("job_id"),
            bot = o.text("bot"),
            error = o.text("error"),
            botName = o.text("bot_name"),
            botAvatar = o.text("bot_avatar"),
            botTone = o["bot_tone"].asTone(),
        )
    }

    // ---- steps / attachments ----

    fun parseStep(o: JsonObject): BotStep? {
        val id = o.int("id") ?: return null
        return BotStep(
            id = id,
            messageId = o.int("message_id"),
            jobId = o.int("job_id"),
            seq = o.int("seq") ?: 0,
            kind = o.text("kind") ?: "note",
            title = o.text("title") ?: "",
            detail = o.text("detail") ?: "",
            status = o.text("status"),
            createdAt = o.text("created_at"),
        )
    }

    fun parseStepList(arr: JsonArray?): List<BotStep> =
        arr?.mapNotNull { it.asObject()?.let(::parseStep) }?.sortedBy { it.seq } ?: emptyList()

    /** `GET /api/messages/{id}/steps` → `{"data":[…]}`. */
    fun parseSteps(body: String): List<BotStep> = parseStepList(dataArray(body))

    fun parseAttachment(o: JsonObject): BotAttachment? {
        val path = o.text("path")?.takeIf { it.isNotEmpty() } ?: return null
        return BotAttachment(
            name = o.text("name")?.takeIf { it.isNotEmpty() } ?: path.substringAfterLast('/'),
            path = path,
            mime = o.text("mime"),
            size = o["size"].asLongLenient(),
            via = o.text("via"),
        )
    }

    /** 메시지의 `attachments`(JSON 문자열) 또는 null/빈 문자열/깨진 값 → 목록. */
    fun parseAttachments(json: String?): List<BotAttachment> {
        if (json.isNullOrBlank()) return emptyList()
        return parseJsonOrNull(json).asArray()?.mapNotNull { it.asObject()?.let(::parseAttachment) } ?: emptyList()
    }

    /** `POST /api/channels/{id}/attachments` 응답. path 가 없으면 null. */
    fun parseUpload(body: String): BotAttachment? = parseJsonOrNull(body).asObject()?.let(::parseAttachment)

    // ---- bot admin ----

    fun parseDiscoverResult(body: String): DiscoverResult {
        val o = parseJsonOrNull(body).asObject() ?: return DiscoverResult()
        return DiscoverResult(
            source = o.text("source"),
            found = o.int("found") ?: 0,
            created = o.int("created") ?: 0,
            updated = o.int("updated") ?: 0,
            offline = o.int("offline") ?: 0,
            archived = o.int("archived") ?: 0,
            skipped = o.array("skipped")?.mapNotNull { it.asText() } ?: emptyList(),
        )
    }

    fun parseHealthSummary(body: String): BotHealthSummary {
        val o = parseJsonOrNull(body).asObject()
        return BotHealthSummary(o?.int("online") ?: 0, o?.int("total") ?: 0)
    }

    // ---- workspaces ----

    fun parseWorkspace(o: JsonObject): Workspace? {
        val id = o.int("id") ?: return null
        val path = o.text("path") ?: return null
        return Workspace(
            id = id,
            kind = o.text("kind") ?: "folder",
            label = o.text("label")?.takeIf { it.isNotEmpty() } ?: path.substringAfterLast('/'),
            path = path,
            repoUrl = o.text("repo_url"),
            defaultBranch = o.text("default_branch"),
            createdAt = o.text("created_at"),
        )
    }

    fun parseWorkspaces(body: String): List<Workspace> =
        dataArray(body).mapNotNull { it.asObject()?.let(::parseWorkspace) }

    fun parseWorkspaceObject(body: String): Workspace? = parseJsonOrNull(body).asObject()?.let(::parseWorkspace)

    fun parseBrowse(body: String): BrowseResult? {
        val o = parseJsonOrNull(body).asObject() ?: return null
        val path = o.text("path") ?: return null
        return BrowseResult(
            path = path,
            parent = o.text("parent"),
            entries = o.array("entries")?.mapNotNull { el ->
                val e = el.asObject() ?: return@mapNotNull null
                val p = e.text("path") ?: return@mapNotNull null
                BrowseEntry(e.text("name") ?: p.substringAfterLast('/'), p)
            } ?: emptyList(),
        )
    }

    /** git 저장소가 아니면 서버가 `{"repo": false}` 만 준다. */
    fun parseWorkspaceStatus(body: String): WorkspaceStatus {
        val o = parseJsonOrNull(body).asObject() ?: return WorkspaceStatus(isRepo = false)
        return WorkspaceStatus(
            isRepo = o.bool("repo") ?: false,
            branch = o.text("branch"),
            dirty = o.bool("dirty") ?: false,
            files = o.array("files")?.mapNotNull { it.asText() } ?: emptyList(),
        )
    }

    /** `POST /api/workspaces/{id}/commit` → `{"ok":true,"sha":"…"}`. */
    fun parseCommitSha(body: String): String? = parseJsonOrNull(body).asObject()?.text("sha")

    fun parsePushRequest(body: String): PushRequestResult {
        val o = parseJsonOrNull(body).asObject()
        return PushRequestResult(o?.int("approval_id"), o?.text("status"), o?.text("note"))
    }

    /** `{"ok":true,"note":"…"}` 류 응답의 note. */
    fun parseNote(body: String): String? = parseJsonOrNull(body).asObject()?.text("note")
}
