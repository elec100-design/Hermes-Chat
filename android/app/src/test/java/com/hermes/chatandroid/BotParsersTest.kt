package com.hermes.chatandroid

import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.Parsers
import com.hermes.chatandroid.data.ScanResult
import com.hermes.chatandroid.data.Settings
import com.hermes.chatandroid.net.errorMessageFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hermes-bot(main.py/db.py/repos.py/workspace.py/attachments.py) 의 실제 응답 형태 기준. */
class BotParsersTest {
    @Test fun botRowFromPatchResponse() {
        // _public_bot(): api_key 없음 + has_api_key, dm_channel_id/preview/tone 없음
        val b = Parsers.parseBotObject(
            """{"id":3,"slug":"coder","name":"코더","profile_name":"coder","gateway_port":8643,"gateway_host":null,
            "has_api_key":true,"persona_note":"짧게","soul_summary":"코드","avatar":"","icon":"","workdir":"/Users/m/proj",
            "read_only":1,"approval_policy":"auto","computer_use":0,"source":"discovered","health":"online",
            "name_mismatch":null,"last_seen_at":"2026-07-01T00:00:00+00:00","status":"active","created_at":"x","updated_at":"y"}""",
        )!!
        assertEquals(8643, b.gatewayPort); assertEquals(true, b.hasApiKey); assertEquals(true, b.readOnly)
        assertEquals(false, b.computerUse); assertEquals("auto", b.approvalPolicy); assertEquals("/Users/m/proj", b.workdir)
        assertNull(b.dmChannelId); assertNull(b.tone); assertEquals("🤖", b.avatarText); assertTrue(b.isOnline)
        assertFalse(b.isArchived)
        assertNull(Parsers.parseBotObject("""{"detail":"봇을 찾을 수 없습니다"}"""))
    }

    @Test fun botsListArchivedHasNoDm() {
        val bots = Parsers.parseBots("""{"data":[{"id":1,"slug":"a","status":"archived","dm_channel_id":null}]}""")
        assertTrue(bots.single().isArchived); assertNull(bots.single().dmChannelId)
    }

    @Test fun channelRowFromPatchAndCreate() {
        val c = Parsers.parseJsonChannel(
            """{"id":9,"slug":"team","title":"팀","topic":"","workdir":"/w","kind":"channel","bot_id":null,"bot_chat":0,
            "status":"active","created_at":"a","updated_at":"b"}""",
        )!!
        assertEquals(false, c.botChat); assertEquals("/w", c.workdir); assertEquals("channel", c.kind); assertFalse(c.isDm)
        assertTrue(c.members.isEmpty())
        assertTrue(Parsers.parseJsonChannel("""{"id":1,"title":"x","kind":"dm"}""")!!.isDm)
    }

    @Test fun attachmentsJsonStringOnMessage() {
        val json = """{"data":[{"id":5,"channel_id":2,"parent_id":null,"root_id":null,"seq":1,"role":"user","author":"나",
            "body":"(첨부만)","status":"done","job_id":null,"author_bot_id":null,
            "attachments":"[{\"name\":\"a.png\",\"path\":\"/h/.hermes/profiles/x/uploads/a.png\",\"mime\":\"image/png\",\"size\":2048}, {\"nopath\":1}]",
            "error":null,"created_at":"t","tone":0,"display_name":"나","avatar":"🧑","icon_key":""}]}"""
        val m: BotMessage = Parsers.parseBotMessages(json).single()
        val a = m.attachmentItems.single()
        assertEquals("a.png", a.name); assertEquals(2048L, a.size); assertTrue(a.isImage)
        assertNull(m.iconKey); assertNull(m.parentId)
        assertTrue(Parsers.parseAttachments(null).isEmpty())
        assertTrue(Parsers.parseAttachments("not json").isEmpty())
        assertTrue(Parsers.parseAttachments("{}").isEmpty())
    }

    @Test fun uploadResponse() {
        val u = Parsers.parseUpload("""{"name":"한글.txt","path":"/data/uploads/p/한글.txt","mime":null,"size":10,"via":"local"}""")!!
        assertEquals("local", u.via); assertNull(u.mime); assertEquals(10L, u.size)
        assertNull(Parsers.parseUpload("""{"name":"x"}"""))
    }

    @Test fun postResultWithBotFields() {
        val r = Parsers.parseBotPostResult(
            """{"message_id":1,"reply_message_id":2,"job_id":3,"bot":"coder","bot_name":"코더","bot_avatar":"💻","bot_icon":"","bot_tone":2}""",
        )
        assertEquals("코더", r.botName); assertEquals(2, r.botTone); assertEquals(3, r.jobId)
        val err = Parsers.parseBotPostResult("""{"message_id":1,"reply_message_id":null,"job_id":null,"error":"@x 는 이 채널 멤버가 아닙니다"}""")
        assertNull(err.jobId); assertNotNull(err.error)
    }

    @Test fun stepsList() {
        val steps = Parsers.parseSteps(
            """{"data":[{"id":12,"message_id":5,"job_id":3,"recording_id":null,"seq":2,"kind":"tool_result","title":"ls","detail":"a\nb",
            "status":"done","payload":null,"created_at":"t"},{"id":11,"message_id":5,"job_id":3,"seq":1,"kind":"tool","title":"ls","detail":"","status":"running"}]}""",
        )
        assertEquals(listOf(11, 12), steps.map { it.id }) // seq 순
        assertEquals("tool_result", steps[1].kind); assertEquals("a\nb", steps[1].detail); assertEquals(5, steps[0].messageId)
        assertTrue(Parsers.parseSteps("""{"detail":"x"}""").isEmpty())
    }

    @Test fun discoverAndHealth() {
        val d = Parsers.parseDiscoverResult(
            """{"source":"bridge","found":3,"created":1,"updated":2,"offline":0,"archived":1,"skipped":[":8650 는 hermes 의 'x' 플랫폼입니다"]}""",
        )
        assertEquals("bridge", d.source); assertEquals(3, d.found); assertEquals(1, d.archived); assertEquals(1, d.skipped.size)
        val h = Parsers.parseHealthSummary("""{"online":2,"total":3}""")
        assertEquals(2, h.online); assertEquals(3, h.total)
    }

    @Test fun workspaces() {
        val list = Parsers.parseWorkspaces(
            """{"data":[{"id":1,"kind":"folder","label":"docs","path":"/Users/m/docs","repo_url":null,"default_branch":null,"created_at":"t"},
            {"id":2,"kind":"repo","label":"o/r","path":"/d/repos/o/r","repo_url":"https://github.com/o/r","default_branch":"main","created_at":"t"}]}""",
        )
        assertFalse(list[0].isRepo); assertTrue(list[1].isRepo); assertEquals("main", list[1].defaultBranch)
        assertEquals(2, Parsers.parseWorkspaceObject("""{"id":2,"kind":"repo","label":"o/r","path":"/p"}""")?.id)
    }

    @Test fun browse() {
        val b = Parsers.parseBrowse("""{"path":"/Users/m","parent":null,"entries":[{"name":"docs","path":"/Users/m/docs"},{"name":"src","path":"/Users/m/src"}]}""")!!
        assertNull(b.parent); assertEquals(listOf("docs", "src"), b.entries.map { it.name })
        assertEquals("/Users/m", Parsers.parseBrowse("""{"path":"/Users/m/docs","parent":"/Users/m","entries":[]}""")!!.parent)
        assertNull(Parsers.parseBrowse("""{"detail":"허용된 범위 밖의 경로입니다"}"""))
    }

    @Test fun workspaceStatusAndCommitAndPush() {
        val s = Parsers.parseWorkspaceStatus("""{"repo":true,"branch":"main","dirty":true,"files":["a.py","b/c.txt"]}""")
        assertTrue(s.isRepo); assertTrue(s.dirty); assertEquals(2, s.files.size); assertEquals("main", s.branch)
        val notRepo = Parsers.parseWorkspaceStatus("""{"repo":false}""")
        assertFalse(notRepo.isRepo); assertFalse(notRepo.dirty); assertTrue(notRepo.files.isEmpty())
        assertEquals("abc123", Parsers.parseCommitSha("""{"ok":true,"sha":"abc123"}"""))
        val p = Parsers.parsePushRequest("""{"approval_id":7,"status":"pending","note":"승인 인박스에서 확인 후 실행됩니다."}""")
        assertEquals(7, p.approvalId); assertEquals("pending", p.status)
        assertEquals("목록에서만 제거했습니다.", Parsers.parseNote("""{"ok":true,"note":"목록에서만 제거했습니다."}"""))
    }

    @Test fun errorDetailBecomesMessage() {
        assertEquals("봇 전용 대화창은 지울 수 없습니다. 봇을 보관하세요.",
            errorMessageFor(400, """{"detail":"봇 전용 대화창은 지울 수 없습니다. 봇을 보관하세요."}"""))
        assertEquals("채널을 찾을 수 없습니다", errorMessageFor(404, """{"detail":"채널을 찾을 수 없습니다"}"""))
        assertEquals("찾을 수 없음(404)", errorMessageFor(404, ""))
        assertEquals("HTTP 500", errorMessageFor(500, ""))
        assertTrue(errorMessageFor(401, """{"detail":"Unauthorized"}""").startsWith("인증 실패(401)"))
        // FastAPI 422
        assertEquals("Field required", errorMessageFor(422, """{"detail":[{"loc":["body","x"],"msg":"Field required","type":"missing"}]}"""))
    }

    @Test fun scanResultMessages() {
        assertEquals("API 키가 올바르지 않습니다", ScanResult(emptyList(), unauthorized = true).errorMessage)
        assertTrue(ScanResult(emptyList()).errorMessage!!.contains("찾지 못했습니다"))
        assertNull(ScanResult(listOf(com.hermes.chatandroid.data.ProfileEntry("a", 1)), true).errorMessage)
    }

    @Test fun hostOnly() {
        assertEquals("100.83.59.60", Settings.hostOnly("http://100.83.59.60:8900/"))
        assertEquals("h", Settings.hostOnly("h:8900"))
        assertEquals("h", Settings.hostOnly(" h "))
    }
}
