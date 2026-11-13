package com.hermes.chatandroid.ui.bots.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.ui.bots.BotAvatar
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox

/**
 * 봇 프로필 화면 (T-172).
 * - [onOpenSession]: 미사용(지난 세션은 2단계 add-on으로 이후 복원)
 * - [onOpenChannel]: 봇 DM 등 채널 채팅 열기
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun BotProfileScreen(
    container: AppContainer,
    botId: Int,
    onBack: () -> Unit,
    onOpenSession: (port: Int, sessionId: String) -> Unit,
    onOpenChannel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: BotProfileViewModel = viewModel(key = "botProfile$botId", factory = BotProfileViewModel.Factory(container, botId))
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var archiveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(st.message) {
        st.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(st.bot?.displayName ?: "봇 프로필") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        val bot = st.bot
        when {
            bot == null && st.loading -> LoadingBox(Modifier.padding(padding))
            bot == null -> Column(Modifier.padding(padding)) {
                ErrorBanner(st.error ?: "봇을 불러오지 못했습니다", onRetry = vm::loadBot)
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item { Header(bot, st.healthRefreshing, vm::refreshHealth, onOpenChannel) }
                item { EditSection(st, vm, onArchive = { archiveDialog = true }) }
            }
        }
    }

    val b = st.bot
    if (archiveDialog && b != null) {
        val toArchive = !b.isArchived
        AlertDialog(
            onDismissRequest = { archiveDialog = false },
            title = { Text(if (toArchive) "봇 보관" else "보관 해제") },
            text = { Text(if (toArchive) "${b.displayName} 을(를) 보관할까요? 목록에서 숨겨지며 언제든 해제할 수 있습니다." else "${b.displayName} 의 보관을 해제할까요?") },
            confirmButton = { TextButton(onClick = { archiveDialog = false; vm.setArchived(toArchive) }) { Text(if (toArchive) "보관" else "해제") } },
            dismissButton = { TextButton(onClick = { archiveDialog = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Header(bot: Bot, refreshing: Boolean, onRefresh: () -> Unit, onOpenChannel: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BotAvatar(bot.avatarText, bot.tone, size = 88.dp)
        Text(bot.displayName, style = MaterialTheme.typography.headlineSmall)
        Text("@${bot.profileName ?: bot.slug}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (bot.isOnline) Color(0xFF3FB68B) else Color(0xFF9E9E9E)))
            Text(
                if (bot.isOnline) "  온라인" else "  오프라인",
                style = MaterialTheme.typography.bodyMedium,
            )
            bot.gatewayPort?.let { Text("  ·  게이트웨이 :$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (bot.isArchived) Text("  ·  보관됨", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Text("  헬스 새로고침")
        }
        bot.soulSummary?.takeIf { it.isNotBlank() }?.let { soul ->
            Text(
                soul,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            )
            Text(if (expanded) "접기" else "펼치기", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded })
        }
        val dm = bot.dmChannelId
        Button(onClick = { dm?.let(onOpenChannel) }, enabled = dm != null, modifier = Modifier.fillMaxWidth()) { Text("대화하기") }
    }
    HorizontalDivider()
}

@Composable
private fun EditSection(st: BotProfileState, vm: BotProfileViewModel, onArchive: () -> Unit) {
    val f = st.form
    val bot = st.bot ?: return
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("설정", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(f.name, { v -> vm.edit { it.copy(name = v) } }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.avatar, { v -> vm.edit { it.copy(avatar = v) } }, label = { Text("아바타 (이모지)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            f.personaNote, { v -> vm.edit { it.copy(personaNote = v) } },
            label = { Text("페르소나 메모") },
            supportingText = { Text("프롬프트에 보강으로만 추가됩니다. SOUL.md 는 덮어쓰지 않습니다.") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        var menu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                val label = ApprovalPolicies.firstOrNull { it.first == f.approvalPolicy }?.second ?: f.approvalPolicy
                Text("승인 정책: $label", Modifier.weight(1f))
            }
            DropdownMenu(menu, { menu = false }) {
                ApprovalPolicies.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { vm.edit { it.copy(approvalPolicy = value) }; menu = false })
                }
            }
        }
        if (f.approvalPolicy == "ask" && !f.readOnly) {
            Text(
                "승인 요청은 지시(프롬프트)일 뿐 강제가 아닙니다. 읽기 전용을 함께 켜는 것을 권장합니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwitchRow("읽기 전용", f.readOnly) { v -> vm.edit { it.copy(readOnly = v) } }
        SwitchRow("컴퓨터 사용", f.computerUse) { v -> vm.edit { it.copy(computerUse = v) } }
        OutlinedTextField(f.workdir, { v -> vm.edit { it.copy(workdir = v) } }, label = { Text("작업 폴더") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = vm::save, enabled = st.dirty && !st.saving, modifier = Modifier.fillMaxWidth()) {
            if (st.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("저장")
        }
        OutlinedButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) { Text(if (bot.isArchived) "보관 해제" else "봇 보관") }
    }
    HorizontalDivider()
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
