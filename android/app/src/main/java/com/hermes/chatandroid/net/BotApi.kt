package com.hermes.chatandroid.net

import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotApproval
import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.BotPostResult
import com.hermes.chatandroid.data.BotStreamEvent
import com.hermes.chatandroid.data.Parsers
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/** Hermes-bot(:8900) 클라이언트. [token] 이 비어 있으면 인증 헤더 없음. */
class BotApi(
    private val baseUrl: String,
    private val token: String,
    client: OkHttpClient = defaultHttpClient(),
) {
    private val http = client
    /** 승인은 최대 200초 걸릴 수 있다. */
    private val longHttp = client.newBuilder().readTimeout(210, TimeUnit.SECONDS).build()
    private val streamHttp = client.newBuilder().readTimeout(600, TimeUnit.SECONDS).build()

    private fun req(path: String) = Request.Builder().url(joinUrl(baseUrl, path)).bearer(token)

    suspend fun health(): Boolean = try {
        http.fetchText(Request.Builder().url(joinUrl(baseUrl, "health")).build()); true
    } catch (e: Exception) { false }

    suspend fun fetchChannels(): List<BotChannel> =
        Parsers.parseBotChannels(http.fetchText(req("api/channels").build()))

    suspend fun fetchBots(activeOnly: Boolean = true): List<Bot> =
        Parsers.parseBots(http.fetchText(req(if (activeOnly) "api/bots?status=active" else "api/bots").build()))

    suspend fun fetchMessages(channelId: Int): List<BotMessage> =
        Parsers.parseBotMessages(http.fetchText(req("api/channels/$channelId/messages").build()))

    suspend fun postMessage(channelId: Int, body: String): BotPostResult {
        val json = buildJsonObject { put("body", body) }
        val text = http.fetchText(req("api/channels/$channelId/messages").post(jsonBody(json.toString())).build())
        return Parsers.parseBotPostResult(text)
    }

    suspend fun createChannel(title: String, topic: String? = null, botIds: List<Int> = emptyList()): BotChannel {
        val json = buildJsonObject {
            put("title", title)
            if (!topic.isNullOrBlank()) put("topic", topic)
            if (botIds.isNotEmpty()) put("bot_ids", buildJsonArray { botIds.forEach { add(it) } })
        }
        val text = http.fetchText(req("api/channels").post(jsonBody(json.toString())).build())
        return parseJsonOrNull(text).asObject()?.let(Parsers::parseBotChannel)
            ?: throw ApiException(0, "채널 응답을 해석할 수 없습니다")
    }

    suspend fun fetchApprovals(status: String = "pending"): List<BotApproval> =
        Parsers.parseBotApprovals(http.fetchText(req("api/approvals?status=$status").build()))

    suspend fun approve(id: Int, note: String? = null) = decide(id, "approve", note)
    suspend fun reject(id: Int, note: String? = null) = decide(id, "reject", note)

    private suspend fun decide(id: Int, action: String, note: String?) {
        val json = buildJsonObject { if (!note.isNullOrBlank()) put("note", note) }
        longHttp.fetchText(req("api/approvals/$id/$action").post(jsonBody(json.toString())).build())
    }

    /**
     * 잡 SSE. 구독할 때마다 처음부터 재생되므로 수집 측은 구독 시 버퍼를 리셋해야 한다.
     * `end` 이벤트 이후 흐름이 완료된다. 수집 취소 = 연결 종료.
     */
    fun jobStream(jobId: Int): Flow<BotStreamEvent> =
        streamHttp.sseFrames(req("jobs/$jobId/stream").header("Accept", "text/event-stream").build())
            .mapNotNull { mapFrame(it) }

    companion object {
        fun mapFrame(f: SseFrame): BotStreamEvent? {
            val obj = parseJsonOrNull(f.data).asObject()
            return when (f.event) {
                "delta" -> (obj?.text("text") ?: obj?.text("delta"))?.let { BotStreamEvent.Delta(it) }
                "end" -> BotStreamEvent.End(obj?.text("status") ?: "done", obj?.text("error"))
                "error" -> BotStreamEvent.Error(obj?.text("message") ?: f.data.ifBlank { "오류" })
                else -> null // steps 등 무시
            }
        }
    }
}
