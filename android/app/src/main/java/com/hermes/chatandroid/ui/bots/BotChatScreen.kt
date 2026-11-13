package com.hermes.chatandroid.ui.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.MessageBubble
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    )

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
        if (body.isEmpty() || _state.value.sending) return
        _state.update { it.copy(sending = true, info = null, error = null) }
        viewModelScope.launch {
            try {
                val r = api.postMessage(channelId, body)
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
    onBack: () -> Unit,
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
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastLen = state.messages.size to state.streaming.values.sumOf { it.length }

    LaunchedEffect(lastLen) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    val members = state.channel?.members.orEmpty()
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    val dm = state.dmBot
                    val titleClick: (() -> Unit)? = when {
                        dm != null -> { { onOpenBotProfile(dm.id) } }
                        state.channel != null -> { { onOpenChannelSettings(channelId) } }
                        else -> null
                    }
                    Row(
                        Modifier.then(if (titleClick != null) Modifier.clickable(onClick = titleClick) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (dm != null) BotAvatar(dm.avatarText, dm.tone, size = 28.dp)
                        Text(dm?.displayName ?: state.channel?.title ?: "채팅", maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                state.info?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                if (members.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        members.forEach { m ->
                            AssistChip(
                                onClick = { input = if (input.isEmpty() || input.endsWith(" ")) input + "@${m.slug} " else "$input @${m.slug} " },
                                label = { Text("${m.avatarText} @${m.slug}") },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("메시지") },
                        maxLines = 5,
                    )
                    IconButton(
                        onClick = { vm.send(input) { input = "" } },
                        enabled = input.isNotBlank() && !state.sending,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "보내기") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it, onRetry = vm::load) }
            if (state.loading) {
                LoadingBox()
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { m -> MessageRow(m, state) }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: BotMessage, state: BotChatUiState) {
    if (m.isSystem) {
        Text(
            m.body,
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (m.isUser) {
        MessageBubble(text = m.body, isUser = true)
        return
    }
    val streamed = state.streaming[m.id]
    val text = if (!streamed.isNullOrEmpty()) streamed else m.body
    val failed = m.status == "failed"
    val errText = if (failed) (m.error ?: state.streamErrors[m.id] ?: "응답 실패") else state.streamErrors[m.id]
    val accent = toneColor(m.tone)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        BotAvatar(m.avatar?.takeIf { it.isNotEmpty() } ?: "🤖", m.tone, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                m.displayName ?: m.author ?: "봇",
                style = MaterialTheme.typography.labelMedium,
                color = accent ?: MaterialTheme.colorScheme.primary,
            )
            when {
                text.isNotEmpty() -> MessageBubble(text = text, isUser = false)
                m.isInFlight -> Text(
                    if (m.status == "queued") "대기 중…" else "생각 중…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            errText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
