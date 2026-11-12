package com.hermes.chatandroid

import com.hermes.chatandroid.data.DemoBotApi
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
}
