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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val pendingApprovals: Int = 0,
    val error: String? = null,
)

class BotsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(BotsUiState())
    val state = _state.asStateFlow()

    init { refresh(initial = true) }

    fun refresh(initial: Boolean = false) {
        val api = container.botApi() ?: run {
            _state.update { it.copy(loading = false, refreshing = false) }
            return
        }
        _state.update { it.copy(refreshing = !initial) }
        viewModelScope.launch {
            try {
                val bots = api.fetchBots()
                val channels = api.fetchChannels()
                val approvals = runCatching { api.fetchApprovals().size }.getOrDefault(0)
                _state.update { BotsUiState(loading = false, bots = bots, channels = channels, pendingApprovals = approvals) }
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
    onOpenApprovals: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: BotsViewModel = viewModel(factory = viewModelFactory { initializer { BotsViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    val configured = container.botApi() != null

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("봇") },
                actions = {
                    IconButton(onClick = onOpenApprovals) {
                        BadgedBox(badge = { if (state.pendingApprovals > 0) Badge { Text("${state.pendingApprovals}") } }) {
                            Icon(Icons.Filled.Notifications, contentDescription = "승인 요청")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (configured) FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "새 채널")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!configured) {
                EmptyState("봇 서버가 설정되지 않았습니다", subtitle = "설정 탭에서 봇 서버 주소와 토큰을 입력하세요.")
                return@Column
            }
            state.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
            if (state.loading) {
                LoadingBox()
            } else {
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { SectionHeader("봇") }
                        items(state.bots, key = { "b${it.id}" }) { b ->
                            BotRow(b) { b.dmChannelId?.let(onOpenChannel) }
                            HorizontalDivider()
                        }
                        item { SectionHeader("채널") }
                        if (state.channels.isEmpty()) {
                            item {
                                Text("채널이 없습니다. 아래 + 로 만들어 보세요.", Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(state.channels, key = { "c${it.id}" }) { c ->
                            ChannelRow(c) { onOpenChannel(c.id) }
                            HorizontalDivider()
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
private fun SectionHeader(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BotRow(b: Bot, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = b.dmChannelId != null, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(b.avatarText, b.tone)
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
