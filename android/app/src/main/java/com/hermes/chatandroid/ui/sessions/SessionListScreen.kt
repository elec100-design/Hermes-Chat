package com.hermes.chatandroid.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Session
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.SearchPillField
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

    LaunchedEffect(profile, settings.host, settings.apiKey) { vm.load(profile) }

    val filtered = remember(state.sessions, query) {
        if (query.isBlank()) state.sessions else state.sessions.filter { it.displayTitle.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Box {
                        Row(
                            Modifier.clickable(onClick = { menuOpen = true }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(profile?.name ?: "프로필 없음", style = MaterialTheme.typography.headlineSmall)
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "프로필 선택")
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            settings.visibleProfiles.forEach { p ->
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
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.create(onOpenSession) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "새 세션")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchPillField(
                value = query,
                onValueChange = { query = it },
                placeholder = "세션 검색",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            state.error?.let { ErrorBanner(it, onRetry = { vm.dismissError(); vm.refresh() }) }
            PullToRefreshBox(
                isRefreshing = state.loading && state.sessions.isNotEmpty(),
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading && state.sessions.isEmpty() -> LoadingBox(label = "세션 목록을 불러오는 중…")
                    filtered.isEmpty() -> EmptyState(
                        if (state.sessions.isEmpty()) "세션이 없습니다" else "검색 결과가 없습니다",
                        subtitle = if (state.sessions.isEmpty()) "+ 버튼으로 새 대화를 시작하세요" else null,
                        icon = Icons.AutoMirrored.Rounded.Chat,
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(filtered, key = { _, s -> s.id }) { i, s ->
                            if (query.isBlank() && i >= filtered.size - 5) LaunchedEffect(filtered.size) { vm.loadMore() }
                            HermesCard(
                                onClick = { onOpenSession(s.id) },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(s.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (s.updatedAt > 0) {
                                            Text(
                                                formatTime(s.updatedAt),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton({ renaming = s }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "이름 변경", modifier = Modifier.size(19.dp))
                                    }
                                    IconButton({ deleting = s }) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "삭제", modifier = Modifier.size(19.dp))
                                    }
                                }
                            }
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
            text = { HermesTextField(text, { text = it }, label = "세션 제목") },
            confirmButton = {
                Button(onClick = { vm.rename(s.id, text.trim()); renaming = null }, enabled = text.isNotBlank(), shape = CircleShape) { Text("저장") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("취소") } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("세션 삭제") },
            text = { Text("\"${s.displayTitle}\" 세션을 삭제할까요? 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton({ vm.delete(s.id); deleting = null }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("취소") } },
        )
    }
}

private fun formatTime(epochSec: Double): String =
    SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date((epochSec * 1000).toLong()))
