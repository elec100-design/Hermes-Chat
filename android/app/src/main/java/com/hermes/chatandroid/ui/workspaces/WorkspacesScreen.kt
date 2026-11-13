package com.hermes.chatandroid.ui.workspaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.hermes.chatandroid.data.BrowseResult
import com.hermes.chatandroid.data.PushRequestResult
import com.hermes.chatandroid.data.Workspace
import com.hermes.chatandroid.data.WorkspaceStatus
import com.hermes.chatandroid.ui.bots.botErrorText
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspacesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<Workspace> = emptyList(),
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
                _state.update { it.copy(loading = false, refreshing = false, items = list, error = null) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: WorkspacesViewModel = viewModel(factory = viewModelFactory { initializer { WorkspacesViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val removeNote by vm.lastRemoveNote.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh(initial = true) }
    var menuOpen by remember { mutableStateOf(false) }
    var showBrowse by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("워크스페이스") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, contentDescription = "추가") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("폴더 추가") },
                        leadingIcon = { Icon(Icons.Filled.Folder, null) },
                        onClick = { menuOpen = false; vm.clearAddError(); vm.browse(null); showBrowse = true },
                    )
                    DropdownMenuItem(
                        text = { Text("리포 클론") },
                        leadingIcon = { Icon(Icons.Filled.Code, null) },
                        onClick = { menuOpen = false; vm.clearAddError(); showClone = true },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
            removeNote?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.lastRemoveNote.value = null }) { Text("확인") }
                }
            }
            when {
                state.loading -> LoadingBox()
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                    if (state.items.isEmpty()) {
                        EmptyState("등록된 작업 위치가 없습니다", subtitle = "+ 버튼으로 폴더를 추가하거나 리포를 클론하세요")
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(state.items, key = { it.id }) { ws ->
                                ListItem(
                                    modifier = Modifier.clickable { vm.lastRemoveNote.value = null; vm.openDetail(ws) },
                                    leadingContent = { Icon(if (ws.isRepo) Icons.Filled.Code else Icons.Filled.Folder, null) },
                                    headlineContent = { Text(ws.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(ws.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = {
                                        if (ws.isRepo) AssistChip(onClick = { vm.openDetail(ws) }, label = { Text(ws.defaultBranch ?: "repo") })
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBrowse) {
        BrowseDialog(vm, onDismiss = { showBrowse = false })
    }
    if (showClone) {
        CloneDialog(vm, onDismiss = { showClone = false })
    }
    detail?.let { d ->
        DetailSheet(d, vm)
    }
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
            Column {
                Text(b.result?.path ?: "서버 폴더 탐색", style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                (b.error ?: state.addError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp)) {
                    when {
                        b.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> LazyColumn {
                            val r = b.result
                            if (r?.parent != null) {
                                item {
                                    ListItem(
                                        modifier = Modifier.clickable { vm.browse(r.parent) },
                                        leadingContent = { Icon(Icons.Filled.Folder, null) },
                                        headlineContent = { Text("..") },
                                    )
                                }
                            }
                            items(r?.entries ?: emptyList(), key = { it.path }) { e ->
                                ListItem(
                                    modifier = Modifier.clickable { vm.browse(e.path) },
                                    leadingContent = { Icon(Icons.Filled.Folder, null) },
                                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                            if (r != null && r.entries.isEmpty()) item { Text("하위 폴더가 없습니다", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                OutlinedTextField(
                    value = label, onValueChange = { label = it }, singleLine = true,
                    label = { Text("이름 (선택)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = b.result != null && !state.adding,
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(repo, { repo = it }, singleLine = true, enabled = !state.adding,
                    label = { Text("리포 URL (예: https://github.com/…)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(branch, { branch = it }, singleLine = true, enabled = !state.adding,
                    label = { Text("브랜치 (선택)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(label, { label = it }, singleLine = true, enabled = !state.adding,
                    label = { Text("이름 (선택)") }, modifier = Modifier.fillMaxWidth())
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
                onClick = { vm.clone(repo, branch, label, onDismiss) },
            ) { Text("클론") }
        },
        dismissButton = { TextButton(enabled = !state.adding, onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailSheet(d: DetailUiState, vm: WorkspacesViewModel) {
    var showCommit by remember { mutableStateOf(false) }
    var showPush by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val ws = d.workspace
    val isRepo = d.status?.isRepo ?: ws.isRepo

    ModalBottomSheet(onDismissRequest = { vm.closeDetail() }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ws.label, style = MaterialTheme.typography.titleLarge)
            Text(ws.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ws.repoUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            d.error?.let { ErrorBanner(it, onRetry = { vm.loadStatus() }) }
            d.info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            d.push?.let { p ->
                Column {
                    Text("승인 탭에서 승인하면 푸시됩니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "승인 #${p.approvalId ?: "?"} · 상태 ${p.status ?: "대기"}" + (p.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
            when {
                d.statusLoading && d.status == null -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                d.status != null && !d.status.isRepo -> Text("git 저장소가 아닌 폴더입니다. 커밋/푸시를 쓸 수 없습니다.", style = MaterialTheme.typography.bodyMedium)
                d.status != null -> {
                    val s = d.status
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = {}, label = { Text("브랜치 ${s.branch ?: "?"}") })
                        FilterChip(selected = s.dirty, onClick = {}, label = { Text(if (s.dirty) "변경 있음" else "깨끗함") })
                    }
                    if (s.files.isNotEmpty()) {
                        Text("변경된 파일 (${s.files.size})", style = MaterialTheme.typography.labelLarge)
                        Column(Modifier.heightIn(max = 180.dp)) {
                            LazyColumn {
                                items(s.files) { f ->
                                    Text(f, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = isRepo && !d.busy && d.status?.dirty == true, onClick = { showCommit = true }) { Text("커밋") }
                OutlinedButton(enabled = isRepo && !d.busy, onClick = { showPush = true }) { Text("푸시 요청") }
                if (d.busy) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
            }
            TextButton(enabled = !d.busy, onClick = { showRemove = true }) {
                Text("목록에서 제거", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showCommit) {
        var msg by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCommit = false },
            title = { Text("커밋") },
            text = { OutlinedTextField(msg, { msg = it }, label = { Text("커밋 메시지") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(enabled = msg.isNotBlank(), onClick = { showCommit = false; vm.commit(msg) }) { Text("커밋") }
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("바로 푸시되지 않고 승인 항목이 만들어집니다. 승인 탭에서 승인하면 푸시됩니다.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(branch, { branch = it }, singleLine = true, label = { Text("브랜치 (선택)") }, modifier = Modifier.fillMaxWidth())
                    if (d.bots.isNotEmpty()) {
                        Text("요청 봇 (선택)", style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            confirmButton = { Button(onClick = { showPush = false; vm.requestPush(branch, botId) }) { Text("요청") } },
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
