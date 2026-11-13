package com.hermes.chatandroid.ui.bots

import android.content.ContentResolver
import android.net.Uri
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.BotStep
import com.hermes.chatandroid.ui.bots.chat.BotAttachmentChips
import com.hermes.chatandroid.ui.bots.chat.BotStepsSection
import com.hermes.chatandroid.ui.bots.chat.PendingBotFile
import com.hermes.chatandroid.ui.bots.chat.TypingDots
import com.hermes.chatandroid.ui.bots.chat.readPendingFile
import com.hermes.chatandroid.ui.components.ChatComposerDock
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.MessageBubble
import com.hermes.chatandroid.ui.theme.hermesColors
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
)

class BotChatViewModel(private val container: AppContainer, private val channelId: Int) : ViewModel() {
    private val _state = MutableStateFlow(BotChatUiState())
    val state = _state.asStateFlow()

    private val follower = BotJobFollower(
        scope = viewModelScope,
        streamOf = { jobId -> container.botApi()?.jobStream(jobId) ?: emptyFlow() },
        reload = { container.botApi()!!.fetchMessages(channelId) },
        onMessages = { list ->
            _state.update { s ->
                val sorted = list.sortedWith(compareBy({ it.seq ?: Int.MAX_VALUE }, { it.id }))
                val inFlight = sorted.filter { it.isInFlight }.map { it.id }.toSet()
                // 최종본이 된 메시지의 스트리밍 버퍼는 제거
                s.copy(messages = sorted, streaming = s.streaming.filterKeys { it in inFlight })
            }
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
                val r = api.postMessage(channelId, body, uploaded)
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
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
                topContent = if (state.info != null || members.size > 1 || state.pending.isNotEmpty()) {
                    {
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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.messages, key = { it.id }) { m -> MessageRow(m, state, vm) }
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
}

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
private fun MessageRow(m: BotMessage, state: BotChatUiState, vm: BotChatViewModel) {
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
            BotAttachmentChips(attachments)
        }
        return
    }
    val streamed = state.streaming[m.id]
    val text = if (!streamed.isNullOrEmpty()) streamed else m.body
    val failed = m.status == "failed"
    val errText = if (failed) (m.error ?: state.streamErrors[m.id] ?: "응답 실패") else state.streamErrors[m.id]
    val accent = toneColor(m.tone)
    val hc = MaterialTheme.hermesColors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        BotAvatar(m.avatar?.takeIf { it.isNotEmpty() } ?: "🤖", m.tone, size = 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    m.displayName ?: m.author ?: "봇",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent ?: MaterialTheme.colorScheme.primary,
                )
                if (m.isInFlight && text.isNotEmpty()) {
                    Text(
                        "· 작성 중",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                text.isNotEmpty() -> MessageBubble(
                    text = text,
                    isUser = false,
                    containerColor = accent?.copy(alpha = if (hc.isDark) 0.13f else 0.08f),
                    borderColor = accent?.copy(alpha = 0.35f),
                    onLongClick = { copy(text) },
                )
                m.isInFlight -> TypingDots(
                    label = if (m.status == "queued") "대기 중" else "생각 중",
                    accentColor = accent,
                )
            }
            BotAttachmentChips(attachments)
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
