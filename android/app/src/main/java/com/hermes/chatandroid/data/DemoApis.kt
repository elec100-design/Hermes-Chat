package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.BotApi
import com.hermes.chatandroid.net.BridgeApi
import com.hermes.chatandroid.net.HermesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 공백 단위로 쪼개되 공백을 보존해 이어 붙이면 원문이 되는 조각들. */
internal fun wordChunks(text: String): List<String> =
    Regex("\\S+\\s*|\\s+").findAll(text).map { it.value }.toList()

/** 데모 모드 게이트웨이 페이크. 네트워크 호출 없음. 스트림은 단어 단위 지연 방출이며 수집 취소 시 중단된다. */
class DemoHermesApi(private val wordDelayMs: Long = 40L) : HermesApi("", "") {
    private val sessions = DemoData.sessions().toMutableList()
    private val messages = DemoData.sessions().associate { it.id to DemoData.messages(it.id).toMutableList() }.toMutableMap()
    private var seq = 0
    private var replyIndex = 0

    override suspend fun health() = true
    override suspend fun fetchModelIds() = listOf("demo-model")

    override suspend fun fetchSessions(limit: Int, offset: Int): SessionPage {
        val all = sessions.sortedByDescending { it.updatedAt }
        return SessionPage(all.drop(offset).take(limit), hasMore = offset + limit < all.size)
    }

    override suspend fun createSession(title: String?): Session {
        val s = Session("demo-new-${++seq}", title, null, System.currentTimeMillis() / 1000.0, "default")
        sessions.add(s)
        messages[s.id] = mutableListOf()
        return s
    }

    override suspend fun fetchMessages(sessionId: String): List<ChatMessage> = messages[sessionId].orEmpty().toList()

    override suspend fun renameSession(sessionId: String, title: String) {
        val i = sessions.indexOfFirst { it.id == sessionId }
        if (i >= 0) sessions[i] = sessions[i].copy(title = title)
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.removeAll { it.id == sessionId }
        messages.remove(sessionId)
    }

    override fun streamChat(sessionId: String, message: String, attachments: List<String>): Flow<StreamEvent> = flow {
        val now = System.currentTimeMillis() / 1000.0
        val list = messages.getOrPut(sessionId) { mutableListOf() }
        list.add(ChatMessage("u-${++seq}", "user", message, now))
        val reply = DemoData.scriptedResponses[replyIndex++ % DemoData.scriptedResponses.size]
        for (chunk in wordChunks(reply)) {
            delay(wordDelayMs)
            emit(StreamEvent.Delta(chunk))
        }
        // 취소되면 여기까지 오지 않으므로 중단된 답변은 기록에 남지 않는다.
        list.add(ChatMessage("a-${++seq}", "assistant", reply, System.currentTimeMillis() / 1000.0))
        emit(StreamEvent.Completed(reply))
        emit(StreamEvent.Done)
    }

    override suspend fun pollForMissedReply(sessionId: String, expectedUserCount: Int, deadlineMs: Long): String? = null
}

class DemoBridgeApi : BridgeApi("", "") {
    override suspend fun fetchProfiles() = DemoData.profiles.map { BridgeProfile(it.name, it.port) }
    override suspend fun upload(bytes: ByteArray, filename: String, profile: String) = "/demo/uploads/$filename"
}

/** 데모 모드 봇 서버 페이크: 채널/메시지/승인을 메모리에 유지. */
class DemoBotApi(private val wordDelayMs: Long = 40L) : BotApi("", "") {
    private val channels = DemoData.channels.toMutableList()
    private val messages = (DemoData.channels.map { it.id } + 0).associateWith { DemoData.botMessages(it).toMutableList() }.toMutableMap()
    private val approvals = DemoData.approvals().toMutableList()
    private val jobs = HashMap<Int, Pair<Int, String>>() // jobId -> (messageId, 최종 본문)
    private var nextId = 5000
    private var replyIndex = 0

    override suspend fun health() = true
    override suspend fun fetchChannels(): List<BotChannel> = channels.toList()
    override suspend fun fetchBots(activeOnly: Boolean): List<Bot> = DemoData.bots
    override suspend fun fetchMessages(channelId: Int): List<BotMessage> = messages[channelId].orEmpty().toList()

    override suspend fun postMessage(channelId: Int, body: String): BotPostResult {
        val list = messages.getOrPut(channelId) { mutableListOf() }
        val userId = nextId++
        list.add(BotMessage(userId, channelId, list.size + 1, "user", "나", body, "done"))
        val bot = channels.firstOrNull { it.id == channelId }?.members?.firstOrNull()
            ?: return BotPostResult(messageId = userId, error = "채널에 봇이 없습니다")
        val replyId = nextId++
        val jobId = nextId++
        val text = DemoData.botReplies[replyIndex++ % DemoData.botReplies.size]
        list.add(
            BotMessage(
                replyId, channelId, list.size + 1, "bot", bot.slug, "", "running",
                jobId = jobId, authorBotId = bot.id, displayName = bot.displayName, avatar = bot.avatar, tone = bot.tone,
            ),
        )
        jobs[jobId] = replyId to text
        return BotPostResult(messageId = userId, replyMessageId = replyId, jobId = jobId, bot = bot.slug)
    }

    override suspend fun createChannel(title: String, topic: String?, botIds: List<Int>): BotChannel {
        val ch = BotChannel(
            nextId++, null, title, topic, false, "active", null,
            DemoData.bots.filter { it.id in botIds }.map { BotMember(it.id, it.slug, it.name, it.avatar, it.health, it.tone) },
        )
        channels.add(ch)
        messages[ch.id] = mutableListOf()
        return ch
    }

    override suspend fun fetchApprovals(status: String): List<BotApproval> = approvals.filter { it.status == status }
    override suspend fun approve(id: Int, note: String?) = decide(id, "approved")
    override suspend fun reject(id: Int, note: String?) = decide(id, "rejected")

    private fun decide(id: Int, status: String) {
        val i = approvals.indexOfFirst { it.id == id }
        if (i >= 0) approvals[i] = approvals[i].copy(status = status)
    }

    /** 구독마다 처음부터 재생. 끝에 메시지를 done 으로 확정한 뒤 End 를 보낸다. */
    override fun jobStream(jobId: Int): Flow<BotStreamEvent> = flow {
        val (messageId, text) = jobs[jobId] ?: run { emit(BotStreamEvent.End("failed", "알 수 없는 작업")); return@flow }
        for (chunk in wordChunks(text)) {
            delay(wordDelayMs)
            emit(BotStreamEvent.Delta(chunk))
        }
        for (list in messages.values) {
            val i = list.indexOfFirst { it.id == messageId }
            if (i >= 0) list[i] = list[i].copy(body = text, status = "done")
        }
        emit(BotStreamEvent.End("done", null))
    }
}
