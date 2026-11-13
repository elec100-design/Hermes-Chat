package com.hermes.chatandroid

import com.hermes.chatandroid.data.BotPatch
import com.hermes.chatandroid.data.ChannelPatch
import com.hermes.chatandroid.data.DemoBotApi
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.data.DemoData
import com.hermes.chatandroid.data.DemoHermesApi
import com.hermes.chatandroid.data.Settings
import com.hermes.chatandroid.data.StreamEvent
import com.hermes.chatandroid.data.BotStreamEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoModeTest {
    @Test
    fun demoSettingsExposeProfilesAndBots() {
        val s = Settings(demoMode = true)
        assertEquals(DemoData.profiles, s.visibleProfiles)
        assertTrue(s.botConfigured)
        assertTrue(!Settings().botConfigured)
    }

    @Test
    fun hermesFakeReturnsSessionsAndMessages() = runTest {
        val api = DemoHermesApi(0)
        val page = api.fetchSessions()
        assertTrue(page.sessions.isNotEmpty())
        assertTrue(api.fetchMessages(page.sessions.first().id).isNotEmpty())
        val s = api.createSession()
        assertTrue(api.fetchMessages(s.id).isEmpty())
    }

    @Test
    fun streamEmitsDeltasThenCompletesAndStores() = runTest {
        val api = DemoHermesApi(0)
        val events = api.streamChat("demo-session-001", "안녕").toList()
        val deltas = events.filterIsInstance<StreamEvent.Delta>()
        assertTrue(deltas.size > 3)
        assertEquals(StreamEvent.Done, events.last())
        assertEquals(deltas.joinToString("") { it.text }, (events.first { it is StreamEvent.Completed } as StreamEvent.Completed).content)
        assertEquals("assistant", api.fetchMessages("demo-session-001").last().role)
    }

    @Test
    fun cancelledStreamDoesNotStoreReply() = runTest {
        val api = DemoHermesApi(0)
        val before = api.fetchMessages("demo-session-002").size
        api.streamChat("demo-session-002", "질문").take(2).toList() // 수집 조기 종료 = 취소
        assertEquals(before + 1, api.fetchMessages("demo-session-002").size) // user 만 추가
    }

    @Test
    fun botFakeRepliesAndApprovals() = runTest {
        val api = DemoBotApi(0)
        assertTrue(api.fetchBots().isNotEmpty())
        assertTrue(api.fetchChannels().isNotEmpty())
        val r = api.postMessage(101, "@planner 안녕")
        val inflight = api.fetchMessages(101).last()
        assertTrue(inflight.isInFlight)
        val ev = api.jobStream(r.jobId!!).toList()
        assertTrue(ev.first() is BotStreamEvent.Delta)
        assertTrue(ev.last() is BotStreamEvent.End)
        val done = api.fetchMessages(101).last()
        assertEquals("done", done.status)
        assertTrue(done.body.isNotBlank())

        val pending = api.fetchApprovals()
        assertEquals(1, pending.size)
        api.approve(pending[0].id)
        assertTrue(api.fetchApprovals().isEmpty())
    }

    @Test
    fun botFakeAdminChannelAndWorkspaceFlows() = runTest {
        val api = DemoBotApi(0)
        // DM 은 채널 목록에 없다
        assertTrue(api.fetchChannels().none { it.isDm })
        // 봇 수정/보관
        val b = api.updateBot(1, BotPatch(name = "새이름", readOnly = true))
        assertEquals("새이름", b.name); assertEquals(true, b.readOnly)
        api.updateBot(3, BotPatch(status = "archived"))
        assertEquals(2, api.fetchBots().size)
        assertEquals(3, api.fetchBots(false).size)
        assertEquals(2, api.refreshHealth().online)
        // 채널 멤버/설정/삭제
        api.addChannelMember(201, 3)
        assertEquals(3, api.fetchChannels().first { it.id == 201 }.members.size)
        api.removeChannelMember(201, 3)
        assertEquals("팀", api.updateChannel(201, ChannelPatch(title = "팀")).title)
        try { api.deleteChannel(101); org.junit.Assert.fail() } catch (e: ApiException) { assertEquals(400, e.code) }
        api.deleteChannel(201)
        assertTrue(api.fetchChannels().none { it.id == 201 })
        // 첨부
        val att = api.uploadAttachment(101, "a.txt", ByteArray(3), "text/plain")
        api.postMessage(101, "", listOf(att))
        assertEquals("a.txt", api.fetchMessages(101).first { it.attachments != null }.attachmentItems.single().name)
        assertTrue(api.fetchSteps(1002).isNotEmpty())
        // 워크스페이스 + push 는 승인 항목이 된다
        val before = api.fetchWorkspaces().size
        val w = api.cloneWorkspace("o/r")
        assertEquals(before + 1, api.fetchWorkspaces().size)
        assertTrue(api.fetchWorkspaceStatus(w.id).isRepo)
        assertEquals(1, api.fetchApprovals().size)
        api.requestWorkspacePush(w.id)
        assertEquals(2, api.fetchApprovals().size)
        api.removeWorkspace(w.id)
        assertEquals(before, api.fetchWorkspaces().size)
        assertTrue(api.browseWorkspaces().entries.isNotEmpty())
    }
}
