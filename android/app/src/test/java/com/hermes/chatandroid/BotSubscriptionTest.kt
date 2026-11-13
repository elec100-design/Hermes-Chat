package com.hermes.chatandroid

import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.BotStreamEvent
import com.hermes.chatandroid.ui.bots.BotJobFollower
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotSubscriptionTest {
    private fun msg(id: Int, status: String, job: Int?) =
        BotMessage(id = id, role = "bot", status = status, jobId = job, seq = id)

    @Test
    fun endWaits700msThenReloadsAndSubscribesChain() = runTest {
        val subscribed = mutableListOf<Int>()
        var reloads = 0
        val deltas = mutableMapOf<Int, String>()
        val resets = mutableListOf<Int>()
        lateinit var f: BotJobFollower
        f = BotJobFollower(
            scope = this,
            streamOf = { job ->
                subscribed += job
                flow {
                    emit(BotStreamEvent.Delta("a$job"))
                    emit(BotStreamEvent.End("done", null))
                }
            },
            reload = {
                reloads++
                // 첫 reload: 1 완료 + 새 in-flight 2 (봇→봇 연쇄), 이후: 모두 완료
                if (reloads == 1) listOf(msg(1, "done", 10), msg(2, "queued", 20)) else listOf(msg(1, "done", 10), msg(2, "done", 20))
            },
            onMessages = {},
            onReset = { resets += it },
            onDelta = { id, t -> deltas[id] = deltas[id].orEmpty() + t },
            onStreamError = { _, _ -> },
        )
        f.submit(listOf(msg(1, "running", 10)))
        f.follow(1, 10) // 중복 구독 방지
        runCurrent()
        assertEquals(listOf(10), subscribed)
        assertEquals(0, reloads)
        advanceTimeBy(699); runCurrent()
        assertEquals(0, reloads)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, reloads)
        assertEquals(listOf(10, 20), subscribed)
        advanceTimeBy(701); runCurrent()
        assertEquals(2, reloads)
        assertEquals(listOf(10, 20), subscribed)
        assertEquals("a10", deltas[1])
        assertEquals(listOf(1, 2), resets)
    }
}
