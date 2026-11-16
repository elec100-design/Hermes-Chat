package com.hermes.chatandroid

import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.data.BotMember
import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.ChatUx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUxTest {
    private fun member(id: Int, slug: String) = BotMember(id = id, slug = slug, name = slug)
    private fun channel(vararg members: BotMember) = BotChannel(
        id = 1, title = "작업", kind = "channel", members = members.toList(),
    )
    private fun botMsg(author: String, id: Int = 1) = BotMessage(
        id = id, role = "bot", author = author, body = "답",
    )

    @Test fun defaultRecipientIsLastReplierNotFirstMember() {
        val members = listOf(member(1, "opus"), member(2, "grok"))
        val messages = listOf(
            botMsg("opus", 1),
            BotMessage(id = 2, role = "user", author = "나", body = "다음"),
            botMsg("grok", 3),
        )
        assertEquals("grok", ChatUx.defaultRecipientSlug(messages, members))
        assertEquals("grok", ChatUx.lastReplierSlug(messages, members))
    }

    @Test fun defaultRecipientFallsBackToFirstMemberWhenNobodyReplied() {
        val members = listOf(member(1, "opus"), member(2, "grok"))
        assertEquals("opus", ChatUx.defaultRecipientSlug(emptyList(), members))
        assertNull(ChatUx.lastReplierSlug(emptyList(), members))
    }

    @Test fun defaultRecipientIgnoresNonMembersAndUsesAuthorBotId() {
        val members = listOf(member(7, "wiki"))
        val messages = listOf(
            BotMessage(id = 1, role = "bot", author = "outsider", authorBotId = 7, body = "답"),
        )
        assertEquals("wiki", ChatUx.lastReplierSlug(messages, members))
    }

    @Test fun multiBotWithoutMentionIsBlocked() {
        val ch = channel(member(1, "opus"), member(2, "grok"))
        assertTrue(ChatUx.needsRecipientPick(ch, "그냥 메모"))
        assertFalse(ChatUx.needsRecipientPick(ch, "@grok 정리해"))
        assertFalse(ChatUx.needsRecipientPick(ch.copy(kind = "dm", members = listOf(member(1, "opus"))), "안녕"))
        assertFalse(ChatUx.needsRecipientPick(channel(member(1, "opus")), "안녕"))
    }

    @Test fun soloChannelKeepsAutoSlug() {
        val ch = channel(member(1, "opus"))
        assertEquals("@opus 안녕", ChatUx.addressSolo("안녕", ch))
        assertEquals("@grok 이미", ChatUx.addressSolo("@grok 이미", ch))
        assertEquals("안녕", ChatUx.addressSolo("안녕", ch.copy(kind = "dm")))
        assertEquals("@grok 안녕", ChatUx.addressTo("안녕", "grok"))
    }

    @Test fun soloRecipientVisibleOnlyForOneBot() {
        val dm = Bot(id = 1, slug = "grok", name = "그록")
        assertEquals("grok", ChatUx.soloRecipientSlug(dm, null))
        assertEquals("opus", ChatUx.soloRecipientSlug(null, channel(member(1, "opus"))))
        assertNull(ChatUx.soloRecipientSlug(null, channel(member(1, "opus"), member(2, "grok"))))
    }
}
