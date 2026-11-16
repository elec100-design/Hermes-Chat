package com.hermes.chatandroid.ui.bots

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.live.LiveTarget
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.BotPatch
import com.hermes.chatandroid.data.ChannelPatch
import com.hermes.chatandroid.data.Discussion
import com.hermes.chatandroid.data.DiscussionRequest
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.data.Workspace
import com.hermes.chatandroid.net.AttachmentImageStore
import com.hermes.chatandroid.data.BotStep
import com.hermes.chatandroid.ui.bots.chat.BotAttachmentChips
import com.hermes.chatandroid.ui.bots.chat.BotStepsSection
import com.hermes.chatandroid.ui.bots.chat.LiveBar
import com.hermes.chatandroid.ui.bots.chat.LiveButton
import com.hermes.chatandroid.ui.bots.chat.WorkspaceButton
import com.hermes.chatandroid.ui.bots.chat.WorkspacePickerSheet
import com.hermes.chatandroid.ui.bots.chat.DiscussionBar
import com.hermes.chatandroid.ui.bots.chat.DiscussionButton
import com.hermes.chatandroid.ui.bots.chat.DiscussionKindLabel
import com.hermes.chatandroid.ui.bots.chat.DiscussionSheet
import com.hermes.chatandroid.ui.bots.chat.PendingBotFile
import com.hermes.chatandroid.ui.bots.chat.TypingDots
import com.hermes.chatandroid.ui.bots.chat.readPendingFile
import com.hermes.chatandroid.ui.components.ChatComposerDock
import com.hermes.chatandroid.ui.components.ComposerMaxWidth
import com.hermes.chatandroid.ui.components.isWideWindow
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.MessageBubble
import com.hermes.chatandroid.ui.theme.hermesColors
import com.hermes.chatandroid.ui.workspaces.GitMenuButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BotChatUiState(
    val loading: Boolean = true,
    val channel: BotChannel? = null,
    /** 이 채널이 봇 DM 이면 그 봇(`/api/channels` 목록에는 DM 이 없어 봇 목록에서 찾는다). */
    val dmBot: Bot? = null,
    val messages: List<BotMessage> = emptyList(),
    /** messageId → 스트리밍 중 누적 본문 */
    val streaming: Map<Int, String> = emptyMap(),
    val streamErrors: Map<Int, String> = emptyMap(),
    val sending: Boolean = false,
    val pending: List<PendingBotFile> = emptyList(),
    /** messageId → 실행 단계(라이브는 스냅샷 통째 교체, 완료 메시지는 펼칠 때 로드). */
    val steps: Map<Int, List<BotStep>> = emptyMap(),
    val error: String? = null,
    val info: String? = null,
    /** 작업 폴더 선택 시트용 등록 워크스페이스. null = 아직 안 불러옴. */
    val workspaces: List<Workspace>? = null,
    /** 이 채널의 가장 최근 토론(없으면 null). 진행 중이면 입력 카드 위에 진행 줄이 뜬다. */
    val discussion: Discussion? = null,
    val startingDiscussion: Boolean = false,
) {
    /** 이 대화에 지정된 작업 폴더(DM = 봇 기본 폴더, 채널 = 채널 폴더). */
    val workdir: String? get() = (dmBot?.workdir ?: channel?.workdir)?.takeIf { it.isNotBlank() }
}

/**
 * 봇이 하나뿐인 채널(작업 채팅)은 멘션 없이도 그 봇이 답하게 `@slug` 를 붙인다 —
 * 서버는 채널에서 멘션이 없으면 아무도 답하지 않는다. 이미 멘션이 있거나 봇이 둘 이상이면 그대로.
 */
internal fun soloMention(body: String, channel: BotChannel?): String {
    val only = channel?.takeIf { !it.isDm }?.members?.singleOrNull() ?: return body
    return if (Regex("(^|\\s)@\\S").containsMatchIn(body)) body else "@${only.slug} $body".trim()
}

class BotChatViewModel(private val container: AppContainer, private val channelId: Int) : ViewModel() {
    private val _state = MutableStateFlow(BotChatUiState())
    val state = _state.asStateFlow()

    private val follower = BotJobFollower(
        scope = viewModelScope,
        streamOf = { jobId -> container.botApi()?.jobStream(jobId) ?: emptyFlow() },
        reload = { container.botApi()!!.fetchMessages(channelId) },
        onMessages = { list ->
            container.notifier.markSeen(channelId, list)
            _state.update { s ->
                val sorted = list.sortedWith(compareBy({ it.seq ?: Int.MAX_VALUE }, { it.id }))
                val inFlight = sorted.filter { it.isInFlight }.map { it.id }.toSet()
                // 최종본이 된 메시지의 스트리밍 버퍼는 제거
                s.copy(messages = sorted, streaming = s.streaming.filterKeys { it in inFlight })
            }
            refreshDiscussion(list)
        },
        onReset = { id -> _state.update { it.copy(streaming = it.streaming + (id to ""), streamErrors = it.streamErrors - id) } },
        onDelta = { id, t -> _state.update { it.copy(streaming = it.streaming + (id to (it.streaming[id].orEmpty() + t))) } },
        onStreamError = { id, m -> _state.update { it.copy(streamErrors = it.streamErrors + (id to m)) } },
        onReloadError = { e -> _state.update { it.copy(error = botErrorText(e)) } },
        onSteps = { id, st -> _state.update { it.copy(steps = it.steps + (id to st)) } },
    )

    private var nextFileId = 0L
    private val loadingSteps = HashSet<Int>()

    fun addAttachments(resolver: ContentResolver, uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    val f = withContext(Dispatchers.IO) { readPendingFile(resolver, uri, nextFileId++) }
                    _state.update { it.copy(pending = it.pending + f) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "첨부 파일을 읽지 못했습니다") }
                }
            }
        }
    }

    fun removeAttachment(id: Long) = _state.update { s -> s.copy(pending = s.pending.filter { it.id != id }) }

    /** 완료된 메시지의 단계를 한 번만 가져온다(라이브로 이미 있으면 생략). */
    fun loadSteps(messageId: Int) {
        val api = container.botApi() ?: return
        if (_state.value.steps.containsKey(messageId) || !loadingSteps.add(messageId)) return
        viewModelScope.launch {
            try {
                val st = api.fetchSteps(messageId)
                _state.update { it.copy(steps = it.steps + (messageId to st)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            } finally {
                loadingSteps.remove(messageId)
            }
        }
    }

    /** Live 가 이 채널에 발화를 올렸다 — 목록을 새로 읽고 진행 중인 답을 따라간다. */
    fun onLivePosted() {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                follower.submit(api.fetchMessages(channelId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }

    fun loadWorkspaces() {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                val list = api.fetchWorkspaces()
                _state.update { it.copy(workspaces = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(workspaces = emptyList(), error = botErrorText(e)) }
            }
        }
    }

    /** 작업 폴더 지정([path] "" = 해제). DM 은 그 봇의 기본 폴더, 채널은 채널 폴더로 서버에 저장한다. */
    fun setWorkdir(path: String) {
        val api = container.botApi() ?: return
        val s = _state.value
        viewModelScope.launch {
            try {
                when {
                    s.dmBot != null -> {
                        api.updateBot(s.dmBot.id, BotPatch(workdir = path))
                        _state.update { it.copy(dmBot = it.dmBot?.copy(workdir = path.ifEmpty { null })) }
                    }
                    s.channel != null -> {
                        api.updateChannel(channelId, ChannelPatch(workdir = path))
                        _state.update { it.copy(channel = it.channel?.copy(workdir = path.ifEmpty { null })) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }

    /**
     * 채널에서 보낼 때 메시지에 실을 작업 폴더. 서버는 봇 기본 폴더를 채널 폴더보다 앞세우므로,
     * 채널에서 고른 폴더가 실제로 쓰이게 메시지 단위로 넘긴다. 서버가 등록된 위치만 받으므로 목록에 있을 때만.
     */
    private fun channelWorkdirForSend(): String? {
        val s = _state.value
        val wd = s.channel?.workdir?.takeIf { it.isNotBlank() } ?: return null
        return wd.takeIf { s.workspaces?.any { ws -> wd == ws.path || wd.startsWith(ws.path.trimEnd('/') + "/") } == true }
    }

    init { load() }

    fun load() {
        val api = container.botApi()
        if (api == null) {
            _state.update { it.copy(loading = false, error = "봇 서버가 설정되지 않았습니다.") }
            return
        }
        viewModelScope.launch {
            try {
                val ch = runCatching { api.fetchChannels().firstOrNull { it.id == channelId } }.getOrNull()
                val dm = if (ch != null) null else runCatching { api.fetchBots(false).firstOrNull { it.dmChannelId == channelId } }.getOrNull()
                val msgs = api.fetchMessages(channelId)
                _state.update { it.copy(loading = false, channel = ch ?: it.channel, dmBot = dm ?: it.dmBot, error = null) }
                if (!ch?.workdir.isNullOrBlank() && _state.value.workspaces == null) loadWorkspaces()
                if (!container.settingsRepository.current.demoMode) {
                    container.onChatOpened?.invoke(channelId, dm?.displayName ?: ch?.let { "#${it.title}" } ?: "대화", dm)
                }
                follower.submit(msgs)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = botErrorText(e)) }
            }
        }
    }

    fun send(text: String, onSent: () -> Unit) {
        val api = container.botApi() ?: return
        val body = text.trim()
        val files = _state.value.pending
        // 서버는 본문이 비어도 첨부가 있으면 받는다(main.py api_post_message).
        if ((body.isEmpty() && files.isEmpty()) || _state.value.sending) return
        _state.update { it.copy(sending = true, info = null, error = null) }
        viewModelScope.launch {
            try {
                val uploaded = withContext(Dispatchers.IO) {
                    files.map { api.uploadAttachment(channelId, it.name, it.bytes, it.mime) }
                }
                val r = api.postMessage(channelId, soloMention(body, _state.value.channel), uploaded, workdir = channelWorkdirForSend())
                _state.update { it.copy(pending = emptyList()) }
                onSent()
                _state.value.dmBot?.let { container.touchBotActivity(it.id) }
                val msgs = api.fetchMessages(channelId)
                follower.submit(msgs)
                if (r.jobId != null && r.replyMessageId != null) follower.follow(r.replyMessageId, r.jobId)
                val info = when {
                    r.error != null -> r.error
                    r.jobId == null -> {
                        val slug = _state.value.channel?.members?.firstOrNull()?.slug
                        "@${slug ?: "봇이름"} 으로 멘션해야 답합니다"
                    }
                    else -> null
                }
                _state.update { it.copy(sending = false, info = info) }
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, error = botErrorText(e)) }
            }
        }
    }

    /**
     * 메시지에 토론 발화가 있으면 그 토론의 상태(발언 횟수·진행 여부)를 다시 읽는다.
     * 잡이 끝날 때마다 목록을 새로 읽으므로 그때마다 불린다. 이미 끝난 토론은 다시 묻지 않는다.
     */
    private fun refreshDiscussion(messages: List<BotMessage>) {
        val api = container.botApi() ?: return
        val id = messages.lastOrNull { it.discussionId != null }?.discussionId
        val cur = _state.value.discussion
        if (id == null) {
            if (cur != null) _state.update { it.copy(discussion = null) }
            return
        }
        if (cur?.id == id && !cur.isRunning) return
        viewModelScope.launch {
            try {
                val d = api.fetchDiscussion(id)
                _state.update { it.copy(discussion = d) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 구버전 서버(P32 미만)에는 이 경로가 없다 — 발언 자체는 보이므로 조용히 넘어간다.
            }
        }
    }

    /** 토론을 연다. [onResult] 는 성공이면 null, 서버가 거절하면 그 이유. */
    fun startDiscussion(request: DiscussionRequest, onResult: (String?) -> Unit) {
        val api = container.botApi() ?: return
        if (_state.value.startingDiscussion) return
        _state.update { it.copy(startingDiscussion = true, info = null, error = null) }
        viewModelScope.launch {
            try {
                api.startDiscussion(channelId, request.copy(workdir = channelWorkdirForSend()))
                follower.submit(api.fetchMessages(channelId))
                _state.update { it.copy(startingDiscussion = false) }
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(startingDiscussion = false) }
                onResult(if (e is ApiException && e.code == 404) "봇 서버가 토론을 지원하지 않습니다. Hermes-bot 을 P32 이상으로 올려 주세요." else botErrorText(e))
            }
        }
    }

    fun stopDiscussion() {
        val api = container.botApi() ?: return
        val id = _state.value.discussion?.takeIf { it.isRunning }?.id ?: return
        viewModelScope.launch {
            try {
                val d = api.stopDiscussion(id)
                _state.update { it.copy(discussion = d) }
                follower.submit(api.fetchMessages(channelId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }

    override fun onCleared() {
        follower.cancelAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChatScreen(
    container: AppContainer,
    channelId: Int,
    onBack: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** DM 상단 제목 탭 → 봇 프로필. */
    onOpenBotProfile: (botId: Int) -> Unit = {},
    /** 채널 상단 제목 탭 → 채널 설정. */
    onOpenChannelSettings: (channelId: Int) -> Unit = {},
    /** Live 바의 카메라 버튼(글라스 미연결) → 더보기 > 메타 글라스 · Live. */
    onOpenGlasses: () -> Unit = {},
    /** 작업 폴더 시트의 "워크스페이스 관리". */
    onOpenWorkspaces: () -> Unit = {},
) {
    val vm: BotChatViewModel = viewModel(
        key = "botChat$channelId",
        factory = viewModelFactory { initializer { BotChatViewModel(container, channelId) } },
    )
    LaunchedEffect(channelId) { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember(channelId) { mutableStateOf("") }
    val listState = remember(channelId) { LazyListState() }
    val scope = rememberCoroutineScope()
    val lastLen = Triple(state.messages.size, state.streaming.values.sumOf { it.length }, state.steps.values.sumOf { it.size })
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.addAttachments(context.contentResolver, uris)
    }

    // ---- Live(핸즈프리) — 이 채팅 안에서 켜고 끈다. 대화는 이 채널에 그대로 쌓인다.
    val live = container.live
    val liveUi by live.ui.collectAsStateWithLifecycle()
    val glassesState by live.glasses.state.collectAsStateWithLifecycle()
    val liveHere = liveUi.target?.channelId == channelId
    LaunchedEffect(channelId) {
        live.posted.collect { if (it.channelId == channelId) vm.onLivePosted() }
    }
    var pendingLive by remember(channelId) { mutableStateOf<LiveTarget?>(null) }
    val livePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val t = pendingLive
        pendingLive = null
        if (t != null && isGranted(context, Manifest.permission.RECORD_AUDIO)) live.start(t)
    }
    fun startLive(target: LiveTarget) {
        if (live.active) live.stop()
        val needs = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot { isGranted(context, it) }
        if (needs.isEmpty()) live.start(target)
        else { pendingLive = target; livePermissions.launch(needs.toTypedArray()) }
    }
    var liveMenu by remember(channelId) { mutableStateOf(false) }
    var workspaceSheet by remember(channelId) { mutableStateOf(false) }
    var discussionSheet by remember(channelId) { mutableStateOf(false) }
    var discussionError by remember(channelId) { mutableStateOf<String?>(null) }
    val wide = isWideWindow()
    // 하단 근처일 때만 따라간다(위로 올려 읽는 중이면 방해하지 않음). 첫 로드는 항상 맨 아래.
    val nearBottom by remember(channelId) {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) true
            else {
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= info.totalItemsCount - 2
            }
        }
    }
    var initialScrolled by remember(channelId) { mutableStateOf(false) }
    LaunchedEffect(channelId, lastLen) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!initialScrolled || nearBottom) {
            listState.scrollToItem(state.messages.lastIndex)
            listState.scrollBy(1_000_000f)
            initialScrolled = true
        }
    }

    val members = state.channel?.members.orEmpty()
    val dm = state.dmBot
    val ch = state.channel
    val headerAction: (() -> Unit)? = when {
        dm != null -> { { onOpenBotProfile(dm.id) } }
        ch != null -> { { onOpenChannelSettings(channelId) } }
        else -> null
    }
    val hc = MaterialTheme.hermesColors

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .then(if (headerAction != null) Modifier.clickable(onClick = headerAction) else Modifier)
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            when {
                                dm != null -> BotAvatar(dm.avatarText, dm.tone, size = 36.dp, online = dm.isOnline)
                                ch != null -> ChannelAvatarGroup(ch.members, size = 36.dp)
                            }
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(
                                    dm?.displayName ?: ch?.title ?: "채팅",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = when {
                                    dm != null -> if (dm.isOnline) "온라인 · 탭하여 프로필" else "오프라인 · 탭하여 프로필"
                                    ch != null -> "멤버 ${ch.members.size}명" + (ch.topic?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                                    else -> null
                                }
                                if (subtitle != null) {
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (dm?.isOnline == true) hc.online else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        when {
                            onBack != null -> IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로")
                            }
                            onMenuClick != null -> IconButton(onClick = onMenuClick) {
                                Icon(Icons.Rounded.Menu, contentDescription = "봇 · 채널 목록")
                            }
                        }
                    },
                    actions = {
                        // 작업 폴더가 있는 대화: 커밋·푸시·PR·병합·배포
                        state.workdir?.let { wd -> GitMenuButton(container, wd, showLabel = wide) }
                        if (headerAction != null) {
                            IconButton(onClick = headerAction) {
                                Icon(Icons.Rounded.Tune, contentDescription = "정보 및 설정")
                            }
                        }
                    },
                )
                HorizontalDivider(thickness = 0.7.dp, color = hc.cardBorder)
            }
        },
        bottomBar = {
            ChatComposerDock(
                value = input,
                onValueChange = { input = it },
                onAttach = { picker.launch("*/*") },
                attachEnabled = !state.sending,
                onSend = { vm.send(input) { input = "" } },
                sendEnabled = (input.isNotBlank() || state.pending.isNotEmpty()) && !state.sending,
                sending = state.sending,
                placeholder = if (members.size > 1) "@봇이름 으로 멘션해 대화하세요…" else "메시지를 입력하세요…",
                leadingActions = if (dm != null || ch != null) {
                    {
                        WorkspaceButton(state.workdir, showLabel = wide, onClick = {
                            workspaceSheet = true
                            vm.loadWorkspaces()
                        })
                        // 봇이 둘 이상인 채널: 주제를 던져 토론을 연다(입력 중인 글이 주제로 들어간다).
                        if (members.size > 1) {
                            DiscussionButton(running = state.discussion?.isRunning == true, onClick = {
                                discussionError = null
                                discussionSheet = true
                            })
                        }
                    }
                } else null,
                trailingActions = if (dm != null || ch != null) {
                    {
                        Box {
                            LiveButton(
                                phase = liveUi.phase.takeIf { liveHere },
                                onClick = {
                                    val title = dm?.displayName ?: ch?.title ?: "채팅"
                                    when {
                                        liveHere -> live.primary()
                                        members.size > 1 -> liveMenu = true
                                        else -> startLive(LiveTarget(channelId, title, members.firstOrNull()?.slug))
                                    }
                                },
                            )
                            // 봇이 여럿인 채널: Live 에서 답할 봇을 고른다.
                            DropdownMenu(expanded = liveMenu, onDismissRequest = { liveMenu = false }) {
                                members.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text("@${m.slug} 와 Live") },
                                        onClick = {
                                            liveMenu = false
                                            startLive(LiveTarget(channelId, ch?.title ?: "채팅", m.slug))
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else null,
                topContent = if (liveHere || state.info != null || members.size > 1 || state.pending.isNotEmpty()) {
                    {
                        state.discussion?.takeIf { it.isRunning }?.let { DiscussionBar(it, onStop = vm::stopDiscussion) }
                        if (liveHere) {
                            LiveBar(
                                ui = liveUi,
                                glasses = glassesState,
                                onPhoto = live::takePhotoNow,
                                onGlassesSettings = onOpenGlasses,
                                onStop = live::stop,
                                onDismissError = { live.clearError(); live.glasses.clearError() },
                            )
                        }
                        state.info?.let {
                            Surface(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = hc.warningContainer,
                            ) {
                                Text(
                                    it,
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = hc.onWarningContainer,
                                )
                            }
                        }
                        if (members.size > 1) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                members.forEach { m ->
                                    val c = toneColor(m.tone) ?: MaterialTheme.colorScheme.primary
                                    Surface(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable {
                                                input = if (input.isEmpty() || input.endsWith(" ")) input + "@${m.slug} " else "$input @${m.slug} "
                                            },
                                        shape = CircleShape,
                                        color = c.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, c.copy(alpha = 0.30f)),
                                    ) {
                                        Row(
                                            Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            BotAvatar(m.avatarText, m.tone, size = 18.dp)
                                            Text(
                                                "@${m.slug}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = c,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (state.pending.isNotEmpty()) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.pending.forEach { f ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    ) {
                                        Row(
                                            Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(Icons.Rounded.AttachFile, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                            Text(
                                                f.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Icon(
                                                Icons.Rounded.Close,
                                                "첨부 제거",
                                                Modifier
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .clickable { vm.removeAttachment(f.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else null,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                state.error?.let { ErrorBanner(it, onRetry = vm::load) }
                when {
                    state.loading -> LoadingBox(label = "대화를 불러오는 중…")
                    state.messages.isEmpty() -> ChatIntroEmptyState(dm = dm, channel = ch)
                    else -> LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(state.messages, key = { it.id }) { m ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = ComposerMaxWidth).fillMaxWidth()) {
                                    MessageRow(m, state, vm, container.attachmentImages)
                                }
                            }
                        }
                    }
                }
            }

            // 위로 올려 읽는 중일 때 최신 메시지로 내려가는 플로팅 버튼
            androidx.compose.animation.AnimatedVisibility(
                visible = !nearBottom && state.messages.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(state.messages.lastIndex)
                        }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "맨 아래로")
                }
            }
        }
    }

    if (discussionSheet) {
        DiscussionSheet(
            members = members,
            initialTopic = input,
            starting = state.startingDiscussion,
            error = discussionError,
            onStart = { req ->
                vm.startDiscussion(req) { err ->
                    discussionError = err
                    if (err == null) { discussionSheet = false; input = "" }
                }
            },
            onDismiss = { discussionSheet = false },
        )
    }

    if (workspaceSheet) {
        WorkspacePickerSheet(
            workspaces = state.workspaces,
            current = state.workdir,
            onPick = { path -> workspaceSheet = false; vm.setWorkdir(path) },
            onManage = { workspaceSheet = false; onOpenWorkspaces() },
            onDismiss = { workspaceSheet = false },
        )
    }
}

private fun isGranted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ChatIntroEmptyState(dm: Bot?, channel: BotChannel?) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (dm != null) {
                BotAvatar(dm.avatarText, dm.tone, size = 72.dp, online = dm.isOnline)
                Text(dm.displayName, style = MaterialTheme.typography.titleLarge)
                dm.soulSummary?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    "아래 입력창에 메시지를 보내 대화를 시작하세요.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (channel != null) {
                ChannelAvatarGroup(channel.members)
                Text(channel.title, style = MaterialTheme.typography.titleLarge)
                channel.topic?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    "참여 중인 봇을 @멘션해 대화를 시작해 보세요.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text("대화를 시작하세요", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun MessageRow(m: BotMessage, state: BotChatUiState, vm: BotChatViewModel, images: AttachmentImageStore) {
    if (m.isSystem) {
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(
                    m.body,
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun copy(t: String) {
        clipboard.setText(AnnotatedString(t))
        Toast.makeText(context, "메시지를 복사했습니다", Toast.LENGTH_SHORT).show()
    }
    val attachments = m.attachmentItems
    if (m.isUser) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (m.body.isNotBlank()) {
                MessageBubble(text = m.body, isUser = true, onLongClick = { copy(m.body) })
            }
            BotAttachmentChips(attachments, images = images, alignEnd = true)
        }
        return
    }
    val streamed = state.streaming[m.id]
    val text = if (!streamed.isNullOrEmpty()) streamed else m.body
    val failed = m.status == "failed"
    val errText = if (failed) (m.error ?: state.streamErrors[m.id] ?: "응답 실패") else state.streamErrors[m.id]
    val accent = toneColor(m.tone)
    // 봇 답: 이름 줄(작은 아바타 + 이름) 아래에 말풍선 없이 본문을 넓게.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BotAvatar(m.avatar?.takeIf { it.isNotEmpty() } ?: "🤖", m.tone, size = 24.dp)
            Text(
                m.displayName ?: m.author ?: "봇",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent ?: MaterialTheme.colorScheme.primary,
            )
            DiscussionKindLabel(m.discKind)
            if (m.isInFlight && text.isNotEmpty()) {
                Text(
                    "· 작성 중",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        run {
            when {
                text.isNotEmpty() -> MessageBubble(text = text, isUser = false, onLongClick = { copy(text) })
                m.isInFlight -> TypingDots(
                    label = if (m.status == "queued") "대기 중" else "생각 중",
                    accentColor = accent,
                )
            }
            BotAttachmentChips(attachments, images = images)
            if (m.jobId != null || state.steps.containsKey(m.id)) {
                BotStepsSection(
                    messageId = m.id,
                    steps = state.steps[m.id],
                    running = m.isInFlight,
                    onExpand = { if (!m.isInFlight) vm.loadSteps(m.id) },
                )
            }
            errText?.let {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        it,
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
