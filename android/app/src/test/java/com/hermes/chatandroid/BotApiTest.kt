package com.hermes.chatandroid

import com.hermes.chatandroid.data.BotAttachment
import com.hermes.chatandroid.data.BotPatch
import com.hermes.chatandroid.data.BotStreamEvent
import com.hermes.chatandroid.data.ChannelPatch
import com.hermes.chatandroid.data.ProfileDiscovery
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.net.BotApi
import com.hermes.chatandroid.ui.bots.checkBotServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BotApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun api(token: String = "tok") = BotApi(server.url("/").toString().trimEnd('/'), token)
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun updateBotSendsOnlySetFieldsAsIntBooleans() = runBlocking {
        server.enqueue(json("""{"id":3,"slug":"c","read_only":1,"computer_use":0,"workdir":""}"""))
        val b = api().updateBot(3, BotPatch(readOnly = true, computerUse = false, workdir = "", name = "N"))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method); assertEquals("/api/bots/3", r.path)
        assertEquals("Bearer tok", r.getHeader("Authorization"))
        val body = r.body.readUtf8()
        assertTrue(body, body.contains("\"read_only\":1") && body.contains("\"computer_use\":0") && body.contains("\"workdir\":\"\""))
        assertFalse(body, body.contains("avatar") || body.contains("status"))
        assertEquals(true, b.readOnly)
    }

    @Test fun serverDetailBecomesApiExceptionMessage() = runBlocking {
        server.enqueue(json("""{"detail":"봇 전용 대화창은 지울 수 없습니다. 봇을 보관하세요."}""", 400))
        try { api().deleteChannel(5); fail() } catch (e: ApiException) {
            assertEquals(400, e.code); assertEquals("봇 전용 대화창은 지울 수 없습니다. 봇을 보관하세요.", e.message)
            assertEquals(e.message, e.detail)
        }
        val r = server.takeRequest(); assertEquals("DELETE", r.method); assertEquals("/api/channels/5", r.path)
    }

    @Test fun channelAndMemberEndpoints() = runBlocking {
        server.enqueue(json("""{"id":2,"title":"새","bot_chat":0,"kind":"channel"}"""))
        server.enqueue(json("""{"ok":true}""", 201))
        server.enqueue(json("""{"ok":true}"""))
        val c = api().updateChannel(2, ChannelPatch(title = "새", botChat = false))
        api().addChannelMember(2, 7)
        api().removeChannelMember(2, 7)
        assertEquals(false, c.botChat)
        val r1 = server.takeRequest(); assertEquals("PATCH", r1.method); assertEquals("/api/channels/2", r1.path)
        assertTrue(r1.body.readUtf8().contains("\"bot_chat\":0"))
        val r2 = server.takeRequest(); assertEquals("POST", r2.method); assertEquals("/api/channels/2/members", r2.path)
        assertEquals("""{"bot_id":7}""", r2.body.readUtf8())
        val r3 = server.takeRequest(); assertEquals("DELETE", r3.method); assertEquals("/api/channels/2/members/7", r3.path)
    }

    @Test fun uploadIsMultipartFileField() = runBlocking {
        server.enqueue(json("""{"name":"a.txt","path":"/p/uploads/a.txt","mime":"text/plain","size":3,"via":"bridge"}"""))
        val a = api().uploadAttachment(4, "a.txt", "abc".toByteArray(), "text/plain")
        val r = server.takeRequest()
        assertEquals("/api/channels/4/attachments", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = r.body.readUtf8()
        assertTrue(body, body.contains("name=\"file\"") && body.contains("filename=\"a.txt\"") && body.contains("abc"))
        assertEquals("bridge", a.via)
    }

    @Test fun postMessageWithAttachmentsAndWorkdir() = runBlocking {
        server.enqueue(json("""{"message_id":1,"reply_message_id":2,"job_id":3,"bot":"c"}""", 202))
        api().postMessage(4, "", listOf(BotAttachment("a.txt", "/p/a.txt", "text/plain", 3)), "/w")
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"attachments\":[{\"name\":\"a.txt\",\"path\":\"/p/a.txt\",\"mime\":\"text/plain\",\"size\":3}]"))
        assertTrue(body, body.contains("\"workdir\":\"/w\""))
    }

    @Test fun workspaceEndpoints() = runBlocking {
        server.enqueue(json("""{"path":"/Users/m/a b","parent":"/Users/m","entries":[]}"""))
        server.enqueue(json("""{"approval_id":9,"status":"pending","note":"n"}"""))
        server.enqueue(json("""{"ok":true,"note":"목록에서만 제거했습니다."}"""))
        server.enqueue(json("""{"repo":false}"""))
        api().browseWorkspaces("/Users/m/a b")
        assertEquals("/api/workspaces/browse?path=%2FUsers%2Fm%2Fa+b", server.takeRequest().path)
        assertEquals(9, api().requestWorkspacePush(2, "main", 1).approvalId)
        val push = server.takeRequest(); assertEquals("/api/workspaces/2/push", push.path)
        assertEquals("""{"branch":"main","bot_id":1}""", push.body.readUtf8())
        assertEquals("목록에서만 제거했습니다.", api().removeWorkspace(2))
        assertFalse(api().fetchWorkspaceStatus(2).isRepo)
    }

    @Test fun jobStreamSurfacesStepsAndEndSteps() = runBlocking {
        val step = """{"id":1,"message_id":5,"job_id":3,"seq":1,"kind":"tool","title":"ls","detail":"","status":"running","created_at":"t"}"""
        val body = "event: delta\ndata: {\"text\":\"안\"}\n\n" +
            "event: steps\ndata: {\"steps\":[$step]}\n\n" +
            "event: end\ndata: {\"status\":\"done\",\"error\":null,\"steps\":[$step]}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val ev = api().jobStream(3).toList()
        assertEquals(3, ev.size)
        assertEquals("ls", (ev[1] as BotStreamEvent.Steps).steps.single().title)
        val end = ev[2] as BotStreamEvent.End
        assertEquals("done", end.status); assertEquals(1, end.steps.size)
    }

    @Test fun checkBotServerReportsTokenProblemOn401() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))          // /health (무인증)
        server.enqueue(json("""{"detail":"Unauthorized"}""", 401))              // /api/bots
        val r = checkBotServer(api(""))
        assertFalse(r.ok); assertTrue(r.message, r.message.contains("토큰"))

        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(json("""{"data":[{"id":1,"slug":"a"}]}"""))
        val ok = checkBotServer(api())
        assertTrue(ok.ok); assertTrue(ok.message.contains("1"))
    }

    @Test fun scanReportsUnauthorizedWhenPortAnswers401() = runBlocking {
        server.enqueue(json("""{"error":"invalid key"}""", 401))
        val port = server.port
        val d = ProfileDiscovery(ports = port..port, urlFor = { _, p -> "http://127.0.0.1:$p" })
        val r = d.scan("127.0.0.1", "bad")
        assertTrue(r.profiles.isEmpty()); assertTrue(r.unauthorized)
        assertEquals("API 키가 올바르지 않습니다", r.errorMessage)
    }
}
