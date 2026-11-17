package com.hermes.chatandroid.net

/**
 * 봇 채팅 첨부 이미지 원본. Hermes-bot 서버에는 첨부 다운로드 경로가 없어서, 프로필 uploads 로 올라간 파일을
 * Bridge `GET /files/raw` 로 받는다. 받은 바이트는 경로별로 메모리에 둔다(최근 사용 순, [maxBytes] 한도).
 */
class AttachmentImageStore(
    private val bridge: () -> BridgeApi?,
    private val maxBytes: Int = 32 * 1024 * 1024,
    private val botApi: () -> BotApi? = { null },
) {
    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var total = 0

    /** 방금 이 기기에서 올린 파일 — 다시 내려받지 않게 넣어 둔다. */
    @Synchronized
    fun put(path: String, data: ByteArray) {
        cache.remove(path)?.let { total -= it.size }
        if (data.size > maxBytes) return
        cache[path] = data
        total += data.size
        val it = cache.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            total -= it.next().value.size
            it.remove()
        }
    }

    @Synchronized
    private fun cached(path: String): ByteArray? = cache[path]

    /**
     * [path] = 서버가 준 맥미니 절대경로. Bridge 로 먼저 시도하고, Bridge 미설정(게스트 등)이거나
     * 로컬 경로면 [channelId] 경유 Hermes-bot 첨부 다운로드로 폴백한다.
     */
    suspend fun load(path: String, channelId: Int? = null): ByteArray? {
        cached(path)?.let { return it }
        val rel = relativePath(path)
        val bApi = bridge()
        if (rel != null && bApi != null) {
            return bApi.fetchRaw(rel).also { put(path, it) }
        }
        val cid = channelId ?: return null
        val bot = botApi() ?: return null
        return bot.fetchChannelAttachment(cid, path).also { put(path, it) }
    }

    companion object {
        private const val HOME_MARK = "/.hermes/"

        /** "/Users/x/.hermes/uploads/a.jpg" → "uploads/a.jpg" (HERMES_HOME 기준). 그 밖의 경로는 null. */
        fun relativePath(path: String): String? {
            val i = path.indexOf(HOME_MARK)
            if (i < 0) return null
            return path.substring(i + HOME_MARK.length).takeIf { it.isNotBlank() }
        }
    }
}
