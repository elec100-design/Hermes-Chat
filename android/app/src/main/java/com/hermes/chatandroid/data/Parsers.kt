package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.array
import com.hermes.chatandroid.net.asArray
import com.hermes.chatandroid.net.asBoolLenient
import com.hermes.chatandroid.net.asDoubleLenient
import com.hermes.chatandroid.net.asIntLenient
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

    fun parseBots(body: String): List<Bot> = dataArray(body).mapNotNull { el ->
        val o = el.asObject() ?: return@mapNotNull null
        val id = o.int("id") ?: return@mapNotNull null
        Bot(
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
        )
    }

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
        )
    }

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
        )
    }
}
