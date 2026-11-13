package com.hermes.chatandroid

import com.hermes.chatandroid.data.Parsers
import com.hermes.chatandroid.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test fun sessionShape1Wrapped() {
        val s = Parsers.parseSessionResponse("""{"object":"hermes.session","session":{"id":"s1","title":"T"}}""")
        assertEquals("s1", s?.id); assertEquals("T", s?.title)
    }

    @Test fun sessionShape2Direct() =
        assertEquals("s2", Parsers.parseSessionResponse("""{"id":"s2","title":null}""")?.id)

    @Test fun sessionShape3DataWrapped() =
        assertEquals("s3", Parsers.parseSessionResponse("""{"data":{"id":"s3"}}""")?.id)

    @Test fun sessionShape4DeepAndIntId() {
        assertEquals("42", Parsers.parseSessionResponse("""{"result":{"inner":{"session_id":42}}}""")?.id)
        assertEquals("abc", Parsers.parseSessionResponse("\"abc\"")?.id)
        assertNull(Parsers.parseSessionResponse("""{"nothing":true}"""))
    }

    @Test fun sessionList() {
        val page = Parsers.parseSessionList(
            """{"data":[{"id":"a","last_active":1.0},{"id":"b","started_at":5},{"title":"noid"}],"has_more":true}"""
        )
        assertEquals(listOf("b", "a"), page.sessions.map { it.id })
        assertTrue(page.hasMore)
    }

    @Test fun messageIdIntOrString() {
        val msgs = Parsers.parseMessages(
            """{"data":[{"id":7,"role":"user","content":"hi"},{"id":"x9","role":"assistant","content":null},"junk"]}"""
        )
        assertEquals(listOf("7", "x9"), msgs.map { it.id })
        assertEquals("", msgs[1].content)
        assertTrue(msgs[0].isUser)
    }

    @Test fun botToneLenient() {
        val json = """{"data":[
            {"id":1,"tone":"0"},{"id":2,"tone":0},{"id":3,"tone":""},{"id":4,"tone":"3"},{"id":5,"tone":3},{"id":6,"tone":"abc"}
        ]}"""
        assertEquals(listOf(null, null, null, 3, 3, null), Parsers.parseBots(json).map { it.tone })
    }

    @Test fun botBooleansIntOrBool() {
        val bots = Parsers.parseBots("""{"data":[{"id":1,"read_only":1},{"id":2,"read_only":0},{"id":3,"read_only":true},{"id":4}]}""")
        assertEquals(listOf(true, false, true, null), bots.map { it.readOnly })
    }

    @Test fun channelsWithMembersAndMessages() {
        val ch = Parsers.parseBotChannels(
            """{"data":[{"id":3,"title":"G","bot_chat":1,"members":[{"id":9,"slug":"a","tone":"2","avatar":""}]}]}"""
        ).single()
        assertEquals(true, ch.botChat)
        assertEquals(2, ch.members.single().tone)
        assertEquals("🤖", ch.members.single().avatarText)

        val m = Parsers.parseBotMessages(
            """{"data":[{"id":1,"role":"bot","body":"","status":"running","job_id":12,"attachments":null}]}"""
        ).single()
        assertTrue(m.isInFlight); assertEquals(12, m.jobId); assertNull(m.attachments)
    }

    @Test fun settingsUrls() {
        val s = Settings(host = "http://100.1.2.3/", botHost = "100.1.2.3")
        assertEquals("100.1.2.3", s.cleanHost)
        assertEquals("http://100.1.2.3:8900", s.botBaseUrl)
        assertEquals("http://100.1.2.3:8765", s.bridgeBaseUrl)
        assertEquals("http://h:1", Settings.normalizeUrl("h:1", 8900))
        assertFalse(Settings().botConfigured)
    }
}
