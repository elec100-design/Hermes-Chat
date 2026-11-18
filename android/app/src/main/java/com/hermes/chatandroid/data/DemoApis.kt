package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.BotApi
import com.hermes.chatandroid.net.BridgeApi
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.net.HermesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 공백 단위로 쪼개되 공백을 보존해 이어 붙이면 원문이 되는 조각들. */
internal fun wordChunks(text: String): List<String> =
    Regex("\\S+\\s*|\\s+").findAll(text).map { it.value }.toList()

/** 데모 모드 게이트웨이 페이크. 네트워크 호출 없음. 스트림은 단어 단위 지연 방출이며 수집 취소 시 중단된다. */
class DemoHermesApi(private val wordDelayMs: Long = 40L) : HermesApi("", "") {
    private val sessions = DemoData.sessions().toMutableList()
    private val messages = DemoData.sessions().associate { it.id to DemoData.messages(it.id).toMutableList() }.toMutableMap()
    private var seq = 0
    private var replyIndex = 0

    override suspend fun health() = true
    override suspend fun fetchModelIds() = listOf("demo-model")

    override suspend fun fetchSessions(limit: Int, offset: Int): SessionPage {
        val all = sessions.sortedByDescending { it.updatedAt }
        return SessionPage(all.drop(offset).take(limit), hasMore = offset + limit < all.size)
    }

    override suspend fun createSession(title: String?): Session {
        val s = Session("demo-new-${++seq}", title, null, System.currentTimeMillis() / 1000.0, "default")
        sessions.add(s)
        messages[s.id] = mutableListOf()
        return s
    }

    override suspend fun fetchMessages(sessionId: String): List<ChatMessage> = messages[sessionId].orEmpty().toList()

    override suspend fun renameSession(sessionId: String, title: String) {
        val i = sessions.indexOfFirst { it.id == sessionId }
        if (i >= 0) sessions[i] = sessions[i].copy(title = title)
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.removeAll { it.id == sessionId }
        messages.remove(sessionId)
    }

    override fun streamChat(sessionId: String, message: String, attachments: List<String>): Flow<StreamEvent> = flow {
        val now = System.currentTimeMillis() / 1000.0
        val list = messages.getOrPut(sessionId) { mutableListOf() }
        list.add(ChatMessage("u-${++seq}", "user", message, now))
        val reply = DemoData.scriptedResponses[replyIndex++ % DemoData.scriptedResponses.size]
        for (chunk in wordChunks(reply)) {
            delay(wordDelayMs)
            emit(StreamEvent.Delta(chunk))
        }
        // 취소되면 여기까지 오지 않으므로 중단된 답변은 기록에 남지 않는다.
        list.add(ChatMessage("a-${++seq}", "assistant", reply, System.currentTimeMillis() / 1000.0))
        emit(StreamEvent.Completed(reply))
        emit(StreamEvent.Done)
    }

    override suspend fun pollForMissedReply(sessionId: String, expectedUserCount: Int, deadlineMs: Long): String? = null
}

class DemoBridgeApi : BridgeApi("", "") {
    override suspend fun fetchProfiles() = DemoData.profiles.map { BridgeProfile(it.name, it.port) }
    override suspend fun upload(bytes: ByteArray, filename: String, profile: String) = "/demo/uploads/$filename"
    override suspend fun fetchRaw(relativePath: String): ByteArray = throw java.io.IOException("데모 모드에는 파일이 없습니다")

    override suspend fun fetchUsage(refresh: Boolean) = listOf(
        UsageSource(
            "claude:demo", "claude", "Claude", "demo@example.com", java.time.Instant.now().toString(),
            UsageReport(
                available = true, planLabel = "Pro",
                windows = listOf(
                    UsageWindow("Session", 17, java.time.Instant.now().plusSeconds(9_000).toString(), "ok"),
                    UsageWindow("Weekly", 62, java.time.Instant.now().plusSeconds(400_000).toString(), "warning"),
                ),
            ),
        ),
        UsageSource("grok:demo", "grok", "Grok", report = UsageReport(available = false, problemStatus = 401, problemKind = "rejected")),
    )

    override suspend fun fetchGitStatus(path: String) = GitStatus(
        branch = "feature/demo", dirty = true, files = listOf("README.md"), hasUpstream = true, ahead = 1,
        hasRemote = true, defaultBranch = "main",
    )

    override suspend fun gitAction(path: String, action: GitAction, message: String?) =
        GitResult("데모 모드 — 실제로 실행하지 않았습니다 (${action.label})")

    // 칸반·크론: 둘러보기용 읽기 전용 샘플 (쓰기는 무시)
    private val demoTasks = listOf(
        KanbanTask("t_demo1", "주간 안전 점검 보고서 초안", "현장 사진 12장 정리 후 요약", KanbanStatus.RUNNING, "safety"),
        KanbanTask("t_demo2", "위키 링크 깨짐 점검", "", KanbanStatus.READY, "wiki"),
        KanbanTask("t_demo3", "포트폴리오 리밸런싱 검토", "", KanbanStatus.BLOCKED, "invest", error = "시세 API 응답 없음"),
        KanbanTask("t_demo4", "논문 3편 요약", "", KanbanStatus.DONE, "opus", result = "요약 3건을 위키에 저장"),
    )

    override suspend fun fetchKanbanBoards() =
        listOf(KanbanBoardSummary("default", "Default", demoTasks.groupingBy { it.status.key }.eachCount()))

    override suspend fun fetchKanbanBoard(board: String) = KanbanBoard("default", "Default", demoTasks)

    override suspend fun fetchKanbanTask(board: String, taskId: String) =
        KanbanTaskDetail(demoTasks.first { it.id == taskId }, emptyList(), emptyList())

    override suspend fun createKanbanTask(board: String, title: String, detail: String, assignee: String, mode: KanbanCreateMode) {}
    override suspend fun createKanbanBoard(name: String) {}
    override suspend fun kanbanAction(board: String, taskId: String, action: String, reason: String?) {}

    override suspend fun fetchCronJobs(profile: String) = when (profile) {
        "default" -> listOf(
            CronJob("c_demo1", "default", "아침 브리핑", "오늘 일정과 날씨를 요약해 보내줘", "0 7 * * *", "telegram",
                nextRunAt = "2026-11-16T07:00:00+09:00", lastRunAt = "2026-11-15T07:00:40+09:00", lastStatus = "ok"),
            CronJob("c_demo2", "default", "주간 회고", "이번 주 작업을 정리해줘", "0 18 * * 5", "origin", enabled = false, state = "paused"),
        )
        else -> emptyList()
    }

    override suspend fun createCronJob(form: CronForm) {}
    override suspend fun updateCronJob(profile: String, jobId: String, old: CronForm, new: CronForm) {}
    override suspend fun setCronEnabled(profile: String, jobId: String, enabled: Boolean) {}
    override suspend fun runCronJob(profile: String, jobId: String) {}
    override suspend fun deleteCronJob(profile: String, jobId: String) {}
}

/** 데모 모드 봇 서버 페이크: 봇/채널/메시지/승인/작업 위치를 메모리에 유지. */
class DemoBotApi(private val wordDelayMs: Long = 40L) : BotApi("", "") {
    private val bots = DemoData.bots.toMutableList()
    private val channels = DemoData.channels.toMutableList()
    private val messages = (DemoData.channels.map { it.id } + 0).associateWith { DemoData.botMessages(it).toMutableList() }.toMutableMap()
    private val approvals = DemoData.approvals().toMutableList()
    private val workspaces = DemoData.workspaces.toMutableList()
    private val jobs = HashMap<Int, Pair<Int, String>>() // jobId -> (messageId, 최종 본문)
    private var nextId = 5000
    private var replyIndex = 0

    override suspend fun health() = true

    override suspend fun fetchMe() = BotUserInfo(1, "나", "owner")

    override suspend fun acceptInvite(code: String, displayName: String) =
        throw ApiException(400, "데모 모드에서는 초대 링크를 사용할 수 없습니다")

    override suspend fun createChannelInvite(channelId: Int, label: String, maxUses: Int?, expiresHours: Int?) =
        ChannelInvite(nextId++, channelId, label, "demo-invite", "https://demo.invalid/invite/demo", "hermes://invite?code=demo", maxUses, 0)

    override suspend fun fetchChannelInvites(channelId: Int): List<ChannelInvite> = emptyList()
    override suspend fun revokeChannelInvite(channelId: Int, inviteId: Int) {}
    override suspend fun fetchChannelUsers(channelId: Int): List<ChannelHumanMember> =
        listOf(ChannelHumanMember(1, "나", "owner"))
    override suspend fun removeChannelUser(channelId: Int, userId: Int) {}
    override suspend fun fetchChannelAttachment(channelId: Int, path: String): ByteArray =
        throw ApiException(404, "데모 모드에는 첨부 파일이 없습니다")
    override fun channelStream(channelId: Int): Flow<ChannelStreamEvent> = kotlinx.coroutines.flow.emptyFlow()

    /** 실제 서버처럼 DM 은 목록에 없다. */
    override suspend fun fetchChannels(): List<BotChannel> = channels.filter { !it.isDm && it.status != "archived" }

    override suspend fun fetchBots(activeOnly: Boolean): List<Bot> =
        if (activeOnly) bots.filter { it.status == "active" } else bots.toList()

    override suspend fun fetchMessages(channelId: Int): List<BotMessage> = messages[channelId].orEmpty().toList()

    override suspend fun postMessage(channelId: Int, body: String, attachments: List<BotAttachment>, workdir: String?): BotPostResult {
        val list = messages.getOrPut(channelId) { mutableListOf() }
        val userId = nextId++
        val attJson = if (attachments.isEmpty()) null else
            attachments.joinToString(",", "[", "]") { """{"name":"${it.name}","path":"${it.path}"}""" }
        list.add(BotMessage(userId, channelId, list.size + 1, "user", "나", body.ifEmpty { "(첨부만)" }, "done", attachments = attJson))
        val bot = channels.firstOrNull { it.id == channelId }?.members?.firstOrNull()
            ?: return BotPostResult(messageId = userId, error = "채널에 봇이 없습니다")
        val replyId = nextId++
        val jobId = nextId++
        val text = DemoData.botReplies[replyIndex++ % DemoData.botReplies.size]
        list.add(
            BotMessage(
                replyId, channelId, list.size + 1, "bot", bot.slug, "", "running",
                jobId = jobId, authorBotId = bot.id, displayName = bot.displayName, avatar = bot.avatar, tone = bot.tone,
            ),
        )
        jobs[jobId] = replyId to text
        return BotPostResult(messageId = userId, replyMessageId = replyId, jobId = jobId, bot = bot.slug)
    }

    /** 데모에는 진행자가 조율할 실제 봇이 없다. */
    override suspend fun startDiscussion(channelId: Int, request: DiscussionRequest): Int =
        throw ApiException(400, "데모 모드에서는 토론을 열 수 없습니다")

    override suspend fun createChannel(title: String, topic: String?, botIds: List<Int>, workdir: String?, botChat: Boolean?): BotChannel {
        val ch = BotChannel(
            nextId++, null, title, topic, botChat ?: true, "active", null,
            bots.filter { it.id in botIds }.map { BotMember(it.id, it.slug, it.name, it.avatar, it.health, it.tone) },
            kind = "channel", workdir = workdir,
        )
        channels.add(ch)
        messages[ch.id] = mutableListOf()
        return ch
    }

    // ---- 봇 관리 ----

    override suspend fun updateBot(id: Int, patch: BotPatch): Bot {
        val i = bots.indexOfFirst { it.id == id }
        if (i < 0) throw ApiException(404, "봇을 찾을 수 없습니다")
        val b = bots[i]
        val next = b.copy(
            name = patch.name ?: b.name,
            avatar = patch.avatar ?: b.avatar,
            icon = patch.icon ?: b.icon,
            personaNote = patch.personaNote ?: b.personaNote,
            workdir = patch.workdir ?: b.workdir,
            readOnly = patch.readOnly ?: b.readOnly,
            approvalPolicy = patch.approvalPolicy ?: b.approvalPolicy,
            computerUse = patch.computerUse ?: b.computerUse,
            status = patch.status ?: b.status,
        )
        bots[i] = next
        return next
    }

    override suspend fun discoverBots() = DiscoverResult("demo", found = bots.size, updated = bots.size)
    override suspend fun refreshHealth() = BotHealthSummary(bots.count { it.isOnline }, bots.size)

    override suspend fun fetchSteps(messageId: Int): List<BotStep> = listOf(
        BotStep(1, messageId, null, 1, "tool", "read_file", "notes.md", "done"),
        BotStep(2, messageId, null, 2, "tool_result", "read_file", "(데모) 파일 내용", "done"),
        BotStep(3, messageId, null, 3, "note", "정리", "요약을 작성했습니다.", "done"),
    )

    override suspend fun uploadAttachment(channelId: Int, filename: String, bytes: ByteArray, mime: String?) =
        BotAttachment(filename, "/demo/uploads/$filename", mime, bytes.size.toLong(), "local")

    // ---- 채널 관리 ----

    private fun channelIndex(id: Int): Int =
        channels.indexOfFirst { it.id == id }.also { if (it < 0) throw ApiException(404, "채널을 찾을 수 없습니다") }

    override suspend fun updateChannel(id: Int, patch: ChannelPatch): BotChannel {
        val i = channelIndex(id)
        val c = channels[i]
        val next = c.copy(
            title = patch.title ?: c.title,
            topic = patch.topic ?: c.topic,
            workdir = patch.workdir ?: c.workdir,
            botChat = patch.botChat ?: c.botChat,
            status = patch.status ?: c.status,
        )
        channels[i] = next
        return next
    }

    override suspend fun addChannelMember(channelId: Int, botId: Int) {
        val i = channelIndex(channelId)
        val b = bots.firstOrNull { it.id == botId } ?: throw ApiException(404, "봇을 찾을 수 없습니다")
        val c = channels[i]
        if (c.members.none { it.id == botId }) {
            channels[i] = c.copy(members = c.members + BotMember(b.id, b.slug, b.name, b.avatar, b.health, b.tone))
        }
    }

    override suspend fun removeChannelMember(channelId: Int, botId: Int) {
        val i = channelIndex(channelId)
        channels[i] = channels[i].let { c -> c.copy(members = c.members.filter { it.id != botId }) }
    }

    override suspend fun deleteChannel(channelId: Int) {
        val i = channelIndex(channelId)
        if (channels[i].isDm) throw ApiException(400, "봇 전용 대화창은 지울 수 없습니다. 봇을 보관하세요.")
        channels.removeAt(i)
        messages.remove(channelId)
    }

    override suspend fun resetChannelContext(channelId: Int) {
        val list = messages.getOrPut(channelId) { mutableListOf() }
        list.add(BotMessage(nextId++, channelId, list.size + 1, "system", "system", "새 대화를 시작했습니다 (이전 맥락과 분리됨)", "done"))
    }

    // ---- 작업 위치 ----

    override suspend fun fetchWorkspaces(): List<Workspace> = workspaces.toList()

    override suspend fun browseWorkspaces(path: String?): BrowseResult {
        val root = "/Users/demo"
        val cur = path?.takeIf { it.isNotEmpty() } ?: root
        if (!cur.startsWith(root)) throw ApiException(400, "허용된 범위 밖의 경로입니다")
        val entries = if (cur == root) listOf("Documents", "repos", "Projects").map { BrowseEntry(it, "$root/$it") } else emptyList()
        return BrowseResult(cur, if (cur == root) null else cur.substringBeforeLast('/'), entries)
    }

    override suspend fun addWorkspaceFolder(path: String, label: String?): Workspace {
        if (workspaces.any { it.path == path }) throw ApiException(400, "이미 등록된 폴더입니다")
        return Workspace(nextId++, "folder", label?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'), path).also { workspaces.add(it) }
    }

    override suspend fun cloneWorkspace(repo: String, branch: String?, label: String?): Workspace {
        val name = repo.trim().removeSuffix(".git").substringAfter("github.com/").trim('/')
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(name)) throw ApiException(400, "owner/repo 형식이나 GitHub URL 을 입력하세요")
        return Workspace(nextId++, "repo", label?.takeIf { it.isNotBlank() } ?: name, "/Users/demo/repos/$name",
            "https://github.com/$name", branch ?: "main").also { workspaces.add(it) }
    }

    override suspend fun removeWorkspace(id: Int): String? {
        if (!workspaces.removeAll { it.id == id }) throw ApiException(404, "작업 위치를 찾을 수 없습니다")
        return "목록에서만 제거했습니다. 폴더는 그대로입니다."
    }

    override suspend fun fetchWorkspaceStatus(id: Int): WorkspaceStatus {
        val w = workspaces.firstOrNull { it.id == id } ?: throw ApiException(404, "작업 위치를 찾을 수 없습니다")
        return if (w.isRepo) WorkspaceStatus(true, w.defaultBranch ?: "main", true, listOf("README.md")) else WorkspaceStatus(false)
    }

    override suspend fun commitWorkspace(id: Int, message: String): String? {
        if (message.isBlank()) throw ApiException(400, "커밋 메시지가 필요합니다")
        return "demo" + nextId++
    }

    override suspend fun requestWorkspacePush(id: Int, branch: String?, botId: Int?): PushRequestResult {
        val w = workspaces.firstOrNull { it.id == id } ?: throw ApiException(404, "작업 위치를 찾을 수 없습니다")
        val aid = nextId++
        approvals.add(
            BotApproval(aid, "git_push", "${w.label} → ${branch ?: w.defaultBranch ?: "main"} 푸시", "데모 푸시 요청",
                "git push origin ${branch ?: "main"}", "되돌릴 수 없습니다.", "pending", botId),
        )
        return PushRequestResult(aid, "pending", "승인 인박스에서 확인 후 실행됩니다.")
    }

    override suspend fun fetchApprovals(status: String): List<BotApproval> = approvals.filter { it.status == status }
    override suspend fun approve(id: Int, note: String?) = decide(id, "approved")
    override suspend fun reject(id: Int, note: String?) = decide(id, "rejected")

    private fun decide(id: Int, status: String) {
        val i = approvals.indexOfFirst { it.id == id }
        if (i >= 0) approvals[i] = approvals[i].copy(status = status)
    }

    /** 구독마다 처음부터 재생. 끝에 메시지를 done 으로 확정한 뒤 End 를 보낸다. */
    override fun jobStream(jobId: Int): Flow<BotStreamEvent> = flow {
        val (messageId, text) = jobs[jobId] ?: run { emit(BotStreamEvent.End("failed", "알 수 없는 작업")); return@flow }
        for (chunk in wordChunks(text)) {
            delay(wordDelayMs)
            emit(BotStreamEvent.Delta(chunk))
        }
        for (list in messages.values) {
            val i = list.indexOfFirst { it.id == messageId }
            if (i >= 0) list[i] = list[i].copy(body = text, status = "done")
        }
        emit(BotStreamEvent.End("done", null))
    }
}
