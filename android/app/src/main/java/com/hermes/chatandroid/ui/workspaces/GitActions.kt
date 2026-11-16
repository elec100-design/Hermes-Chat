package com.hermes.chatandroid.ui.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
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
import com.hermes.chatandroid.data.GitAction
import com.hermes.chatandroid.data.GitResult
import com.hermes.chatandroid.data.GitStatus
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GitUiState(
    val loading: Boolean = false,
    val status: GitStatus? = null,
    /** 상태를 못 읽은 이유(Bridge 미설정·git 저장소 아님 등). */
    val unavailable: String? = null,
    val running: GitAction? = null,
    val result: GitResult? = null,
    val resultTitle: String? = null,
    val error: String? = null,
)

/** 작업 폴더 하나의 git 상태·동작. 배포는 몇 분 걸릴 수 있어 화면 회전·폴드에도 유지되게 ViewModel 로 둔다. */
class GitActionsViewModel(private val container: AppContainer, private val workdir: String) : ViewModel() {
    private val _state = MutableStateFlow(GitUiState())
    val state = _state.asStateFlow()

    fun refresh() {
        val api = container.bridgeApi() ?: run {
            _state.update { it.copy(unavailable = "Bridge 가 설정되지 않았습니다 (설정 > Bridge)") }
            return
        }
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val s = api.fetchGitStatus(workdir)
                _state.update { it.copy(loading = false, status = s, unavailable = null) }
            } catch (e: Exception) {
                val why = if (e is ApiException && e.code == 404) "git 저장소가 아닌 폴더입니다" else messageOf(e)
                _state.update { it.copy(loading = false, status = null, unavailable = why) }
            }
        }
    }

    fun run(action: GitAction, message: String? = null) {
        val api = container.bridgeApi() ?: return
        if (_state.value.running != null) return
        _state.update { it.copy(running = action, error = null, result = null) }
        viewModelScope.launch {
            try {
                val r = api.gitAction(workdir, action, message)
                _state.update { it.copy(running = null, result = r, resultTitle = "${action.label} 완료") }
            } catch (e: Exception) {
                _state.update { it.copy(running = null, error = messageOf(e)) }
            }
            refresh()
        }
    }

    fun dismissResult() = _state.update { it.copy(result = null, resultTitle = null, error = null) }

    private fun messageOf(e: Exception) = e.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"
}

/**
 * 채팅 오른쪽 위 git 버튼(T-199) — 커밋·풀·푸시·PR·병합·배포.
 * 원격에 닿는 동작은 확인 창을 거친 뒤 Bridge 가 맥미니의 git/gh 자격증명으로 실행한다.
 */
@Composable
fun GitMenuButton(container: AppContainer, workdir: String, showLabel: Boolean, modifier: Modifier = Modifier) {
    val vm: GitActionsViewModel = viewModel(
        key = "git:$workdir",
        factory = viewModelFactory { initializer { GitActionsViewModel(container, workdir) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<GitAction?>(null) }
    var commitDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val status = state.status
    val busy = state.running != null

    Box(modifier) {
        Surface(
            onClick = { open = true; vm.refresh() },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.hermesColors.cardBorder),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Commit, contentDescription = "git 작업", modifier = Modifier.size(18.dp))
                if (showLabel) {
                    Text(
                        state.running?.let { "${it.label} 중…" } ?: status?.branch?.ifBlank { null } ?: "Git",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).widthIn(min = 200.dp, max = 280.dp)) {
                Text(
                    when {
                        status != null -> status.summary
                        state.loading -> "상태 확인 중…"
                        else -> state.unavailable ?: "상태를 읽지 못했습니다"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status == null && !state.loading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    workdir,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = MaterialTheme.hermesColors.subtleDivider)
            val ready = status != null && !busy
            GitItem("커밋…", Icons.Rounded.Commit, ready && status?.dirty == true) { open = false; commitDialog = true }
            GitItem(
                "풀" + (status?.behind?.takeIf { it > 0 }?.let { " ↓$it" } ?: ""),
                Icons.Rounded.Download, ready && status?.hasUpstream == true,
            ) { open = false; vm.run(GitAction.PULL) }
            GitItem(
                "푸시" + (status?.ahead?.takeIf { it > 0 }?.let { " ↑$it" } ?: ""),
                Icons.Rounded.Upload, ready && status?.hasRemote == true,
            ) { open = false; confirm = GitAction.PUSH }
            HorizontalDivider(color = MaterialTheme.hermesColors.subtleDivider)
            val pr = status?.pr
            // 열린 PR 이 있으면 보기, 없거나 이미 병합·닫힌 PR 이면 새로 만들기
            if (pr?.url != null && pr.isOpen) {
                GitItem("PR 보기 #${pr.number ?: "?"}", Icons.AutoMirrored.Rounded.OpenInNew, true) {
                    open = false
                    runCatching { uriHandler.openUri(pr.url) }
                }
            } else {
                // 기본 브랜치에서는 PR 을 만들 수 없다
                GitItem("PR 만들기", Icons.AutoMirrored.Rounded.OpenInNew, ready && status?.hasRemote == true && status?.onDefaultBranch == false) {
                    open = false; confirm = GitAction.PR
                }
            }
            GitItem("병합", Icons.AutoMirrored.Rounded.CallMerge, ready && pr?.isOpen == true) { open = false; confirm = GitAction.MERGE }
            GitItem(
                "배포" + if (status != null && status.deployScript == null) " (scripts/deploy.sh 없음)" else "",
                Icons.Rounded.RocketLaunch, ready && status?.deployScript != null,
            ) { open = false; confirm = GitAction.DEPLOY }
            HorizontalDivider(color = MaterialTheme.hermesColors.subtleDivider)
            GitItem("새로고침", Icons.Rounded.Refresh, !state.loading) { vm.refresh() }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(action.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(action.confirm.orEmpty())
                    Text(
                        listOfNotNull(
                            status?.branch?.let { "브랜치 $it" },
                            status?.pr?.takeIf { action == GitAction.MERGE }?.let { "PR #${it.number} ${it.title.orEmpty()}" },
                            status?.deployScript?.takeIf { action == GitAction.DEPLOY },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(shape = CircleShape, onClick = { confirm = null; vm.run(action) }) { Text(action.label) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
    if (commitDialog) {
        var msg by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { commitDialog = false },
            title = { Text("커밋") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "변경된 파일 ${status?.files?.size ?: 0}개를 모두 커밋합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HermesTextField(msg, { msg = it }, label = "커밋 메시지", singleLine = false, maxLines = 4)
                }
            },
            confirmButton = {
                Button(enabled = msg.isNotBlank(), shape = CircleShape, onClick = { commitDialog = false; vm.run(GitAction.COMMIT, msg) }) { Text("커밋") }
            },
            dismissButton = { TextButton(onClick = { commitDialog = false }) { Text("취소") } },
        )
    }
    val result = state.result
    val failure = state.error
    val resultUrl = result?.url
    if (result != null || failure != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissResult() },
            title = { Text(if (failure != null) "실패했습니다" else state.resultTitle ?: "완료") },
            text = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.hermesColors.codeSurface,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    Text(
                        (failure ?: result?.output).orEmpty().ifBlank { "출력 없음" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissResult() }) { Text("확인") } },
            dismissButton = if (resultUrl != null) {
                { TextButton(onClick = { runCatching { uriHandler.openUri(resultUrl) } }) { Text("GitHub 에서 열기") } }
            } else null,
        )
    }
}

@Composable
private fun GitItem(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        enabled = enabled,
        onClick = onClick,
    )
}
