package com.hermes.chatandroid.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTextTest {
    @Test fun takesCompleteSentencesAndKeepsRest() {
        val (s, rest) = LiveText.takeSentences("안녕하세요. 오늘 날씨는 맑아요! 그리고 내일")
        assertEquals(listOf("안녕하세요.", "오늘 날씨는 맑아요!"), s)
        assertEquals("그리고 내일", rest)
    }

    @Test fun newlineEndsSentence() {
        val (s, rest) = LiveText.takeSentences("첫 줄입니다\n둘째")
        assertEquals(listOf("첫 줄입니다"), s)
        assertEquals("둘째", rest)
    }

    @Test fun tinyFragmentsMergeWithNext() {
        val (s, rest) = LiveText.takeSentences("네. 알겠습니다. ")
        assertEquals(listOf("네. 알겠습니다."), s)
        assertEquals("", rest)
    }

    @Test fun incrementalDeltasProduceSameSentences() {
        var buf = ""
        val out = mutableListOf<String>()
        listOf("메뉴판에 ", "김치찌개가 있어요", ". 가격은 9000원", "입니다. ").forEach { d ->
            val (s, r) = LiveText.takeSentences(buf + d); out += s; buf = r
        }
        assertEquals(listOf("메뉴판에 김치찌개가 있어요.", "가격은 9000원입니다."), out)
        assertEquals("", buf)
    }

    @Test fun decimalNumbersDoNotSplit() {
        val (s, rest) = LiveText.takeSentences("3.5킬로그램")
        assertTrue(s.isEmpty())
        assertEquals("3.5킬로그램", rest)
    }

    @Test fun speechStripsMarkdown() {
        assertEquals("제목 항목 하나 굵게 링크", LiveText.forSpeech("## 제목\n- 항목 하나\n**굵게** [링크](http://x.y)").replace("\n", " "))
        assertEquals("코드 생략.", LiveText.forSpeech("```kotlin\nval a = 1\n```"))
        assertEquals("주소는 링크", LiveText.forSpeech("주소는 https://example.com/a"))
    }

    @Test fun wantsPhotoOnVisionWords() {
        assertTrue(LiveText.wantsPhoto("이거 뭐야"))
        assertTrue(LiveText.wantsPhoto("메뉴판 읽어줘"))
        assertFalse(LiveText.wantsPhoto("오늘 일정 알려줘"))
    }

    @Test fun mentionAddedOnlyWhenNeeded() {
        assertEquals("@hermes 안녕", LiveText.withMention(" 안녕 ", "hermes"))
        assertEquals("@other 안녕", LiveText.withMention("@other 안녕", "hermes"))
        assertEquals("안녕", LiveText.withMention("안녕", null))
    }
}
