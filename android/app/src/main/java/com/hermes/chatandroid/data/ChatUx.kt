package com.hermes.chatandroid.data

/**
 * 채팅 화면의 순수 판정. UI와 서버를 부르지 않는다.
 * 서버는 채널에서 멘션이 없으면 job_id 없이 메시지만 저장하고, 받은 봇이 없다는 오류는 주지 않는다.
 */
object ChatUx {
    private val mention = Regex("(^|\\s)@(\\S+)")

    fun hasMention(body: String): Boolean = mention.containsMatchIn(body)

    /** 본문의 첫 @slug. 끝의 문장부호는 슬러그에서 뺀다. */
    fun mentionedSlug(body: String): String? {
        val raw = mention.find(body)?.groupValues?.getOrNull(2) ?: return null
        return raw.trimEnd('.', ',', '!', '?', ':', ';', ')', ']', '…').takeIf { it.isNotEmpty() }
    }

    /**
     * 이 채널 멤버 중 마지막으로 답한 봇. 없으면 null.
     * author 가 슬러그이면 그걸, 아니면 authorBotId 로 멤버를 찾는다.
     */
    fun lastReplierSlug(messages: List<BotMessage>, members: List<BotMember>): String? {
        val byId = members.associateBy { it.id }
        val slugs = members.map { it.slug }.toSet()
        for (m in messages.asReversed()) {
            if (m.role != "bot") continue
            m.author?.takeIf { it in slugs }?.let { return it }
            m.authorBotId?.let { byId[it]?.slug }?.let { return it }
        }
        return null
    }

    /** 수신 봇 고르기 시트의 기본값. 마지막 답장 봇, 아직 없으면 멤버 첫 봇. */
    fun defaultRecipientSlug(messages: List<BotMessage>, members: List<BotMember>): String? {
        if (members.isEmpty()) return null
        return lastReplierSlug(messages, members) ?: members.first().slug
    }

    /** 봇이 둘 이상인 채널에서 멘션이 없으면 전송을 막는다. DM·봇 한 명은 막지 않는다. */
    fun needsRecipientPick(channel: BotChannel?, body: String): Boolean {
        if (channel == null || channel.isDm) return false
        if (channel.members.size < 2) return false
        return !hasMention(body)
    }

    /** 봇이 하나인 채널만 @slug 를 붙인다. 이미 멘션이 있거나 DM 이면 그대로. */
    fun addressSolo(body: String, channel: BotChannel?): String {
        val only = channel?.takeIf { !it.isDm }?.members?.singleOrNull() ?: return body
        return if (hasMention(body)) body else "@${only.slug} $body".trim()
    }

    /** 고른 봇에게 보내도록 멘션을 앞에 붙인다. 이미 멘션이 있으면 그대로. */
    fun addressTo(body: String, slug: String): String {
        if (hasMention(body) || slug.isBlank()) return body
        return "@$slug $body".trim()
    }

    /** 봇이 한 명일 때 화면에 보여줄 수신자. DM 은 그 봇, 채널은 유일한 멤버. */
    fun soloRecipientSlug(dm: Bot?, channel: BotChannel?): String? = when {
        dm != null -> dm.slug
        channel != null && !channel.isDm && channel.members.size == 1 -> channel.members[0].slug
        else -> null
    }
}
