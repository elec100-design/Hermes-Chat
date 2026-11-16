package com.hermes.chatandroid

import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.data.BotMember
import com.hermes.chatandroid.data.DevParsers
import com.hermes.chatandroid.data.UsageText
import com.hermes.chatandroid.data.Workspace
import com.hermes.chatandroid.data.workChatsByWorkspace
import com.hermes.chatandroid.data.workspaceOf
import com.hermes.chatandroid.ui.bots.soloMention
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevParsersTest {
    // 실제 `paseo usage --json` 형태(아이콘은 Bridge 가 뺀다)
    private val usage = """{"data":[
        {"id":"codex:9d","sourceId":"codex","sourceLabel":"Codex","account":{"label":"me@example.com"},"fetchedAt":"2026-11-16T05:59:48.400Z",
         "report":{"status":"unavailable","problem":{"kind":"rejected","status":401,"refreshedBy":"codex"}},
         "lastSuccessful":{"fetchedAt":"2026-11-13T12:28:34.077Z","report":{"status":"available","planLabel":"free",
           "windows":[{"id":"2592000s","label":"30-day","shortLabel":"30d","summary":true,"usedPct":86,"remainingPct":14,"resetsAt":"2026-11-23T14:28:07.000Z","tone":"warning"}],
           "balances":[{"id":"credits","label":"Credits","remaining":0,"unit":"credits","tone":"danger"}],"details":[]}}},
        {"id":"claude:a5","sourceId":"claude","sourceLabel":"Claude","account":{"label":"me@example.com"},"fetchedAt":"2026-11-16T05:59:48.399Z",
         "report":{"status":"available","planLabel":"Pro ai","windows":[
           {"id":"five_hour","label":"Session","shortLabel":"5h","usedPct":17,"resetsAt":"2026-11-16T08:39:59.755599+00:00","tone":"ok"},
           {"id":"weekly","label":"Weekly","shortLabel":"wk","usedPct":16.4,"resetsAt":"2026-11-22T19:59:59.755619+00:00","tone":"ok"}],
           "balances":[],"details":[{"id":"extra_usage","label":"Extra usage","value":"Disabled"}]}},
        {"id":"grok:default","sourceId":"grok","sourceLabel":"Grok","account":{},"fetchedAt":"2026-11-16T05:59:48.400Z",
         "report":{"status":"unavailable","problem":{"kind":"rejected","status":401}}}
    ]}"""

    @Test
    fun parsesUsageAndPutsClaudeGrokFirst() {
        val list = DevParsers.parseUsage(usage)
        assertEquals(listOf("claude", "grok", "codex"), list.map { it.sourceId })

        val claude = list[0]
        assertTrue(claude.report.available)
        assertEquals("Pro ai", claude.report.planLabel)
        assertEquals(listOf(17, 16), claude.report.windows.map { it.usedPct })
        assertEquals("Disabled", claude.report.details.single().value)
        assertNull(claude.problemText)

        val grok = list[1]
        assertFalse(grok.report.available)
        assertNull(grok.account)
        assertTrue(grok.problemText!!.contains("401"))
        assertTrue(grok.shown.windows.isEmpty())

        // 지금은 못 읽지만 마지막 성공 값을 보여 준다
        val codex = list[2]
        assertEquals(86, codex.shown.windows.single().usedPct)
        assertEquals("0", codex.shown.balances.single().remaining)
        assertEquals("2026-11-13T12:28:34.077Z", codex.shownAt)
    }

    @Test
    fun usageToleratesGarbage() {
        assertTrue(DevParsers.parseUsage("").isEmpty())
        assertTrue(DevParsers.parseUsage("""{"error":"paseo 실행파일을 찾지 못했습니다"}""").isEmpty())
    }

    @Test
    fun resetAndAgoText() {
        val now = Instant.parse("2026-11-16T05:00:00Z")
        val seoul = ZoneId.of("Asia/Seoul")
        assertEquals("7일 9시간 후 재설정 · 11월 23일 오후 11:28", UsageText.reset("2026-11-23T14:28:07.000Z", now, seoul))
        assertEquals("3시간 39분 후 재설정 · 11월 16일 오후 5:39", UsageText.reset("2026-11-16T08:39:59.755599+00:00", now, seoul))
        assertEquals("초기화 시간이 지났습니다", UsageText.reset("2026-11-11T21:48:38+00:00", now, seoul))
        assertNull(UsageText.reset("not a date", now, seoul))
        assertEquals("2일 전", UsageText.ago("2026-11-13T12:28:34.077Z", now))
        assertEquals("방금", UsageText.ago("2026-11-16T04:59:40Z", now))
    }

    @Test
    fun parsesGitStatus() {
        val s = DevParsers.parseGitStatus(
            """{"path":"/Users/me/repo","branch":"claude/x","dirty":true,"files":["a.kt","b/c.md"],"upstream":true,"ahead":2,"behind":1,
               "remote":true,"default_branch":"main","pr":{"number":12,"url":"https://github.com/o/r/pull/12","state":"OPEN","title":"t"},"deploy_script":"scripts/deploy.sh"}""",
        )
        assertEquals("claude/x · 변경 2 · ↑2 ↓1", s.summary)
        assertTrue(s.pr!!.isOpen)
        assertEquals(12, s.pr!!.number)
        assertFalse(s.onDefaultBranch)
        assertEquals("scripts/deploy.sh", s.deployScript)

        val clean = DevParsers.parseGitStatus("""{"branch":"main","dirty":false,"files":[],"upstream":false,"default_branch":"main","pr":null,"deploy_script":null}""")
        assertEquals("main · 깨끗함", clean.summary)
        assertTrue(clean.onDefaultBranch)
        assertNull(clean.pr)
        assertNull(clean.deployScript)
    }

    @Test
    fun groupsWorkChatsUnderDeepestWorkspace() {
        val outer = Workspace(id = 1, label = "orca", kind = "folder", path = "/Users/me/orca")
        val inner = Workspace(id = 2, label = "HermesChat", kind = "repo", path = "/Users/me/orca/HermesChat/")
        val all = listOf(outer, inner)
        assertEquals(inner, workspaceOf("/Users/me/orca/HermesChat/android", all))
        assertEquals(outer, workspaceOf("/Users/me/orca/other", all))
        assertNull(workspaceOf("/Users/me/orca-2", all))
        assertNull(workspaceOf(" ".trim(), all))

        val chats = listOf(
            BotChannel(id = 10, title = "앱", workdir = "/Users/me/orca/HermesChat"),
            BotChannel(id = 11, title = "브리지", workdir = "/Users/me/orca/HermesChat/server"),
            BotChannel(id = 12, title = "잡담"),
        )
        val grouped = workChatsByWorkspace(all, chats)
        assertEquals(listOf(10, 11), grouped[2]!!.map { it.id })
        assertNull(grouped[1])
    }

    @Test
    fun soloChannelGetsItsOnlyBotMentioned() {
        val opus = BotMember(id = 1, slug = "opus")
        val solo = BotChannel(id = 10, title = "앱 작업", kind = "channel", members = listOf(opus))
        assertEquals("@opus 빌드 고쳐 줘", soloMention("빌드 고쳐 줘", solo))
        assertEquals("@grok 이건 네가 봐 줘", soloMention("@grok 이건 네가 봐 줘", solo))
        assertEquals("@opus a@b.com 로 보내", soloMention("a@b.com 로 보내", solo))
        // 봇이 둘 이상이거나 DM 이면 건드리지 않는다
        val duo = solo.copy(members = listOf(opus, BotMember(id = 2, slug = "grok")))
        assertEquals("안녕", soloMention("안녕", duo))
        assertEquals("안녕", soloMention("안녕", solo.copy(kind = "dm")))
        assertEquals("안녕", soloMention("안녕", null))
    }
}
