package com.hermes.chatandroid.net

import okio.BufferedSource

/** SSE 프레임 하나. [event] 가 없으면 기본 "message". 여러 data 줄은 "\n" 으로 합쳐진다. */
data class SseFrame(val event: String?, val data: String)

/** 순수 코틀린 SSE 파서 (줄 단위, 빈 줄로 프레임 구분). */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** 한 줄을 먹이고, 프레임이 완성되면 반환. */
    fun feed(line: String): SseFrame? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // 주석/keep-alive
        val idx = line.indexOf(':')
        val field = if (idx < 0) line else line.substring(0, idx)
        var value = if (idx < 0) "" else line.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** 스트림 종료 시 남은 프레임 배출. */
    fun flush(): SseFrame? {
        if (!hasData && event == null) return null
        val frame = SseFrame(event, data.toString())
        event = null
        data.setLength(0)
        hasData = false
        return frame
    }

    companion object {
        fun parse(lines: Sequence<String>): Sequence<SseFrame> = sequence {
            val p = SseParser()
            for (line in lines) {
                val f = p.feed(line)
                if (f != null) yield(f)
            }
            p.flush()?.let { yield(it) }
        }

        fun parse(source: BufferedSource): Sequence<SseFrame> =
            parse(generateSequence { source.readUtf8Line() })

        fun parse(text: String): Sequence<SseFrame> = parse(text.lineSequence())
    }
}
