package com.hermes.chatandroid.net

import com.hermes.chatandroid.data.ChatMessage
import com.hermes.chatandroid.data.Parsers
import com.hermes.chatandroid.data.Session
import com.hermes.chatandroid.data.SessionPage
import com.hermes.chatandroid.data.StreamEvent
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/** 프로필별 hermes-agent 게이트웨이 REST + SSE 클라이언트. [baseUrl] = "http://host:port". */
open class HermesApi(
    private val baseUrl: String,
    private val apiKey: String,
    client: OkHttpClient = defaultHttpClient(),
) {
    private val http = client
    /** 스트림은 도구 실행 동안 오래 조용할 수 있어 읽기 타임아웃 5분. */
    private val streamHttp = client.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()

    private fun req(path: String): Request.Builder =
        Request.Builder().url(joinUrl(baseUrl, path)).bearer(apiKey)

    private fun seg(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    open suspend fun health(): Boolean = try {
        http.fetchText(Request.Builder().url(joinUrl(baseUrl, "health")).build()); true
    } catch (e: Exception) { false }

    open suspend fun fetchModelIds(): List<String> =
        Parsers.parseModelIds(http.fetchText(req("v1/models").build()))

    open suspend fun fetchSessions(limit: Int = 50, offset: Int = 0): SessionPage =
        Parsers.parseSessionList(http.fetchText(req("api/sessions?limit=$limit&offset=$offset").build()))

    open suspend fun createSession(title: String? = null): Session {
        val body = buildJsonObject { if (!title.isNullOrBlank()) put("title", title) }
        val text = http.fetchText(req("api/sessions").post(jsonBody(body.toString())).build())
        return Parsers.parseSessionResponse(text)
            ?: throw ApiException(0, "세션 응답을 해석할 수 없습니다: ${text.take(300)}")
    }

    open suspend fun fetchMessages(sessionId: String): List<ChatMessage> =
        Parsers.parseMessages(http.fetchText(req("api/sessions/${seg(sessionId)}/messages").build()))

    open suspend fun renameSession(sessionId: String, title: String) {
        val body = buildJsonObject { put("title", title) }
        http.fetchText(req("api/sessions/${seg(sessionId)}").patch(jsonBody(body.toString())).build())
    }

    open suspend fun deleteSession(sessionId: String) {
        http.fetchText(req("api/sessions/${seg(sessionId)}").delete().build())
    }

    /**
     * 채팅 스트림. 수집 취소 = 연결 종료. [attachments] 는 Bridge 업로드로 받은 서버 경로 목록이며
     * iOS 와 동일하게 "[첨부: 경로]" 줄로 메시지 앞에 붙는다.
     */
    open fun streamChat(
        sessionId: String,
        message: String,
        attachments: List<String> = emptyList(),
    ): Flow<StreamEvent> {
        val text = if (attachments.isEmpty()) message else {
            val header = attachments.joinToString("\n") { "[첨부: $it]" }
            if (message.isEmpty()) header else "$header\n\n$message"
        }
        val body = buildJsonObject { put("message", text) }
        val request = req("api/sessions/${seg(sessionId)}/chat/stream")
            .header("Accept", "text/event-stream")
            .post(jsonBody(body.toString()))
            .build()
        return streamHttp.sseFrames(request).mapNotNull { mapFrame(it) }
    }

    /**
     * 스트림이 빈 채 끝났을 때(게이트웨이가 SSE 로 안 보내는 버그 대응) 세션 기록을 2초 간격으로 폴링해
     * [expectedUserCount] 번째 user 메시지 뒤의 보이는 assistant 답을 회수. 시간 초과 시 null.
     */
    open suspend fun pollForMissedReply(sessionId: String, expectedUserCount: Int, deadlineMs: Long): String? {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            currentCoroutineContext().ensureActive()
            val msgs = try { fetchMessages(sessionId) } catch (e: Exception) { null }
            if (msgs != null) missedReply(msgs, expectedUserCount)?.let { return it }
            delay(2000)
        }
        return null
    }

    companion object {
        /** SSE 프레임 -> 이벤트. 이름 있는 이벤트 우선, OpenAI chunk/[DONE] 폴백. 무시할 프레임은 null. */
        fun mapFrame(f: SseFrame): StreamEvent? {
            val name = f.event
            val data = f.data
            if (data.trim() == "[DONE]") return StreamEvent.Done
            val obj = parseJsonOrNull(data).asObject()
            return when {
                name == "error" -> StreamEvent.Error(obj?.text("message") ?: obj?.text("error") ?: data.ifBlank { "알 수 없는 오류" })
                name == "assistant.delta" -> (obj?.text("delta") ?: obj?.text("text"))?.let { StreamEvent.Delta(it) }
                name == "assistant.completed" -> StreamEvent.Completed(obj?.text("content") ?: "")
                name == "done" -> StreamEvent.Done
                name != null && name.startsWith("tool") ->
                    StreamEvent.Tool(obj?.text("name") ?: obj?.text("tool") ?: obj?.text("tool_name"))
                name != null && name != "message" -> null // run.started, message.started, run.completed …
                obj == null -> null
                else -> {
                    // OpenAI chunk 폴백
                    val err = obj["error"]
                    if (err != null && obj["choices"] == null) {
                        return StreamEvent.Error(err.asObject()?.text("message") ?: err.asText() ?: "오류")
                    }
                    val delta = obj.array("choices")?.firstOrNull().asObject()?.obj("delta")?.text("content")
                    if (delta.isNullOrEmpty()) null else StreamEvent.Delta(delta)
                }
            }
        }

        /** expectedUserCount 번째 user 메시지 뒤에 오는, think 제거 후 비지 않은 마지막 assistant 본문. */
        fun missedReply(messages: List<ChatMessage>, expectedUserCount: Int): String? {
            var users = 0
            var anchor = -1
            messages.forEachIndexed { i, m ->
                if (m.isUser) {
                    users++
                    if (users == expectedUserCount) anchor = i
                }
            }
            if (anchor < 0) return null
            return messages.drop(anchor + 1)
                .filter { it.role == "assistant" }
                .map { it.content }
                .lastOrNull { ThinkFilter.strip(it).isNotBlank() }
        }
    }
}
