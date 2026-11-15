package com.hermes.chatandroid

import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotApproval
import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.net.BotApi
import com.hermes.chatandroid.notify.BotNotifier
import com.hermes.chatandroid.notify.MemoryNotifyStore
import com.hermes.chatandroid.notify.NotifyEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBotApi : BotApi("", "") {
    var bots = listOf(Bot(id = 1, slug = "opus", name = "opus", dmChannelId = 10, preview = "hi"))
    var channels = listOf(BotChannel(id = 20, title = "dev", preview = "a"))
    var approvals = listOf<BotApproval>()
    val messages = mutableMapOf(
        10 to mutableListOf(BotMessage(id = 1, role = "user", body = "hi"), BotMessage(id = 2, role = "bot", body = "old", status = "done")),
        20 to mutableListOf(BotMessage(id = 5, role = "bot", body = "a", status = "done")),
    )
    var fetchCount = 0
    override suspend fun fetchBots(activeOnly: Boolean) = bots
    override suspend fun fetchChannels() = channels
    override suspend fun fetchApprovals(status: String) = approvals
    override suspend fun fetchMessages(channelId: Int): List<BotMessage> { fetchCount++; return messages[channelId].orEmpty() }
}

class BotNotifierTest {
    @Test
    fun firstRunOnlyRecordsBaseline() = runTest {
        val api = FakeBotApi().apply { approvals = listOf(BotApproval(id = 7, title = "rm -rf")) }
        val n = BotNotifier(MemoryNotifyStore())
        val (events, pending) = n.check(api)
        assertTrue(events.isEmpty())
        assertEquals(1, pending)
    }

    @Test
    fun notifiesNewFinishedReplyOnceAndSkipsUnchangedChannels() = runTest {
        val api = FakeBotApi()
        val n = BotNotifier(MemoryNotifyStore())
        n.check(api)
        // 진행 중 답은 아직 알리지 않음
        api.messages[10]!! += BotMessage(id = 3, role = "bot", body = "", status = "running")
        api.bots = listOf(api.bots[0].copy(preview = "q2"))
        assertTrue(n.check(api).first.isEmpty())
        // 완료되면 1회 알림
        api.messages[10]!![2] = BotMessage(id = 3, role = "bot", body = "**새** 답", status = "done")
        api.bots = listOf(api.bots[0].copy(preview = "새 답"))
        val fetchesBefore = api.fetchCount
        val ev = n.check(api).first
        assertEquals(1, ev.size)
        val r = ev.single() as NotifyEvent.Reply
        assertEquals(10, r.channelId)
        assertEquals(3, r.message.id)
        assertEquals(1, api.fetchCount - fetchesBefore) // 미리보기가 그대로인 채널(20)은 안 읽음
        assertTrue(n.check(api).first.isEmpty())
    }

    @Test
    fun activeChannelAndMarkSeenSuppressNotification() = runTest {
        val api = FakeBotApi()
        val n = BotNotifier(MemoryNotifyStore())
        n.check(api)
        n.activeChannelId = 20
        api.messages[20]!! += BotMessage(id = 6, role = "bot", body = "b", status = "done")
        api.channels = listOf(api.channels[0].copy(preview = "b"))
        assertTrue(n.check(api).first.isEmpty())
        n.activeChannelId = null
        api.messages[10]!! += BotMessage(id = 8, role = "bot", body = "c", status = "done")
        n.markSeen(10, api.messages[10]!!)
        api.bots = listOf(api.bots[0].copy(preview = "c"))
        assertTrue(n.check(api).first.isEmpty())
    }

    @Test
    fun newApprovalNotifiedOnce() = runTest {
        val api = FakeBotApi()
        val n = BotNotifier(MemoryNotifyStore())
        n.check(api)
        api.approvals = listOf(BotApproval(id = 9, title = "push", botId = 1))
        val ev = n.check(api).first.single() as NotifyEvent.Approval
        assertEquals("opus", ev.botName)
        assertTrue(n.check(api).first.isEmpty())
    }

    @Test
    fun plainStripsMarkdown() {
        assertEquals("굵게 [코드] 끝", BotNotifier.plain("**굵게**\n```kt\nx\n```\n끝"))
    }
}
