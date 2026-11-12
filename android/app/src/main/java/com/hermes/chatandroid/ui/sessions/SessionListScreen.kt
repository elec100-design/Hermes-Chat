package com.hermes.chatandroid.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Session
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    container: AppContainer,
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SessionListViewModel = viewModel(factory = SessionListViewModel.Factory(container))
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val profile = settings.currentProfile

    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }

    // 프로필/호스트/키가 바뀌면 재로딩
    LaunchedEffect(profile, settings.host, settings.apiKey) { vm.load(profile) }

    val filtered = remember(state.sessions, query) {
        if (query.isBlank()) state.sessions else state.sessions.filter { it.displayTitle.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = {
                Box {
                    Row0(onClick = { menuOpen = true }, label = profile?.name ?: "프로필 없음")
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        settings.profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.name} (:${p.port})") },
                                onClick = {
                                    menuOpen = false
                                    repo.update { it.copy(selectedProfile = p.name) }
                                },
                            )
                        }
                    }
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.create(onOpenSession) }) {
                Icon(Icons.Filled.Add, contentDescription = "새 세션")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("세션 검색") }, singleLine = true,
            )
            state.error?.let { ErrorBanner(it, onRetry = { vm.dismissError(); vm.refresh() }) }
            PullToRefreshBox(
                isRefreshing = state.loading && state.sessions.isNotEmpty(),
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading && state.sessions.isEmpty() -> LoadingBox()
                    filtered.isEmpty() -> EmptyState(
                        if (state.sessions.isEmpty()) "세션이 없습니다" else "검색 결과가 없습니다",
                        subtitle = if (state.sessions.isEmpty()) "+ 버튼으로 새 대화를 시작하세요" else null,
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(filtered, key = { _, s -> s.id }) { i, s ->
                            if (query.isBlank() && i >= filtered.size - 5) LaunchedEffect(filtered.size) { vm.loadMore() }
                            ListItem(
                                headlineContent = { Text(s.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    if (s.updatedAt > 0) Text(formatTime(s.updatedAt))
                                },
                                trailingContent = {
                                    Row0Actions(
                                        onRename = { renaming = s },
                                        onDelete = { deleting = s },
                                    )
                                },
                                modifier = Modifier.clickable { onOpenSession(s.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.title ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("이름 변경") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.rename(s.id, text.trim()); renaming = null }, enabled = text.isNotBlank()) { Text("저장") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("취소") } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("세션 삭제") },
            text = { Text("\"${s.displayTitle}\" 세션을 삭제할까요? 되돌릴 수 없습니다.") },
            confirmButton = { TextButton({ vm.delete(s.id); deleting = null }) { Text("삭제") } },
            dismissButton = { TextButton({ deleting = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun Row0(onClick: () -> Unit, label: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "프로필 선택")
    }
}

@Composable
private fun Row0Actions(onRename: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.foundation.layout.Row {
        IconButton(onRename) { Icon(Icons.Filled.Edit, contentDescription = "이름 변경") }
        IconButton(onDelete) { Icon(Icons.Filled.Delete, contentDescription = "삭제") }
    }
}

private fun formatTime(epochSec: Double): String =
    SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date((epochSec * 1000).toLong()))
