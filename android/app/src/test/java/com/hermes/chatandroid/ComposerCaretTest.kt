package com.hermes.chatandroid

import com.hermes.chatandroid.data.ComposerCaret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerCaretTest {
    @Test fun agentChipPutsCaretAfterTheNameNotAtTheStart() {
        val edit = ComposerCaret.insertMention("", "grok")
        assertEquals("@grok ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
        assertNotEquals(0, edit.cursor)
        assertTrue(edit.cursor > edit.text.indexOf("grok"))
        assertEquals(edit.cursor, ComposerCaret.caretAfterExternalEdit(edit.text))
    }

    @Test fun agentChipAppendsAfterExistingTextAndKeepsCaretBehindTheName() {
        val edit = ComposerCaret.insertMention("안녕", "gemini")
        assertEquals("안녕 @gemini ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
        assertTrue(edit.text.substring(0, edit.cursor).endsWith("@gemini "))
    }

    @Test fun agentChipDoesNotInsertASecondSpace() {
        val edit = ComposerCaret.insertMention("안녕 ", "grok")
        assertEquals("안녕 @grok ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
    }
}
