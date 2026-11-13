package com.hermes.chatandroid.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BotsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val bots: List<Bot> = emptyList(),
    val channels: List<BotChannel> = emptyList(),
    val error: String? = null,
)

/**
 * 홈 목록 순서: 대화가 있는 봇(미리보기 있음) 먼저, 그 안에서 최근에 연 순서(이 기기 기준 — 서버는 DM 시각을 주지 않는다),
 * 그다음 온라인 → 이름. 보관된 봇/DM 없는 봇은 제외.
 */
internal fun sortBotsForHome(bots: List<Bot>, activity: Map<Int, Long>): List<Bot> =
    bots.filter { !it.isArchived && it.dmChannelId != null }
        .sortedWith(
            compareByDescending<Bot> { !it.preview.isNullOrEmpty() }
                .thenByDescending { activity[it.id] ?: 0L }
                .thenByDescending { it.isOnline }
                .thenBy { it.displayName },
        )

class BotsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(BotsUiState())
    val state = _state.asStateFlow()

    fun refresh(initial: Boolean = false) {
        val api = container.botApi() ?: run {
            _state.update { it.copy(loading = false, refreshing = false) }
            return
        }
        _state.update { it.copy(refreshing = !initial) }
        viewModelScope.launch {
            try {
                if (!initial) runCatching { api.refreshHealth() } // 당겨서 새로고침: 헬스 점도 갱신
                val bots = api.fetchBots()
                val channels = api.fetchChannels()
                _state.update { BotsUiState(loading = false, bots = bots, channels = channels) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = botErrorText(e)) }
            }
        }
    }

    fun createChannel(title: String, topic: String, botIds: List<Int>, onCreated: (Int) -> Unit) {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                val ch = api.createChannel(title.trim(), topic.trim().ifEmpty { null }, botIds)
                onCreated(ch.id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    container: AppContainer,
    onOpenChannel: (channelId: Int) -> Unit,
    onOpenBotProfile: (botId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: BotsViewModel = viewModel(factory = viewModelFactory { initializer { BotsViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    // 탭으로 돌아올 때마다 조용히 갱신(첫 진입 포함)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh(initial = true) }
    var showCreate by remember { mutableStateOf(false) }
    var showChannels by rememberSaveable { mutableStateOf(false) }
    val configured = container.botApi() != null

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("대화") }) },
        floatingActionButton = {
            if (configured && showChannels) FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "새 채널")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!configured) {
                EmptyState("봇 서버가 설정되지 않았습니다", subtitle = "더보기 탭에서 봇 서버 주소와 토큰을 입력하세요.")
                return@Column
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                SegmentedButton(
                    selected = !showChannels, onClick = { showChannels = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("봇") }
                SegmentedButton(
                    selected = showChannels, onClick = { showChannels = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("채널") }
            }
            state.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
            if (state.loading) {
                LoadingBox()
            } else {
                val homeBots = sortBotsForHome(state.bots, container.botActivity)
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                    if (!showChannels && homeBots.isEmpty()) {
                        EmptyState("대화할 봇이 없습니다", subtitle = "서버에서 프로필을 검색하면 봇이 나타납니다. 당겨서 새로고침하세요.")
                    } else if (showChannels && state.channels.isEmpty()) {
                        EmptyState("채널이 없습니다", subtitle = "아래 + 로 봇들이 모인 채널을 만들어 보세요.")
                    } else LazyColumn(Modifier.fillMaxSize()) {
                        if (!showChannels) {
                            items(homeBots, key = { "b${it.id}" }) { b ->
                                BotRow(b, onAvatar = { onOpenBotProfile(b.id) }) {
                                    container.touchBotActivity(b.id)
                                    b.dmChannelId?.let(onOpenChannel)
                                }
                                HorizontalDivider()
                            }
                        } else {
                            items(state.channels, key = { "c${it.id}" }) { c ->
                                ChannelRow(c) { onOpenChannel(c.id) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateChannelDialog(
            bots = state.bots,
            onDismiss = { showCreate = false },
            onCreate = { title, topic, ids ->
                showCreate = false
                vm.createChannel(title, topic, ids, onOpenChannel)
            },
        )
    }
}

@Composable
private fun BotRow(b: Bot, onAvatar: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = b.dmChannelId != null, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(b.avatarText, b.tone, modifier = Modifier.clickable(onClick = onAvatar))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Box(
                    Modifier.size(8.dp).background(
                        if (b.isOnline) Color(0xFF3FB68B) else MaterialTheme.colorScheme.outline, CircleShape,
                    ),
                )
            }
            Text(b.previewLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChannelRow(c: BotChannel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.preview?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(c.members.take(5).joinToString("") { it.avatarText }, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CreateChannelDialog(
    bots: List<Bot>,
    onDismiss: () -> Unit,
    onCreate: (title: String, topic: String, botIds: List<Int>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 채널") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("제목") }, singleLine = true)
                OutlinedTextField(topic, { topic = it }, label = { Text("주제 (선택)") })
                Text("참여 봇", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(bots, key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (b.id in selected) selected - b.id else selected + b.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = b.id in selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Text("${b.avatarText} ${b.displayName}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onCreate(title, topic, selected.toList()) }) { Text("만들기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
