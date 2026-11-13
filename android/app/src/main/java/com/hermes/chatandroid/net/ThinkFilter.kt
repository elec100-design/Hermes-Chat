package com.hermes.chatandroid.net

/** `<think>…</think>` 제거 (iOS MarkdownLite.strippingThink 미러). */
object ThinkFilter {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    /** 닫히지 않은 <think> 뒤는 전부 숨기고, 끝에 걸친 "<think>" 접두는 보류한다. */
    fun strip(raw: String): String {
        val sb = StringBuilder()
        var rest = raw
        while (true) {
            val open = rest.indexOf(OPEN)
            if (open < 0) break
            sb.append(rest, 0, open)
            val close = rest.indexOf(CLOSE, open + OPEN.length)
            if (close < 0) return sb.toString()
            rest = rest.substring(close + CLOSE.length)
        }
        sb.append(rest)
        return trimPartialOpenTag(sb.toString())
    }

    /** 미닫힌 <think> 존재 여부 ("생각 중…" 표시용). */
    fun hasOpenThink(raw: String): Boolean {
        val lastOpen = raw.lastIndexOf(OPEN)
        if (lastOpen < 0) return false
        return raw.indexOf(CLOSE, lastOpen + OPEN.length) < 0
    }

    private fun trimPartialOpenTag(text: String): String {
        val maxLen = minOf(OPEN.length - 1, text.length)
        for (len in maxLen downTo 1) {
            if (OPEN.startsWith(text.substring(text.length - len))) {
                return text.substring(0, text.length - len)
            }
        }
        return text
    }
}
