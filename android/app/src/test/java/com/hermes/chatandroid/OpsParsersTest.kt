package com.hermes.chatandroid

import com.hermes.chatandroid.data.CronForm
import com.hermes.chatandroid.data.CronText
import com.hermes.chatandroid.data.KanbanStatus
import com.hermes.chatandroid.data.OpsParsers
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsParsersTest {
    @Test
    fun parsesRealCronJobWithScheduleObject() {
        // 실제 hermes-agent jobs.json 형태(schedule 객체, *_run_at, deliver)
        val body = """{"profile":"wiki","jobs":[{"id":"d59e075661a0","name":"wiki:daily-briefing","prompt":"브리핑","skills":["llm-wiki","arxiv"],
            "schedule":{"kind":"cron","expr":"0 7 * * *","display":"0 7 * * *"},"enabled":true,"state":"scheduled",
            "next_run_at":"2026-11-16T07:00:00+09:00","last_run_at":"2026-11-15T07:01:02.576705+09:00","last_status":"ok","last_error":null,"deliver":"telegram"}]}"""
        val j = OpsParsers.parseCronJobs(body, "wiki").single()
        assertEquals("0 7 * * *", j.schedule)
        assertEquals("telegram", j.deliver)
        assertEquals(listOf("llm-wiki", "arxiv"), j.skills)
        assertEquals("2026-11-16T07:00:00+09:00", j.nextRunAt)
        assertFalse(j.paused)
        assertFalse(j.failed)
    }

    @Test
    fun parsesLegacyCronJobAndPausedState() {
        val body = """{"jobs":[{"id":3,"prompt":"첫 줄\n둘째","schedule":"every 2h","enabled":false,"deliver_to":"origin","last_status":"error","last_error":"boom"}]}"""
        val j = OpsParsers.parseCronJobs(body, "default").single()
        assertEquals("3", j.id)
        assertEquals("첫 줄", j.title)
        assertEquals("origin", j.deliver)
        assertTrue(j.paused)
        assertTrue(j.failed)
    }

    @Test
    fun humanizesCommonSchedules() {
        assertEquals("매일 07:00", CronText.humanize("0 7 * * *"))
        assertEquals("평일 09:30", CronText.humanize("30 9 * * 1-5"))
        assertEquals("매주 월 08:00", CronText.humanize("0 8 * * 1"))
        assertEquals("매주 월·금 08:00", CronText.humanize("0 8 * * 1,5"))
        assertEquals("매월 1일 09:00", CronText.humanize("0 9 1 * *"))
        assertEquals("30분마다", CronText.humanize("*/30 * * * *"))
        assertEquals("매시 15분", CronText.humanize("15 * * * *"))
        assertEquals("2시간마다", CronText.humanize("every 2h"))
        assertEquals("30분마다", CronText.humanize("30m"))
        assertNull(CronText.humanize("0 9 * 1 *"))
    }

    @Test
    fun relativeTime() {
        val now = Instant.parse("2026-11-15T00:00:00Z")
        assertEquals("3시간 후", CronText.relative("2026-11-15T03:00:00Z", now))
        assertEquals("2일 전", CronText.relative("2026-11-13T00:00:00+00:00", now))
    }

    @Test
    fun parsesKanbanBoardsBoardAndDetail() {
        val boards = OpsParsers.parseBoards("""{"data":[{"board":"default","name":"Default","counts":{"done":13,"archived":13,"ready":2}}]}""")
        assertEquals(15, boards.single().activeCount)
        val board = OpsParsers.parseBoard("""{"name":"S","board":"s","tasks":[{"id":"t_1","title":"A","status":"blocked","assignee":"","error":"x","failures":2},{"id":"t_2","title":"","status":"weird"}]}""")
        assertEquals(KanbanStatus.BLOCKED, board.tasks[0].status)
        assertEquals(2, board.tasks[0].failures)
        assertEquals(KanbanStatus.TRIAGE, board.tasks[1].status)
        assertEquals("(제목 없음)", board.tasks[1].title)
        val d = OpsParsers.parseTaskDetail("""{"task":{"id":"t_1","title":"A","status":"done","result":"ok"},"comments":[{"author":"me","body":"hi"}],"events":[{"kind":"completed","payload":"{}"}]}""")!!
        assertEquals("ok", d.task.result)
        assertEquals(1, d.comments.size)
        assertEquals("completed", d.events.single().kind)
    }

    @Test
    fun cronFormSkillList() {
        assertEquals(listOf("a", "b", "c"), CronForm(skills = "a, b,c ").skillList)
    }
}
