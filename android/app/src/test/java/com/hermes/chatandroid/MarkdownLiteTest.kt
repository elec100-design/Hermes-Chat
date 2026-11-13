package com.hermes.chatandroid

import com.hermes.chatandroid.ui.components.MdBlock
import com.hermes.chatandroid.ui.components.inlineMarkdown
import com.hermes.chatandroid.ui.components.splitMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    @Test
    fun splitsHeadingsQuotesParagraphsAndCode() {
        val raw = """
            # 제목
            본문 첫 줄
            > 인용문
            > 둘째 줄
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        val blocks = splitMarkdownBlocks(raw)
        assertEquals(
            listOf(
                MdBlock.Heading(1, "제목"),
                MdBlock.Text("본문 첫 줄"),
                MdBlock.Quote("인용문\n둘째 줄"),
                MdBlock.Code("kotlin", "val x = 1"),
            ),
            blocks,
        )
    }

    @Test
    fun unclosedFenceTreatedAsCodeDuringStreaming() {
        val blocks = splitMarkdownBlocks("설명\n```python\nprint(1)")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Text("설명"), blocks[0])
        assertEquals(MdBlock.Code("python", "print(1)"), blocks[1])
    }

    @Test
    fun inlineMarkdownHandlesBoldCodeItalicAndLinks() {
        val out = inlineMarkdown("**굵게** `code` *기울임* [링크](https://example.com)")
        assertEquals("굵게 code 기울임 링크", out.text)
        assertTrue(out.spanStyles.size >= 4)
    }
}
