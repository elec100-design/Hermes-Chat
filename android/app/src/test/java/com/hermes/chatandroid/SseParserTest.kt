package com.hermes.chatandroid

import com.hermes.chatandroid.data.StreamEvent
import com.hermes.chatandroid.net.HermesApi
import com.hermes.chatandroid.net.SseFrame
import com.hermes.chatandroid.net.SseParser
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    @Test
    fun namedEvents() {
        val text = "event: run.started\ndata: {}\n\nevent: assistant.delta\ndata: {\"delta\":\"Hi\"}\n\n"
        val frames = SseParser.parse(text).toList()
        assertEquals(
            listOf(SseFrame("run.started", "{}"), SseFrame("assistant.delta", "{\"delta\":\"Hi\"}")),
            frames,
        )
    }

    @Test
    fun multiLineDataJoinedWithNewline() {
        val frames = SseParser.parse("data: a\ndata: b\ndata:c\n\n").toList()
        assertEquals(listOf(SseFrame(null, "a\nb\nc")), frames)
    }

    @Test
    fun commentsIgnoredAndTrailingFrameFlushed() {
        val frames = SseParser.parse(": keep-alive\nevent: done\ndata: {}").toList()
        assertEquals(listOf(SseFrame("done", "{}")), frames)
    }

    @Test
    fun parsesFromOkioSource() {
        val buf = Buffer().writeUtf8("data: x\r\n\r\ndata: y\n\n")
        assertEquals(listOf("x", "y"), SseParser.parse(buf).map { it.data }.toList())
    }

    @Test
    fun mapsGatewayNamedEvents() {
        assertEquals(StreamEvent.Delta("안"), HermesApi.mapFrame(SseFrame("assistant.delta", "{\"delta\":\"안\"}")))
        assertEquals(StreamEvent.Completed("full"), HermesApi.mapFrame(SseFrame("assistant.completed", "{\"content\":\"full\"}")))
        assertEquals(StreamEvent.Done, HermesApi.mapFrame(SseFrame("done", "{}")))
        assertNull(HermesApi.mapFrame(SseFrame("run.started", "{}")))
        assertNull(HermesApi.mapFrame(SseFrame("run.completed", "{}")))
        assertEquals(StreamEvent.Tool("web"), HermesApi.mapFrame(SseFrame("tool.started", "{\"name\":\"web\"}")))
    }

    @Test
    fun mapsErrorEvent() {
        assertEquals(StreamEvent.Error("boom"), HermesApi.mapFrame(SseFrame("error", "{\"message\":\"boom\"}")))
    }

    @Test
    fun mapsOpenAiChunkAndDone() {
        val chunk = SseFrame(null, "{\"choices\":[{\"delta\":{\"content\":\"yo\"}}]}")
        assertEquals(StreamEvent.Delta("yo"), HermesApi.mapFrame(chunk))
        assertEquals(StreamEvent.Done, HermesApi.mapFrame(SseFrame(null, "[DONE]")))
        assertNull(HermesApi.mapFrame(SseFrame(null, "{\"choices\":[{\"delta\":{}}]}")))
    }
}
