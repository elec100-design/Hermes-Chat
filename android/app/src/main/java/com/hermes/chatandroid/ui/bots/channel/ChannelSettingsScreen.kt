package com.hermes.chatandroid.ui.bots.channel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.hermes.chatandroid.data.BotMember
import com.hermes.chatandroid.data.ChannelHumanMember
import com.hermes.chatandroid.data.ChannelInvite
import com.hermes.chatandroid.data.ChannelPatch
import com.hermes.chatandroid.ui.bots.BotAvatar
import com.hermes.chatandroid.ui.bots.botErrorText
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.readableWidth
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChannelSettingsUiState(
    val loading: Boolean = true,
    val channel: BotChannel? = null,
    val title: String = "",
    val topic: String = "",
    val workdir: String = "",
    val botChat: Boolean = false,
    val guestBotMode: String = "read_only",
    val saving: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val candidates: List<Bot> = emptyList(),
    val invites: List<ChannelInvite> = emptyList(),
    val humanMembers: List<ChannelHumanMember> = emptyList(),
    val latestInviteUrl: String? = null,
    val inviteBusy: Boolean = false,
) {
    val dirty: Boolean
        get() {
            val c = channel ?: return false
            return title != c.title ||
                topic != (c.topic ?: "") ||
                workdir != (c.workdir ?: "") ||
                botChat != (c.botChat ?: false) ||
                guestBotMode != (c.guestBotMode ?: "read_only")
        }
}

class ChannelSettingsViewModel(private val container: AppContainer, private val channelId: Int) : ViewModel() {
    private val _state = MutableStateFlow(ChannelSettingsUiState())
    val state = _state.asStateFlow()

    private fun ChannelSettingsUiState.withChannel(c: BotChannel) = copy(
        loading = false, channel = c, title = c.title, topic = c.topic ?: "",
        workdir = c.workdir ?: "", botChat = c.botChat ?: false,
        guestBotMode = c.guestBotMode ?: "read_only", error = null,
    )

    fun load() {
        val api = container.botApi()
        if (api == null) {
            _state.update { it.copy(loading = false, error = "봇 서버가 설정되지 않았습니다.") }
            return
        }
        viewModelScope.launch {
            try {
                val c = api.fetchChannels().firstOrNull { it.id == channelId }
                if (c == null) _state.update { it.copy(loading = false, error = "채널을 찾을 수 없습니다.") }
                else {
                    _state.update { it.withChannel(c) }
                    loadInvitesAndUsers()
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = botErrorText(e)) }
            }
        }
    }

    fun loadInvitesAndUsers() {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            val invites = runCatching { api.fetchChannelInvites(channelId) }.getOrDefault(emptyList())
            val users = runCatching { api.fetchChannelUsers(channelId) }.getOrDefault(emptyList())
            _state.update { it.copy(invites = invites, humanMembers = users) }
        }
    }

    fun createInvite(label: String, maxUses: Int?, expiresHours: Int?) {
        val api = container.botApi() ?: return
        if (_state.value.inviteBusy) return
        _state.update { it.copy(inviteBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val created = api.createChannelInvite(channelId, label, maxUses, expiresHours)
                _state.update { it.copy(inviteBusy = false, latestInviteUrl = created.inviteUrl ?: created.inviteCode) }
                loadInvitesAndUsers()
            } catch (e: Exception) {
                _state.update { it.copy(inviteBusy = false, error = botErrorText(e)) }
            }
        }
    }

    fun revokeInvite(inviteId: Int) {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                api.revokeChannelInvite(channelId, inviteId)
                loadInvitesAndUsers()
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }

    fun kickHumanUser(userId: Int) {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                api.removeChannelUser(channelId, userId)
                loadInvitesAndUsers()
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }

    /** 멤버 변경 후: 편집 중인 입력은 유지하고 멤버/원본만 갱신. */
    private suspend fun reloadMembers() {
        val api = container.botApi() ?: return
        val c = api.fetchChannels().firstOrNull { it.id == channelId } ?: return
        _state.update { it.copy(channel = c) }
    }

    fun edit(
        title: String? = null,
        topic: String? = null,
        workdir: String? = null,
        botChat: Boolean? = null,
        guestBotMode: String? = null,
    ) {
        _state.update {
            it.copy(
                title = title ?: it.title, topic = topic ?: it.topic,
                workdir = workdir ?: it.workdir, botChat = botChat ?: it.botChat,
                guestBotMode = guestBotMode ?: it.guestBotMode,
            )
        }
    }

    fun save() {
        val api = container.botApi() ?: return
        val s = _state.value
        val c = s.channel ?: return
        if (s.saving || s.title.isBlank()) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val updated = api.updateChannel(
                    channelId,
                    ChannelPatch(
                        title = s.title.trim().takeIf { it != c.title },
                        topic = s.topic.takeIf { it != (c.topic ?: "") },
                        workdir = s.workdir.trim().takeIf { it != (c.workdir ?: "") },
                        botChat = s.botChat.takeIf { it != (c.botChat ?: false) },
                        guestBotMode = s.guestBotMode.takeIf { it != (c.guestBotMode ?: "read_only") },
                    ),
                )
                // 응답에는 members 가 없으므로 기존 멤버 유지
                val merged = updated.copy(members = c.members, preview = c.preview)
                _state.update { it.withChannel(merged).copy(saving = false) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = botErrorText(e)) }
            }
        }
    }

    fun removeMember(botId: Int) = memberOp { it.removeChannelMember(channelId, botId) }

    fun addMember(botId: Int) = memberOp { it.addChannelMember(channelId, botId) }

    private fun memberOp(op: suspend (com.hermes.chatandroid.net.BotApi) -> Unit) {
        val api = container.botApi() ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                op(api)
                reloadMembers()
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun loadCandidates() {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                val bots = api.fetchBots(activeOnly = true)
                _state.update { it.copy(candidates = bots) }
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val api = container.botApi() ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                api.deleteChannel(channelId)
                _state.update { it.copy(busy = false) }
                onDeleted()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = botErrorText(e)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsScreen(
    container: AppContainer,
    channelId: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ChannelSettingsViewModel = viewModel(
        key = "channel-settings-$channelId",
        factory = viewModelFactory { initializer { ChannelSettingsViewModel(container, channelId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    var removing by remember { mutableStateOf<BotMember?>(null) }
    var kickingUser by remember { mutableStateOf<ChannelHumanMember?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val hc = MaterialTheme.hermesColors

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("채널 설정", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        val channel = state.channel
        when {
            state.loading -> LoadingBox(Modifier.padding(padding).fillMaxSize(), "채널 설정을 불러오는 중…")
            channel == null -> Column(Modifier.padding(padding).fillMaxHeight().readableWidth().padding(16.dp)) {
                ErrorBanner(state.error ?: "채널을 불러오지 못했습니다.", onRetry = vm::load)
            }
            else -> Column(
                Modifier.padding(padding).fillMaxHeight().readableWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                state.error?.let { ErrorBanner(it) }

                HermesCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("채널 정보", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        HermesTextField(
                            value = state.title,
                            onValueChange = { vm.edit(title = it) },
                            label = "제목",
                            isError = state.title.isBlank(),
                        )
                        HermesTextField(
                            value = state.topic,
                            onValueChange = { vm.edit(topic = it) },
                            label = "주제",
                            singleLine = false,
                            minLines = 2,
                            maxLines = 6,
                        )
                        HermesTextField(
                            value = state.workdir,
                            onValueChange = { vm.edit(workdir = it) },
                            label = "작업 폴더",
                        )
                        HorizontalDivider(color = hc.subtleDivider)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("봇끼리 대화(연쇄)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "켜면 봇의 답변에 다른 봇이 이어서 반응합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = state.botChat, onCheckedChange = { vm.edit(botChat = it) })
                        }
                        HorizontalDivider(color = hc.subtleDivider)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("게스트 @봇 호출 정책", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "read_only" to "읽기 전용",
                                    "mention_off" to "멘션 끄기",
                                    "full" to "전체 허용",
                                ).forEach { (mode, label) ->
                                    val selected = state.guestBotMode == mode
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { vm.edit(guestBotMode = mode) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else hc.cardBorder,
                                        ),
                                    ) {
                                        Text(
                                            label,
                                            Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = vm::save,
                            enabled = state.dirty && !state.saving && state.title.isNotBlank(),
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        ) { Text(if (state.saving) "저장 중…" else "변경 사항 저장") }
                    }
                }

                HermesCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "참여 멤버 (${channel.members.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.loadCandidates(); showAdd = true }, enabled = !state.busy) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("추가")
                            }
                        }
                        if (channel.members.isEmpty()) {
                            Text("참여 중인 봇이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            channel.members.forEachIndexed { idx, m ->
                                if (idx > 0) HorizontalDivider(color = hc.subtleDivider)
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    BotAvatar(
                                        m.avatar?.takeIf { it.isNotEmpty() } ?: "🤖",
                                        m.tone,
                                        size = 38.dp,
                                        online = m.health == "online",
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text("@${m.slug}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { removing = m }, enabled = !state.busy) {
                                        Icon(Icons.Rounded.Close, contentDescription = "${m.displayName} 제거")
                                    }
                                }
                            }
                        }
                    }
                }

                HermesCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "참여 사용자 (${state.humanMembers.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showInviteDialog = true }, enabled = !state.inviteBusy) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("초대 링크")
                            }
                        }
                        state.latestInviteUrl?.let { url ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("새 초대 링크가 생성되었습니다", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(url, style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(
                                        onClick = {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                                            android.widget.Toast.makeText(context, "초대 링크를 복사했습니다", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = CircleShape,
                                    ) { Text("링크 복사") }
                                }
                            }
                        }
                        if (state.humanMembers.isEmpty()) {
                            Text("초대된 외부 사용자가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            state.humanMembers.forEachIndexed { idx, u ->
                                if (idx > 0) HorizontalDivider(color = hc.subtleDivider)
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🧑 ${u.displayName}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    if (u.role == "owner") {
                                        Text("관리자", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        IconButton(onClick = { kickingUser = u }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "${u.displayName} 내보내기")
                                        }
                                    }
                                }
                            }
                        }
                        val activeInvites = state.invites.filter { !it.revoked }
                        if (activeInvites.isNotEmpty()) {
                            HorizontalDivider(color = hc.subtleDivider)
                            Text("활성 초대 링크 (${activeInvites.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            activeInvites.forEach { inv ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(inv.label.ifBlank { "초대 #${inv.id}" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text(
                                            buildString {
                                                append(if (inv.maxUses != null) "${inv.usesCount}/${inv.maxUses}회" else "${inv.usesCount}회 사용")
                                                inv.expiresAt?.let { append(" · 만료 ${it.take(10)}") }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { vm.revokeInvite(inv.id) }) { Text("폐기") }
                                }
                            }
                        }
                    }
                }

                HermesCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("위험 구역", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(
                            "채널을 삭제하면 대화 기록도 함께 사라지며 되돌릴 수 없습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = !state.busy,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("채널 삭제")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("멤버 제거") },
            text = { Text("${m.displayName} 님을 이 채널에서 제거할까요?") },
            confirmButton = { TextButton(onClick = { vm.removeMember(m.id); removing = null }) { Text("제거") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } },
        )
    }
    kickingUser?.let { u ->
        AlertDialog(
            onDismissRequest = { kickingUser = null },
            title = { Text("사용자 내보내기") },
            text = { Text("${u.displayName} 님을 채널에서 내보내고 초대 토큰을 해제할까요?") },
            confirmButton = {
                TextButton(onClick = { vm.kickHumanUser(u.id); kickingUser = null }) {
                    Text("내보내기", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { kickingUser = null }) { Text("취소") } },
        )
    }
    if (showInviteDialog) {
        var label by remember { mutableStateOf("") }
        var maxUsesText by remember { mutableStateOf("") }
        var expiresHoursText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("새 초대 링크 만들기") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HermesTextField(label, { label = it }, label = "라벨 (선택)", placeholder = "예: 팀원 초대")
                    HermesTextField(maxUsesText, { maxUsesText = it.filter(Char::isDigit) }, label = "최대 사용 횟수 (비우면 무제한)")
                    HermesTextField(expiresHoursText, { expiresHoursText = it.filter(Char::isDigit) }, label = "유효 시간(시간, 비우면 무제한)")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createInvite(
                        label = label,
                        maxUses = maxUsesText.toIntOrNull()?.takeIf { it > 0 },
                        expiresHours = expiresHoursText.toIntOrNull()?.takeIf { it > 0 },
                    )
                    showInviteDialog = false
                }) { Text("생성") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("취소") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("채널 삭제") },
            text = { Text("이 채널과 모든 메시지가 삭제됩니다. 계속할까요?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onDeleted) }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
    if (showAdd) {
        val memberIds = state.channel?.members?.map { it.id }?.toSet().orEmpty()
        val available = state.candidates.filter { it.id !in memberIds }
        ModalBottomSheet(onDismissRequest = { showAdd = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("멤버 추가", style = MaterialTheme.typography.titleLarge)
                if (available.isEmpty()) {
                    Text("추가할 수 있는 봇이 없습니다.", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                available.forEach { b ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { vm.addMember(b.id); showAdd = false },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, hc.cardBorder),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            BotAvatar(b.avatar?.takeIf { it.isNotEmpty() } ?: "🤖", b.tone, size = 38.dp, online = b.isOnline)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("@${b.slug}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
