package com.hermes.chatandroid.ui.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.BotApproval
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ApprovalsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<BotApproval> = emptyList(),
    /** 처리 중인 승인 id (승인은 최대 200초) */
    val busy: Set<Int> = emptySet(),
    val error: String? = null,
)

class ApprovalsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ApprovalsUiState())
    val state = _state.asStateFlow()

    fun refresh(initial: Boolean = false) {
        val api = container.botApi()
        if (api == null) {
            _state.update { it.copy(loading = false, refreshing = false, error = "봇 서버가 설정되지 않았습니다.") }
            return
        }
        _state.update { it.copy(refreshing = !initial) }
        viewModelScope.launch {
            try {
                val list = api.fetchApprovals()
                _state.update { it.copy(loading = false, refreshing = false, items = list, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = botErrorText(e)) }
            }
        }
    }

    fun decide(id: Int, approve: Boolean, note: String?) {
        val api = container.botApi() ?: return
        if (id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + id, error = null) }
        viewModelScope.launch {
            try {
                if (approve) api.approve(id, note) else api.reject(id, note)
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
            _state.update { it.copy(busy = it.busy - id) }
            refresh()
        }
    }
}

private data class Pending(val approval: BotApproval, val approve: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    /** null 이면 탭 화면(뒤로 버튼 없음). */
    onBack: (() -> Unit)? = null,
) {
    val vm: ApprovalsViewModel = viewModel(factory = viewModelFactory { initializer { ApprovalsViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    // 탭으로 돌아올 때마다 조용히 갱신(첫 진입 포함)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh(initial = true) }
    var pending by remember { mutableStateOf<Pending?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("승인 요청") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
            when {
                state.loading -> LoadingBox()
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                    if (state.items.isEmpty()) {
                        EmptyState("대기 중인 승인이 없습니다")
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.items, key = { it.id }) { a ->
                                ApprovalCard(
                                    a,
                                    busy = a.id in state.busy,
                                    onApprove = { pending = Pending(a, true) },
                                    onReject = { pending = Pending(a, false) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        var note by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (p.approve) "승인" else "거절") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.approval.title, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(note, { note = it }, label = { Text("메모 (선택)") })
                    if (p.approve) Text("승인 실행에는 최대 200초가 걸릴 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.decide(p.approval.id, p.approve, note.ifBlank { null })
                    pending = null
                }) { Text(if (p.approve) "승인" else "거절") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ApprovalCard(a: BotApproval, busy: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                a.kind?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                a.risk?.let {
                    Text(
                        "위험도 $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (it.lowercase() in setOf("high", "높음")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(a.title, style = MaterialTheme.typography.titleSmall)
            a.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("처리 중… (최대 200초)", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    OutlinedButton(onClick = onReject) { Text("거절") }
                    Button(onClick = onApprove) { Text("승인") }
                }
            }
        }
    }
}
