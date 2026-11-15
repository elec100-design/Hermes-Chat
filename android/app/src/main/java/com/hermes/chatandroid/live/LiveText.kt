package com.hermes.chatandroid.live

/**
 * Live 모드의 텍스트 처리 (android 의존 없음, 단위 테스트 대상).
 * - 스트리밍 delta 를 문장 단위로 끊어 TTS 에 넘긴다.
 * - 마크다운·코드·URL 을 소리 내어 읽기 좋게 정리한다.
 */
object LiveText {
    private val sentenceEnd = Regex("""[.!?。！？…]+["')\]」』]*\s|[\n]+""")

    /**
     * [buffer] 에서 완성된 문장들을 떼어 낸다. 반환: (읽을 문장들, 남은 버퍼).
     * 너무 짧은 조각(쉼표 없이 3자 미만)은 다음 문장과 합친다.
     */
    fun takeSentences(buffer: String): Pair<List<String>, String> {
        val out = mutableListOf<String>()
        var start = 0
        var pending = ""
        for (m in sentenceEnd.findAll(buffer)) {
            val piece = pending + buffer.substring(start, m.range.last + 1)
            start = m.range.last + 1
            if (piece.trim().length < 3) { pending = piece; continue }
            out += piece.trim()
            pending = ""
        }
        return out to (pending + buffer.substring(start))
    }

    private val codeBlock = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`([^`]*)`")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val url = Regex("""https?://\S+""")
    private val heading = Regex("""(?m)^\s{0,3}#{1,6}\s*""")
    private val bullet = Regex("""(?m)^\s*([-*+]|\d+[.)])\s+""")
    private val emphasis = Regex("""[*_~]{1,3}""")
    private val tableRule = Regex("""(?m)^\s*\|?\s*:?-{2,}.*$""")

    /** TTS 용 정리. 코드 블록은 "코드 생략"으로 바꾼다. 결과가 비면 빈 문자열. */
    fun forSpeech(text: String): String = text
        .replace(codeBlock, " 코드 생략. ")
        .replace(inlineCode, "$1")
        .replace(link, "$1")
        .replace(url, "링크")
        .replace(tableRule, "")
        .replace(heading, "")
        .replace(bullet, "")
        .replace(emphasis, "")
        .replace('|', ' ')
        .replace(Regex("""[ \t]+"""), " ")
        .trim()

    private val visionWords = listOf(
        "읽어", "글자", "글씨", "텍스트", "써 있", "써있", "적혀", "번역", "메뉴판", "표지판", "문서", "영수증",
        "봐 줘", "봐줘", "보여", "보이", "이거", "저거", "이게", "저게", "여기", "앞에", "사진", "찍어",
    )

    /** "요청할 때만" 모드에서 시야 사진을 붙일 발화인지. */
    fun wantsPhoto(utterance: String): Boolean = visionWords.any { utterance.contains(it) }

    /** 채널(멤버 여러 명)이면 대상 봇 멘션을 붙인다. 이미 @로 시작하면 그대로. */
    fun withMention(utterance: String, mentionSlug: String?): String {
        val t = utterance.trim()
        if (mentionSlug.isNullOrBlank() || t.startsWith("@")) return t
        return "@$mentionSlug $t"
    }
}
