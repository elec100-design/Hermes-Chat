package com.hermes.chatandroid.ui.workspaces

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.hermes.chatandroid.data.BrowseResult
import com.hermes.chatandroid.data.PushRequestResult
import com.hermes.chatandroid.data.Workspace
import com.hermes.chatandroid.data.WorkspaceStatus
import com.hermes.chatandroid.data.workChatsByWorkspace
import com.hermes.chatandroid.ui.bots.BotChatScreen
import com.hermes.chatandroid.ui.bots.ChannelAvatarGroup
import com.hermes.chatandroid.ui.bots.botErrorText
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.IconBadge
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspacesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<Workspace> = emptyList(),
    /** 작업 채팅 = 작업 폴더가 지정된 채널. 워크스페이스별로 묶어 보여 준다. */
    val channels: List<BotChannel> = emptyList(),
    val bots: List<Bot> = emptyList(),
    val error: String? = null,
    /** 추가/클론 진행 중. */
    val adding: Boolean = false,
    val addError: String? = null,
)

data class DetailUiState(
    val workspace: Workspace,
    val status: WorkspaceStatus? = null,
    val statusLoading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    /** 성공 안내(커밋 sha, 제거 note 등). */
    val info: String? = null,
    val push: PushRequestResult? = null,
    val bots: List<Bot> = emptyList(),
)

data class BrowseUiState(
    val loading: Boolean = true,
    val result: BrowseResult? = null,
    val error: String? = null,
)

class WorkspacesViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(WorkspacesUiState())
    val state = _state.asStateFlow()
    private val _detail = MutableStateFlow<DetailUiState?>(null)
    val detail = _detail.asStateFlow()
    private val _browse = MutableStateFlow(BrowseUiState())
    val browse = _browse.asStateFlow()

    private fun noServer() = "봇 서버가 설정되지 않았습니다."

    fun refresh(initial: Boolean = false) {
        val api = container.botApi()
        if (api == null) {
            _state.update { it.copy(loading = false, refreshing = false, error = noServer()) }
            return
        }
        _state.update { it.copy(refreshing = !initial) }
        viewModelScope.launch {
            try {
                val list = api.fetchWorkspaces()
                // 작업 채팅·봇 목록은 부가 정보 — 실패해도 워크스페이스 목록은 보여 준다
                val channels = runCatching { api.fetchChannels() }.getOrNull()
                val bots = runCatching { api.fetchBots() }.getOrNull()
                _state.update {
                    it.copy(
                        loading = false, refreshing = false, items = list, error = null,
                        channels = channels ?: it.channels, bots = bots ?: it.bots,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = botErrorText(e)) }
            }
        }
    }

    // ---- 폴더 탐색 / 추가 / 클론 ----
    fun browse(path: String?) {
        val api = container.botApi()
        if (api == null) { _browse.value = BrowseUiState(false, null, noServer()); return }
        _browse.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                _browse.value = BrowseUiState(false, api.browseWorkspaces(path), null)
            } catch (e: Exception) {
                _browse.update { it.copy(loading = false, error = botErrorText(e)) }
            }
        }
    }

    fun clearAddError() = _state.update { it.copy(addError = null) }

    fun addFolder(path: String, label: String?, onDone: () -> Unit) {
        val api = container.botApi() ?: run { _state.update { it.copy(addError = noServer()) }; return }
        if (_state.value.adding) return
        _state.update { it.copy(adding = true, addError = null) }
        viewModelScope.launch {
            try {
                api.addWorkspaceFolder(path, label?.trim()?.takeIf { it.isNotEmpty() })
                _state.update { it.copy(adding = false) }
                refresh()
                onDone()
            } catch (e: Exception) {
                _state.update { it.copy(adding = false, addError = botErrorText(e)) }
            }
        }
    }

    fun clone(repo: String, branch: String?, label: String?, onDone: () -> Unit) {
        val api = container.botApi() ?: run { _state.update { it.copy(addError = noServer()) }; return }
        if (_state.value.adding) return
        _state.update { it.copy(adding = true, addError = null) }
        viewModelScope.launch {
            try {
                api.cloneWorkspace(
                    repo.trim(),
                    branch?.trim()?.takeIf { it.isNotEmpty() },
                    label?.trim()?.takeIf { it.isNotEmpty() },
                )
                _state.update { it.copy(adding = false) }
                refresh()
                onDone()
            } catch (e: Exception) {
                _state.update { it.copy(adding = false, addError = botErrorText(e)) }
            }
        }
    }

    /** [ws] 폴더에서 일하는 작업 채팅(채널)을 만든다 — 채널 작업 폴더 = 워크스페이스 경로. */
    fun createWorkChat(ws: Workspace, title: String, botIds: List<Int>, onCreated: (Int) -> Unit) {
        val api = container.botApi() ?: run { _state.update { it.copy(addError = noServer()) }; return }
        if (_state.value.adding) return
        _state.update { it.copy(adding = true, addError = null) }
        viewModelScope.launch {
            try {
                val ch = api.createChannel(title.trim(), botIds = botIds, workdir = ws.path)
                _state.update { it.copy(adding = false, channels = it.channels + ch.copy(workdir = ch.workdir ?: ws.path)) }
                onCreated(ch.id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(adding = false, addError = botErrorText(e)) }
            }
        }
    }

    // ---- 상세 ----
    fun openDetail(ws: Workspace) {
        _detail.value = DetailUiState(ws)
        loadStatus()
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                val bots = api.fetchBots()
                _detail.update { d -> d?.takeIf { it.workspace.id == ws.id }?.copy(bots = bots) ?: d }
            } catch (_: Exception) { /* 봇 선택은 선택 사항 */ }
        }
    }

    fun closeDetail() { _detail.value = null }

    fun loadStatus() {
        val d = _detail.value ?: return
        val api = container.botApi() ?: run { _detail.update { it?.copy(statusLoading = false, error = noServer()) }; return }
        _detail.update { it?.copy(statusLoading = true) }
        viewModelScope.launch {
            try {
                val s = api.fetchWorkspaceStatus(d.workspace.id)
                _detail.update { it?.takeIf { x -> x.workspace.id == d.workspace.id }?.copy(status = s, statusLoading = false, error = null) ?: it }
            } catch (e: Exception) {
                _detail.update { it?.copy(statusLoading = false, error = botErrorText(e)) }
            }
        }
    }

    private fun act(block: suspend (com.hermes.chatandroid.net.BotApi, DetailUiState) -> Unit) {
        val d = _detail.value ?: return
        if (d.busy) return
        val api = container.botApi() ?: run { _detail.update { it?.copy(error = noServer()) }; return }
        _detail.update { it?.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                block(api, d)
            } catch (e: Exception) {
                _detail.update { it?.copy(error = botErrorText(e)) }
            }
            _detail.update { it?.copy(busy = false) }
        }
    }

    fun commit(message: String) = act { api, d ->
        val sha = api.commitWorkspace(d.workspace.id, message.trim())
        _detail.update { it?.copy(info = "커밋했습니다" + (sha?.let { s -> " ($s)" } ?: "")) }
        loadStatus()
    }

    fun requestPush(branch: String?, botId: Int?) = act { api, d ->
        val r = api.requestWorkspacePush(d.workspace.id, branch?.trim()?.takeIf { it.isNotEmpty() }, botId)
        _detail.update { it?.copy(push = r) }
    }

    fun remove(onRemoved: () -> Unit) {
        val d = _detail.value ?: return
        if (d.busy) return
        val api = container.botApi() ?: run { _detail.update { it?.copy(error = noServer()) }; return }
        _detail.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val note = api.removeWorkspace(d.workspace.id)
                _detail.value = null
                _state.update { it.copy(error = null) }
                refresh()
                lastRemoveNote.value = note ?: "목록에서만 제거했습니다. 폴더는 그대로입니다."
                onRemoved()
            } catch (e: Exception) {
                _detail.update { it?.copy(busy = false, error = botErrorText(e)) }
            }
        }
    }

    /** 제거 후 목록 화면에 잠깐 보이는 서버 안내. */
    val lastRemoveNote = MutableStateFlow<String?>(null)
}

/**
 * 워크스페이스 탭(T-198): 워크스페이스마다 작업 채팅을 여러 개 열어 코딩 작업을 나눠 진행한다.
 * 넓은 창 = 목록 + 채팅 나란히, 좁은 창 = 목록 ↔ 채팅 전환(채팅 중엔 AppNav 가 탭 바를 숨긴다).
 */
@Composable
fun WorkspacesScreen(
    container: AppContainer,
    selectedChannelId: Int?,
    onSelectChannel: (channelId: Int?) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBotProfile: (botId: Int) -> Unit = {},
    onOpenChannelSettings: (channelId: Int) -> Unit = {},
    onOpenGlasses: () -> Unit = {},
) {
    val vm: WorkspacesViewModel = viewModel(factory = viewModelFactory { initializer { WorkspacesViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh(initial = true) }
    var showBrowse by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var newChatFor by remember { mutableStateOf<Workspace?>(null) }
    var sidebarExpanded by rememberSaveable { mutableStateOf(true) }
    val hc = MaterialTheme.hermesColors
    val chats = remember(state.items, state.channels) { workChatsByWorkspace(state.items, state.channels) }

    // 지워진 채널을 가리키고 있으면 선택 해제
    LaunchedEffect(state.loading, state.error, state.channels, selectedChannelId) {
        if (state.loading || state.error != null) return@LaunchedEffect
        if (selectedChannelId != null && state.channels.none { it.id == selectedChannelId }) onSelectChannel(null)
    }

    val listPane: @Composable () -> Unit = {
        WorkspaceListPane(
            state = state,
            vm = vm,
            chats = chats,
            selectedChannelId = selectedChannelId,
            onSelectChannel = { onSelectChannel(it) },
            onNewChat = { vm.clearAddError(); newChatFor = it },
            onAddFolder = { vm.clearAddError(); vm.browse(null); showBrowse = true },
            onClone = { vm.clearAddError(); showClone = true },
        )
    }
    val chatPane: @Composable (Int, (() -> Unit)?) -> Unit = { chId, onBack ->
        BotChatScreen(
            container,
            channelId = chId,
            onBack = onBack,
            onMenuClick = if (onBack == null) ({ sidebarExpanded = !sidebarExpanded }) else null,
            onOpenBotProfile = onOpenBotProfile,
            onOpenChannelSettings = onOpenChannelSettings,
            onOpenGlasses = onOpenGlasses,
            // 이미 워크스페이스 탭 — 좁은 창에서는 목록으로 돌아간다
            onOpenWorkspaces = { if (onBack != null) onBack() else sidebarExpanded = true },
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        val listWidth = (maxWidth * 0.36f).coerceIn(260.dp, 330.dp)
        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = sidebarExpanded || selectedChannelId == null,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    Row(Modifier.fillMaxHeight()) {
                        Surface(Modifier.width(listWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) { listPane() }
                        VerticalDivider(thickness = 0.7.dp, color = hc.cardBorder)
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedChannelId != null) chatPane(selectedChannelId, null)
                    else EmptyState(
                        title = "작업 채팅을 선택하세요",
                        subtitle = "워크스페이스의 + 버튼으로 새 작업 채팅을 열 수 있습니다.",
                        icon = Icons.Rounded.Code,
                    )
                }
            }
        } else {
            AnimatedContent(
                targetState = selectedChannelId,
                transitionSpec = {
                    val forward = targetState != null
                    (slideInHorizontally(tween(240)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(200)))
                        .togetherWith(fadeOut(tween(160)))
                },
                label = "workspaceListDetail",
            ) { chId ->
                if (chId == null) {
                    listPane()
                } else {
                    BackHandler { onSelectChannel(null) }
                    chatPane(chId) { onSelectChannel(null) }
                }
            }
        }
    }

    if (showBrowse) BrowseDialog(vm, onDismiss = { showBrowse = false })
    if (showClone) CloneDialog(vm, onDismiss = { showClone = false })
    newChatFor?.let { ws ->
        NewWorkChatDialog(
            ws = ws,
            bots = state.bots.filter { !it.isArchived },
            defaultTitle = "${ws.label} 작업 ${(chats[ws.id]?.size ?: 0) + 1}",
            busy = state.adding,
            error = state.addError,
            onDismiss = { newChatFor = null },
            onCreate = { title, botIds ->
                vm.createWorkChat(ws, title, botIds) { chId -> newChatFor = null; onSelectChannel(chId) }
            },
        )
    }
    detail?.let { d -> DetailSheet(d, vm) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceListPane(
    state: WorkspacesUiState,
    vm: WorkspacesViewModel,
    chats: Map<Int, List<BotChannel>>,
    selectedChannelId: Int?,
    onSelectChannel: (Int) -> Unit,
    onNewChat: (Workspace) -> Unit,
    onAddFolder: () -> Unit,
    onClone: () -> Unit,
) {
    val removeNote by vm.lastRemoveNote.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // 오른쪽 채팅의 TopAppBar 와 높이를 64dp 로 맞춘다
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .height(64.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("워크스페이스", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.refresh() }, enabled = !state.refreshing) {
                Icon(Icons.Rounded.Refresh, contentDescription = "새로고침")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.Add, contentDescription = "워크스페이스 추가") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("폴더 추가") },
                        leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                        onClick = { menuOpen = false; onAddFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text("리포 클론") },
                        leadingIcon = { Icon(Icons.Rounded.Code, null) },
                        onClick = { menuOpen = false; onClone() },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.7.dp, color = MaterialTheme.hermesColors.cardBorder)
        state.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
        removeNote?.let {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.lastRemoveNote.value = null }) { Text("확인") }
                }
            }
        }
        when {
            state.loading -> LoadingBox(label = "워크스페이스를 불러오는 중…")
            else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                if (state.items.isEmpty()) {
                    EmptyState(
                        title = "등록된 작업 위치가 없습니다",
                        subtitle = "위의 + 버튼으로 서버 폴더를 추가하거나 Git 리포지토리를 클론하세요.",
                        icon = Icons.Rounded.FolderOpen,
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.items.forEach { ws ->
                            item(key = "ws-${ws.id}") {
                                WorkspaceHeaderRow(
                                    ws,
                                    onOpen = { vm.lastRemoveNote.value = null; vm.openDetail(ws) },
                                    onNewChat = { onNewChat(ws) },
                                )
                            }
                            items(chats[ws.id].orEmpty(), key = { "ch-${it.id}" }) { ch ->
                                WorkChatRow(ch, selected = ch.id == selectedChannelId, onClick = { onSelectChannel(ch.id) })
                            }
                            item(key = "new-${ws.id}") { NewChatRow(onClick = { onNewChat(ws) }) }
                        }
                    }
                }
            }
        }
    }
}

/** 워크스페이스 한 줄 — 누르면 상태·커밋·제거 시트, 오른쪽 + 는 새 작업 채팅. */
@Composable
private fun WorkspaceHeaderRow(ws: Workspace, onOpen: () -> Unit, onNewChat: () -> Unit) {
    val tint = if (ws.isRepo) MaterialTheme.colorScheme.primary else Color(0xFF4F86A8)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon = if (ws.isRepo) Icons.Rounded.Code else Icons.Rounded.Folder, tint = tint, size = 32.dp, iconSize = 17.dp, shapeRadius = 9.dp)
        Column(Modifier.weight(1f)) {
            Text(ws.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ws.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onNewChat) {
            Icon(Icons.Rounded.Add, contentDescription = "${ws.label} 새 작업 채팅", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkChatRow(ch: BotChannel, selected: Boolean, onClick: () -> Unit) {
    val hc = MaterialTheme.hermesColors
    val accent = MaterialTheme.colorScheme.primary
    val sub = ch.preview?.takeIf { it.isNotBlank() } ?: ch.members.joinToString(" · ") { it.displayName }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = if (hc.isDark) 0.18f else 0.10f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, accent.copy(alpha = if (hc.isDark) 0.42f else 0.30f)) else null,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelAvatarGroup(ch.members, size = 30.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    ch.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NewChatRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("새 작업 채팅", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewWorkChatDialog(
    ws: Workspace,
    bots: List<Bot>,
    defaultTitle: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (title: String, botIds: List<Int>) -> Unit,
) {
    var title by remember(ws.id) { mutableStateOf(defaultTitle) }
    var selected by remember(ws.id) { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("새 작업 채팅") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${ws.label} 폴더에서 일할 봇을 고르세요. 봇이 하나면 멘션 없이 바로 답합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HermesTextField(title, { title = it }, label = "제목", enabled = !busy)
                if (bots.isEmpty()) {
                    Text("사용할 수 있는 봇이 없습니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bots.forEach { b ->
                            FilterChip(
                                selected = b.id in selected,
                                enabled = !busy,
                                onClick = { selected = if (b.id in selected) selected - b.id else selected + b.id },
                                label = { Text(b.displayName) },
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && selected.isNotEmpty() && !busy,
                shape = CircleShape,
                onClick = { onCreate(title, selected.toList()) },
            ) { Text(if (busy) "만드는 중…" else "열기") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun BrowseDialog(vm: WorkspacesViewModel, onDismiss: () -> Unit) {
    val b by vm.browse.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.adding) onDismiss() },
        title = { Text("폴더 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    b.result?.path ?: "서버 폴더 탐색",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                (b.error ?: state.addError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)) {
                    when {
                        b.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> LazyColumn {
                            val r = b.result
                            if (r?.parent != null) {
                                item {
                                    ListItem(
                                        modifier = Modifier.clickable { vm.browse(r.parent) },
                                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                                        headlineContent = { Text("..") },
                                    )
                                }
                            }
                            items(r?.entries ?: emptyList(), key = { it.path }) { e ->
                                ListItem(
                                    modifier = Modifier.clickable { vm.browse(e.path) },
                                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                            if (r != null && r.entries.isEmpty()) {
                                item { Text("하위 폴더가 없습니다", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                HermesTextField(value = label, onValueChange = { label = it }, label = "표시 이름 (선택)")
            }
        },
        confirmButton = {
            Button(
                enabled = b.result != null && !state.adding,
                shape = CircleShape,
                onClick = { b.result?.let { vm.addFolder(it.path, label, onDismiss) } },
            ) { Text(if (state.adding) "추가 중…" else "이 폴더 선택") }
        },
        dismissButton = { TextButton(enabled = !state.adding, onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun CloneDialog(vm: WorkspacesViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.adding) onDismiss() },
        title = { Text("리포 클론") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HermesTextField(repo, { repo = it }, label = "리포 URL", placeholder = "https://github.com/…", enabled = !state.adding)
                HermesTextField(branch, { branch = it }, label = "브랜치 (선택)", enabled = !state.adding)
                HermesTextField(label, { label = it }, label = "표시 이름 (선택)", enabled = !state.adding)
                if (state.adding) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("클론 중… 큰 리포는 몇 분 걸릴 수 있습니다", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = repo.isNotBlank() && !state.adding,
                shape = CircleShape,
                onClick = { vm.clone(repo, branch, label, onDismiss) },
            ) { Text("클론") }
        },
        dismissButton = { TextButton(enabled = !state.adding, onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailSheet(d: DetailUiState, vm: WorkspacesViewModel) {
    var showCommit by remember { mutableStateOf(false) }
    var showPush by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val ws = d.workspace
    val isRepo = d.status?.isRepo ?: ws.isRepo
    val hc = MaterialTheme.hermesColors

    ModalBottomSheet(onDismissRequest = { vm.closeDetail() }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(ws.label, style = MaterialTheme.typography.titleLarge)
            Text(ws.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ws.repoUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            d.error?.let { ErrorBanner(it, onRetry = { vm.loadStatus() }) }
            d.info?.let { Text(it, color = hc.online, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
            d.push?.let { p ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("승인 탭에서 승인하면 푸시됩니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "승인 #${p.approvalId ?: "?"} · 상태 ${p.status ?: "대기"}" + (p.note?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider(color = hc.subtleDivider)
            when {
                d.statusLoading && d.status == null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                d.status != null && !d.status.isRepo -> Text(
                    "git 저장소가 아닌 폴더입니다. 커밋/푸시를 쓸 수 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                d.status != null -> {
                    val s = d.status
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = "브랜치 ${s.branch ?: "?"}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                        StatusPill(
                            text = if (s.dirty) "변경 있음" else "깨끗함",
                            containerColor = if (s.dirty) hc.warningContainer else hc.online.copy(alpha = 0.14f),
                            contentColor = if (s.dirty) hc.onWarningContainer else hc.online,
                            dotColor = if (s.dirty) hc.warning else hc.online,
                        )
                    }
                    if (s.files.isNotEmpty()) {
                        Text("변경된 파일 (${s.files.size})", style = MaterialTheme.typography.labelLarge)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = hc.codeSurface,
                            border = BorderStroke(1.dp, hc.cardBorder),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
                        ) {
                            LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(s.files) { f ->
                                    Text(f, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = isRepo && !d.busy && d.status?.dirty == true,
                    onClick = { showCommit = true },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) { Text("커밋") }
                OutlinedButton(
                    enabled = isRepo && !d.busy,
                    onClick = { showPush = true },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) { Text("푸시 요청") }
            }
            if (d.busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            TextButton(enabled = !d.busy, onClick = { showRemove = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("목록에서 제거", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showCommit) {
        var msg by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCommit = false },
            title = { Text("커밋") },
            text = { HermesTextField(msg, { msg = it }, label = "커밋 메시지", singleLine = false, maxLines = 3) },
            confirmButton = {
                Button(enabled = msg.isNotBlank(), shape = CircleShape, onClick = { showCommit = false; vm.commit(msg) }) { Text("커밋") }
            },
            dismissButton = { TextButton(onClick = { showCommit = false }) { Text("취소") } },
        )
    }
    if (showPush) {
        var branch by remember { mutableStateOf("") }
        var botId by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { showPush = false },
            title = { Text("푸시 요청") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "바로 푸시되지 않고 승인 항목이 만들어집니다. 승인 탭에서 승인하면 푸시됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HermesTextField(branch, { branch = it }, label = "브랜치 (선택)")
                    if (d.bots.isNotEmpty()) {
                        Text("요청 봇 (선택)", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.bots.forEach { b ->
                                FilterChip(
                                    selected = botId == b.id,
                                    onClick = { botId = if (botId == b.id) null else b.id },
                                    label = { Text(b.name ?: b.slug) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(shape = CircleShape, onClick = { showPush = false; vm.requestPush(branch, botId) }) { Text("요청") } },
            dismissButton = { TextButton(onClick = { showPush = false }) { Text("취소") } },
        )
    }
    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("목록에서 제거") },
            text = { Text("'${ws.label}' 을(를) 작업 위치 목록에서만 제거합니다. 디스크의 폴더와 파일은 삭제되지 않고 그대로 남습니다.") },
            confirmButton = { TextButton(onClick = { showRemove = false; vm.remove {} }) { Text("제거", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text("취소") } },
        )
    }
}
