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
    fun needsRecipientPick(channel: BotChannel?, body: String, isGuest: Boolean = false): Boolean {
        if (channel == null || channel.isDm) return false
        if (isGuest && channel.guestBotMode == "mention_off") return false
        if (channel.members.size < 2) return false
        return !hasMention(body)
    }

    /** 봇이 하나인 채널만 @slug 를 붙인다. 이미 멘션이 있거나 DM 이면 그대로. */
    fun addressSolo(body: String, channel: BotChannel?, isGuest: Boolean = false): String {
        if (isGuest && channel?.guestBotMode == "mention_off") return body
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

    /**
     * Git 버튼 문구. 브랜치가 비어 "Git" 하나로 뭉뚱그리지 않는다.
     * [loading] 이면 불러오는 중, [failed] 이면 오류, 브랜치가 없으면 브랜치 없음.
     */
    fun gitButtonLabel(loading: Boolean, failed: Boolean, branch: String?): String = when {
        loading -> "불러오는 중"
        failed -> "오류"
        branch.isNullOrBlank() -> "브랜치 없음"
        else -> branch
    }

    /**
     * 경로를 가운데서 줄여 끝 폴더가 보이게 한다. 앞만 남기고 뒤를 자르지 않는다.
     */
    fun middleEllipsisPath(path: String, maxChars: Int = 36): String {
        val clean = path.trim()
        if (maxChars < 2 || clean.length <= maxChars) return clean
        val leaf = clean.trimEnd('/').substringAfterLast('/').ifEmpty { clean }
        val tail = if (clean.length > leaf.length && clean.endsWith(leaf)) "/$leaf" else leaf
        val tailKeep = if (tail.length >= maxChars) tail.takeLast(maxChars - 1) else tail
        val headRoom = maxChars - 1 - tailKeep.length
        val head = if (headRoom > 0) clean.take(headRoom) else ""
        return head + "…" + tailKeep
    }

    /** 아는 글라스 오류만 한국어로. 모르는 문자열은 그대로 둔다. */
    fun glassesErrorText(raw: String): String = when {
        raw.contains("All discovered devices are powered off or disconnected") -> "안경 전원이 꺼졌거나 연결이 끊겼습니다"
        else -> raw
    }

    /**
     * 초대 링크(`https://host/invite/<code>`, `hermes://invite?url=...&code=...`) 또는
     * (서버 주소, 코드) 입력에서 `(normalizedServerUrl, inviteCode)` 를 추출한다.
     */
    fun parseInviteInput(rawUrlOrLink: String, rawCode: String = ""): Pair<String, String>? {
        val input = rawUrlOrLink.trim()
        val explicitCode = rawCode.trim()
        if (input.isEmpty()) return null

        return try {
            val uri = java.net.URI(input)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "hermes" || scheme == "hermeschat") {
                val params = (uri.rawQuery ?: "").split('&').mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null
                    else java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                        java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }.toMap()
                val url = params["url"]?.trim()?.trimEnd('/') ?: return null
                val code = params["code"]?.trim()?.takeIf { it.isNotEmpty() } ?: explicitCode
                if (code.isEmpty()) null else url to code
            } else {
                val path = uri.path ?: ""
                val marker = "/invite/"
                val idx = path.indexOf(marker)
                if (idx >= 0 && uri.host != null) {
                    val code = path.substring(idx + marker.length).trim('/').takeIf { it.isNotEmpty() } ?: explicitCode
                    val origin = buildString {
                        append(scheme ?: "https").append("://").append(uri.host)
                        if (uri.port > 0) append(":").append(uri.port)
                    }
                    if (code.isEmpty()) null else origin to code
                } else {
                    val norm = Settings.normalizeUrl(input, 8900) ?: return null
                    if (explicitCode.isEmpty()) null else norm to explicitCode
                }
            }
        } catch (_: Exception) {
            val norm = Settings.normalizeUrl(input, 8900) ?: return null
            if (explicitCode.isEmpty()) null else norm to explicitCode
        }
    }
}
