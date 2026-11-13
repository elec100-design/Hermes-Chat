package com.hermes.chatandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

sealed interface MdBlock {
    data class Text(val text: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
}

private val HeadingRe = Regex("^(#{1,3})\\s+(.+)$")

/** ``` 펜스 + 제목(#) + 인용(>) 분리. 닫히지 않은 마지막 펜스(스트리밍 중)는 코드로 취급. */
fun splitMarkdownBlocks(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val buf = mutableListOf<String>()
    var inCode = false
    var lang: String? = null

    fun flushTextBuffer() {
        if (buf.isEmpty()) return
        val lines = buf.toList()
        buf.clear()
        val para = mutableListOf<String>()
        val quote = mutableListOf<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            val joined = para.joinToString("\n").trim('\n')
            para.clear()
            if (joined.isNotBlank()) blocks.add(MdBlock.Text(joined))
        }
        fun flushQuote() {
            if (quote.isEmpty()) return
            val joined = quote.joinToString("\n").trim('\n')
            quote.clear()
            if (joined.isNotBlank()) blocks.add(MdBlock.Quote(joined))
        }

        for (line in lines) {
            val trimmed = line.trimStart()
            val h = HeadingRe.matchEntire(trimmed)
            when {
                h != null -> {
                    flushPara(); flushQuote()
                    blocks.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim()))
                }
                trimmed.startsWith("> ") || trimmed == ">" -> {
                    flushPara()
                    quote.add(trimmed.removePrefix(">").trimStart())
                }
                else -> {
                    flushQuote()
                    para.add(line)
                }
            }
        }
        flushPara(); flushQuote()
    }

    fun flushCodeBuffer() {
        if (buf.isEmpty()) return
        val joined = buf.joinToString("\n")
        buf.clear()
        if (joined.isNotBlank()) blocks.add(MdBlock.Code(lang, joined))
    }

    for (line in raw.split("\n")) {
        if (line.trim().startsWith("```")) {
            if (inCode) {
                flushCodeBuffer()
                inCode = false
                lang = null
            } else {
                flushTextBuffer()
                inCode = true
                lang = line.trim().removePrefix("```").trim().ifEmpty { null }
            }
        } else {
            buf.add(line)
        }
    }
    if (inCode) flushCodeBuffer() else flushTextBuffer()
    return blocks
}

/** 인라인 마크다운: **굵게**, *기울임*, `코드`, [링크](url). 짝이 안 맞으면 문자 그대로 둔다. */
fun inlineMarkdown(
    text: String,
    codeBackground: Color = Color(0x22888888),
    linkColor: Color = Color(0xFF5647E5),
): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i + 1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket + 2) {
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 1, closeBracket))
                            }
                            i = closeParen + 1
                            continue
                        }
                    }
                    append(text[i]); i++
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1 && !text[i + 1].isWhitespace()) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }

private val BulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NumberedRe = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")

/** 목록 줄(`- x`, `* x`, `1. x`)을 들여쓰기 + 글머리(`•`/`1.`)로 바꾼다. 나머지 줄은 그대로. */
fun normalizeMarkdownLists(text: String): String =
    text.split("\n").joinToString("\n") { line ->
        val b = BulletRe.matchEntire(line)
        val n = NumberedRe.matchEntire(line)
        when {
            b != null -> "${b.groupValues[1]}  \u2022 ${b.groupValues[2]}"
            n != null -> "${n.groupValues[1]}  ${n.groupValues[2]}. ${n.groupValues[3]}"
            else -> line
        }
    }
