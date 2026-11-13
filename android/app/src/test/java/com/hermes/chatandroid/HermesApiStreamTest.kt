package com.hermes.chatandroid

import com.hermes.chatandroid.data.StreamEvent
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.net.HermesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesApiStreamTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun api() = HermesApi(server.url("/").toString().trimEnd('/'), "key123")

    @Test
    fun streamsNamedEventsAndSendsRequestCorrectly() = runBlocking {
        val body = "event: run.started\ndata: {}\n\n" +
            "event: assistant.delta\ndata: {\"delta\":\"He\"}\n\n" +
            "event: assistant.delta\ndata: {\"delta\":\"llo\"}\n\n" +
            "event: assistant.completed\ndata: {\"content\":\"Hello\"}\n\n" +
            "event: run.completed\ndata: {}\n\n" +
            "event: done\ndata: {}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))

        val events = api().streamChat("sess 1", "안녕", listOf("/p/up/a.png")).toList()

        assertEquals(
            listOf(StreamEvent.Delta("He"), StreamEvent.Delta("llo"), StreamEvent.Completed("Hello"), StreamEvent.Done),
            events,
        )
        val req = server.takeRequest()
        assertEquals("/api/sessions/sess%201/chat/stream", req.path)
        assertEquals("Bearer key123", req.getHeader("Authorization"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertTrue(req.body.readUtf8().contains("[첨부: /p/up/a.png]"))
    }

    @Test
    fun errorEventSurfaces() = runBlocking {
        server.enqueue(MockResponse().setBody("event: error\ndata: {\"message\":\"bad\"}\n\n"))
        assertEquals(listOf<StreamEvent>(StreamEvent.Error("bad")), api().streamChat("s", "m").toList())
    }

    @Test
    fun unauthorizedThrowsApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"nope"}"""))
        val ex = try { api().streamChat("s", "m").toList(); null } catch (e: ApiException) { e }
        assertEquals(401, ex?.code)
    }

    @Test
    fun createSessionParsesWrappedResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"session":{"id":"new1"}}"""))
        assertEquals("new1", api().createSession("t").id)
    }
}
