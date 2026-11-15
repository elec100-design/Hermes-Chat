package com.hermes.chatandroid.notify

import com.hermes.chatandroid.data.BotApproval
import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.net.BotApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 알림 기준점 저장소. Android 구현은 SharedPreferences, 테스트는 메모리. */
interface NotifyStore {
    var initialized: Boolean
    /** 채널별 마지막으로 본(또는 알린) 봇 메시지 id. */
    fun lastSeen(channelId: Int): Int
    fun setLastSeen(channelId: Int, messageId: Int)
    /** 채널별 마지막으로 확인한 미리보기 문구 — 바뀐 채널만 메시지를 읽는다. */
    fun preview(channelId: Int): String?
    fun setPreview(channelId: Int, preview: String)
    var notifiedApprovals: Set<Int>
}

class MemoryNotifyStore : NotifyStore {
    override var initialized = false
    private val seen = HashMap<Int, Int>()
    private val previews = HashMap<Int, String>()
    override fun lastSeen(channelId: Int) = seen[channelId] ?: 0
    override fun setLastSeen(channelId: Int, messageId: Int) { seen[channelId] = messageId }
    override fun preview(channelId: Int) = previews[channelId]
    override fun setPreview(channelId: Int, preview: String) { previews[channelId] = preview }
    override var notifiedApprovals: Set<Int> = emptySet()
}

sealed interface NotifyEvent {
    /** [count] = 이번에 새로 온 봇 답 수, [message] = 그중 마지막. */
    data class Reply(val channelId: Int, val title: String, val message: BotMessage, val count: Int) : NotifyEvent
    data class Approval(val approval: BotApproval, val botName: String?) : NotifyEvent
}

/** 봇 답이 "끝났다"고 볼 상태. 진행 중(queued/running)은 다음 확인 때. */
private fun BotMessage.isFinishedBotReply() =
    role == "bot" && status != "queued" && status != "running" && (body.isNotBlank() || error != null)

/**
 * 봇 서버를 폴링해 새 봇 답·새 승인 요청을 찾는다. 앱(30초)과 WorkManager(15분)가 같은 인스턴스를 공유하고
 * [mutex] 로 직렬화해 중복 알림을 막는다. 첫 실행은 현재 상태를 기준점으로만 기록한다(과거 메시지 폭탄 방지).
 */
class BotNotifier(private val store: NotifyStore) {
    private val mutex = Mutex()

    /** 앱이 보고 있는 채널. 이 채널의 새 답은 알리지 않고 읽음 처리한다. */
    @Volatile var activeChannelId: Int? = null

    /** 채팅 화면이 메시지를 받으면 호출 — 이미 본 답은 나중에 알리지 않는다. */
    fun markSeen(channelId: Int, messages: List<BotMessage>) {
        val maxId = messages.filter { it.isFinishedBotReply() }.maxOfOrNull { it.id } ?: return
        if (maxId > store.lastSeen(channelId)) store.setLastSeen(channelId, maxId)
    }

    /** @return 알릴 이벤트와 현재 대기 중 승인 수. */
    suspend fun check(api: BotApi): Pair<List<NotifyEvent>, Int> = mutex.withLock {
        val bots = api.fetchBots(activeOnly = false)
        val channels = api.fetchChannels()
        val approvals = api.fetchApprovals()
        val events = mutableListOf<NotifyEvent>()
        val first = !store.initialized

        // 채널 후보: 봇 DM + 일반 채널
        val targets: List<Triple<Int, String, String>> =
            bots.filter { it.dmChannelId != null && !it.isArchived }.map { Triple(it.dmChannelId!!, it.displayName, it.preview.orEmpty()) } +
                channels.filter { it.status != "archived" }.map { Triple(it.id, "#${it.title}", it.preview.orEmpty()) }

        for ((chId, title, preview) in targets) {
            if (store.preview(chId) == preview && !first) continue
            val msgs = runCatching { api.fetchMessages(chId) }.getOrNull() ?: continue
            store.setPreview(chId, preview)
            val done = msgs.filter { it.isFinishedBotReply() }
            val maxId = done.maxOfOrNull { it.id } ?: continue
            val last = store.lastSeen(chId)
            if (maxId <= last) continue
            store.setLastSeen(chId, maxId)
            if (first || chId == activeChannelId) continue
            val fresh = done.filter { it.id > last }
            events += NotifyEvent.Reply(chId, title, fresh.maxBy { it.id }, fresh.size)
        }

        val pending = approvals.map { it.id }.toSet()
        if (!first) {
            approvals.filter { it.id !in store.notifiedApprovals }.forEach { a ->
                events += NotifyEvent.Approval(a, bots.firstOrNull { it.id == a.botId }?.displayName)
            }
        }
        // 처리된 승인은 집합에서 빼서 무한히 커지지 않게
        store.notifiedApprovals = pending
        store.initialized = true
        events to approvals.size
    }

    companion object {
        /** 알림 본문용: 마크다운 기호를 덜어내고 한 줄로. */
        fun plain(text: String, max: Int = 300): String =
            text.replace(Regex("```[\\s\\S]*?```"), "[코드]")
                .replace(Regex("[*_`#>|]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .let { if (it.length > max) it.take(max) + "…" else it }
    }
}
