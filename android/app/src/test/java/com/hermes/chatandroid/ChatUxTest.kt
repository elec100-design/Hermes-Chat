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

class KanbanLayoutTest {
    @Test fun firstColumnIsRunningOrReady() {
        assertEquals(com.hermes.chatandroid.data.KanbanStatus.RUNNING, com.hermes.chatandroid.ui.kanban.initialBoardColumn(true))
        assertEquals(com.hermes.chatandroid.data.KanbanStatus.READY, com.hermes.chatandroid.ui.kanban.initialBoardColumn(false))
    }
}

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

    @Test fun gitLabelSplitsLoadingMissingAndError() {
        assertEquals("불러오는 중", ChatUx.gitButtonLabel(loading = true, failed = false, branch = ""))
        assertEquals("오류", ChatUx.gitButtonLabel(loading = false, failed = true, branch = null))
        assertEquals("브랜치 없음", ChatUx.gitButtonLabel(loading = false, failed = false, branch = "  "))
        assertEquals("main", ChatUx.gitButtonLabel(loading = false, failed = false, branch = "main"))
    }

    @Test fun glassesErrorMapsOnlyKnownStrings() {
        val known = "All discovered devices are powered off or disconnected"
        assertEquals("안경 전원이 꺼졌거나 연결이 끊겼습니다", ChatUx.glassesErrorText(known))
        assertEquals(
            "안경 전원이 꺼졌거나 연결이 끊겼습니다",
            ChatUx.glassesErrorText("DAT: $known (code 3)"),
        )
        assertEquals("Your glasses need an update", ChatUx.glassesErrorText("Your glasses need an update"))
    }

    @Test fun pathKeepsTheLastFolder() {
        val path = "/Users/macmini/orca/HermesChat"
        val shown = ChatUx.middleEllipsisPath(path, 24)
        assertTrue(shown.endsWith("HermesChat"))
        assertTrue(shown.contains("…"))
        assertTrue(shown.length <= 24)
        assertEquals(path, ChatUx.middleEllipsisPath(path, 80))
    }

    @Test fun parseInviteInputHandlesWebDeepLinkAndManual() {
        assertEquals(
            "https://chat.example.com" to "abc123",
            ChatUx.parseInviteInput("https://chat.example.com/invite/abc123"),
        )
        assertEquals(
            "https://chat.example.com" to "xyz999",
            ChatUx.parseInviteInput("hermes://invite?url=https%3A%2F%2Fchat.example.com&code=xyz999"),
        )
        assertEquals(
            "http://100.83.59.60:8900" to "code42",
            ChatUx.parseInviteInput("100.83.59.60", "code42"),
        )
        assertNull(ChatUx.parseInviteInput("100.83.59.60", ""))
    }

    @Test fun guestMentionOffSkipsRecipientPickerAndAutoSlug() {
        val ch = channel(member(1, "opus"), member(2, "grok")).copy(guestBotMode = "mention_off")
        assertFalse(ChatUx.needsRecipientPick(ch, "사람끼리 대화", isGuest = true))
        assertTrue(ChatUx.needsRecipientPick(ch, "오너는 여전히 멘션 필요", isGuest = false))
        assertFalse(ChatUx.needsRecipientPick(ch, "호스트도 게스트와 대화", isGuest = false, hasOtherHumans = true))
        val solo = channel(member(1, "opus")).copy(guestBotMode = "mention_off")
        assertEquals("안녕", ChatUx.addressSolo("안녕", solo, isGuest = true))
        assertEquals("안녕", ChatUx.addressSolo("안녕", solo, isGuest = false, hasOtherHumans = true))
    }

    @Test fun extractAndReplaceMentionQuerySupportsUnicodeAndEmailGuard() {
        val q1 = ChatUx.extractMentionQuery("안녕 @기")
        assertEquals(3, q1?.atIndex)
        assertEquals("기", q1?.prefix)
        assertEquals("안녕 @테스트 기백 ", ChatUx.replaceMentionQuery("안녕 @기", q1!!, "테스트 기백"))

        val q2 = ChatUx.extractMentionQuery("@")
        assertEquals(0, q2?.atIndex)
        assertEquals("", q2?.prefix)
        assertEquals("@grok ", ChatUx.replaceMentionQuery("@", q2!!, "grok"))

        assertNull(ChatUx.extractMentionQuery("foo@bar"))
    }

    @Test fun botMessageIsSentByMeSplitsOwnerAndOtherHuman() {
        val mine = BotMessage(id = 1, role = "user", authorUserId = 5, isMine = true)
        val other = BotMessage(id = 2, role = "user", authorUserId = 1, isMine = false)
        val bot = BotMessage(id = 3, role = "bot", authorBotId = 1)
        assertTrue(mine.isSentByMe(5))
        assertFalse(other.isSentByMe(5))
        assertFalse(bot.isSentByMe(5))
    }
}
