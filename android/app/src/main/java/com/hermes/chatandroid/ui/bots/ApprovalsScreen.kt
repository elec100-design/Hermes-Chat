package com.hermes.chatandroid.ui.bots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.components.readableWidth
import com.hermes.chatandroid.ui.theme.hermesColors
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
    LaunchedEffect(Unit) { vm.refresh(initial = true) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    val hc = MaterialTheme.hermesColors

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("승인 요청", style = MaterialTheme.typography.headlineSmall)
                        if (!state.loading && state.items.isNotEmpty()) {
                            StatusPill(
                                text = "${state.items.size}건 대기",
                                containerColor = hc.warningContainer,
                                contentColor = hc.onWarningContainer,
                                dotColor = hc.warning,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.refreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "새로고침")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxHeight().readableWidth()) {
            state.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
            when {
                state.loading -> LoadingBox(label = "승인 대기열을 확인하는 중…")
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                    if (state.items.isEmpty()) {
                        EmptyState(
                            title = "대기 중인 승인이 없습니다",
                            subtitle = "봇이 위험도가 있거나 확인이 필요한 작업을 요청하면 이곳에 표시됩니다.",
                            icon = Icons.Rounded.VerifiedUser,
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            icon = {
                Icon(
                    if (p.approve) Icons.Rounded.Shield else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = if (p.approve) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(if (p.approve) "작업 승인" else "작업 거절") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(p.approval.title, style = MaterialTheme.typography.titleSmall)
                    HermesTextField(note, { note = it }, label = "메모 (선택)", singleLine = false, maxLines = 3)
                    if (p.approve) {
                        Text(
                            "승인 후 서버 실행에는 최대 200초가 걸릴 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.decide(p.approval.id, p.approve, note.ifBlank { null })
                        pending = null
                    },
                    shape = CircleShape,
                    colors = if (p.approve) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(if (p.approve) "승인 실행" else "거절") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ApprovalCard(a: BotApproval, busy: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    val hc = MaterialTheme.hermesColors
    val riskNorm = a.risk?.lowercase()
    val (riskBg, riskFg, riskDot) = when (riskNorm) {
        "high", "높음" -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error)
        "medium", "보통" -> Triple(hc.warningContainer, hc.onWarningContainer, hc.warning)
        else -> Triple(hc.online.copy(alpha = 0.14f), hc.online, hc.online)
    }

    HermesCard(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                a.kind?.let {
                    StatusPill(
                        text = it.uppercase(),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
                a.risk?.let {
                    StatusPill(
                        text = "위험도 $it",
                        containerColor = riskBg,
                        contentColor = riskFg,
                        dotColor = riskDot,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "#${a.id}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(a.title, style = MaterialTheme.typography.titleMedium)

            a.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            a.action?.takeIf { it.isNotBlank() }?.let { cmd ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = hc.codeSurface,
                    border = BorderStroke(1.dp, hc.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            cmd,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            if (busy) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "승인 작업 처리 중… (최대 200초)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(17.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("거절")
                    }
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(17.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("승인")
                    }
                }
            }
        }
    }
}
