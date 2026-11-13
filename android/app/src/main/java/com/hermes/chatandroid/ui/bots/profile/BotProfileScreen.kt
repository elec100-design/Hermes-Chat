package com.hermes.chatandroid.ui.bots.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.ui.bots.BotAvatar
import com.hermes.chatandroid.ui.bots.BotAvatarPreset
import com.hermes.chatandroid.ui.bots.BotAvatarPresets
import com.hermes.chatandroid.ui.bots.toneColor
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.theme.hermesColors

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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(st.bot?.displayName ?: "봇 프로필", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    if (st.bot != null) {
                        IconButton(onClick = vm::refreshHealth, enabled = !st.healthRefreshing) {
                            if (st.healthRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, contentDescription = "헬스 새로고침")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val bot = st.bot
        when {
            bot == null && st.loading -> LoadingBox(Modifier.padding(padding), "프로필을 불러오는 중…")
            bot == null -> Column(Modifier.padding(padding)) {
                ErrorBanner(st.error ?: "봇을 불러오지 못했습니다", onRetry = vm::loadBot)
            }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    val previewAvatar = st.form.avatar.takeIf { it.isNotBlank() } ?: "🤖"
                    HeaderCard(bot, previewAvatar, onOpenChannel)
                }
                item { IdentityCard(st, vm) }
                item { PermissionsCard(st, vm) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = vm::save,
                            enabled = st.dirty && !st.saving,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            if (st.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text("변경 사항 저장")
                        }
                        OutlinedButton(
                            onClick = { archiveDialog = true },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        ) {
                            Icon(if (bot.isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (bot.isArchived) "보관 해제" else "봇 보관")
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
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
            confirmButton = {
                TextButton(onClick = { archiveDialog = false; vm.setArchived(toArchive) }) {
                    Text(if (toArchive) "보관" else "해제", color = if (toArchive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { archiveDialog = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun HeaderCard(bot: Bot, previewAvatar: String, onOpenChannel: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val hc = MaterialTheme.hermesColors
    val accent = toneColor(bot.tone) ?: MaterialTheme.colorScheme.primary

    HermesCard(contentPadding = PaddingValues(0.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = if (hc.isDark) 0.18f else 0.11f),
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BotAvatar(previewAvatar, bot.tone, size = 84.dp, online = bot.isOnline)
                Text(bot.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "@${bot.profileName ?: bot.slug}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = if (bot.isOnline) "온라인" else "오프라인",
                        containerColor = if (bot.isOnline) hc.online.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (bot.isOnline) hc.online else MaterialTheme.colorScheme.onSurfaceVariant,
                        dotColor = if (bot.isOnline) hc.online else hc.offline,
                    )
                    bot.gatewayPort?.let {
                        StatusPill(
                            text = ":$it",
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (bot.isArchived) {
                        StatusPill(
                            text = "보관됨",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                bot.soulSummary?.takeIf { it.isNotBlank() }?.let { soul ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { expanded = !expanded },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, hc.cardBorder),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                soul,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (expanded) "접기" else "SOUL 요약 더보기",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                val dm = bot.dmChannelId
                Button(
                    onClick = { dm?.let(onOpenChannel) },
                    enabled = dm != null,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(46.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("대화하기")
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(st: BotProfileState, vm: BotProfileViewModel) {
    val f = st.form
    val hc = MaterialTheme.hermesColors
    val selectedPreset = BotAvatarPresets.find(f.avatar)
    val customEmoji = if (selectedPreset != null) "" else f.avatar

    HermesCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("기본 정보", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HermesTextField(f.name, { v -> vm.edit { it.copy(name = v) } }, label = "표시 이름")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "아바타 선택",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                selectedPreset != null -> "픽셀 · ${selectedPreset.label}"
                                f.avatar.isNotBlank() -> "이모지 · ${f.avatar}"
                                else -> "기본 (🤖)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (f.avatar.isNotBlank()) {
                            Text(
                                "초기화",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { vm.edit { it.copy(avatar = "") } }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                BotAvatarPresets.all.chunked(4).forEach { rowPresets ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            PixelAvatarOption(
                                preset = preset,
                                selected = selectedPreset?.id == preset.id,
                                cardBorderColor = hc.cardBorder,
                                onClick = { vm.edit { it.copy(avatar = preset.key) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                HermesTextField(
                    value = customEmoji,
                    onValueChange = { v -> vm.edit { it.copy(avatar = v) } },
                    label = "또는 이모지 직접 입력",
                    placeholder = "예: 🚀, 🧠 (입력 시 이모지로 전환)",
                )
            }

            HermesTextField(
                value = f.personaNote,
                onValueChange = { v -> vm.edit { it.copy(personaNote = v) } },
                label = "페르소나 메모",
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                supportingText = { Text("프롬프트에 보강으로만 추가됩니다. SOUL.md 는 덮어쓰지 않습니다.") },
            )
        }
    }
}

@Composable
private fun PixelAvatarOption(
    preset: BotAvatarPreset,
    selected: Boolean,
    cardBorderColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) primary else cardBorderColor),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box {
                Image(
                    bitmap = ImageBitmap.imageResource(preset.drawableRes),
                    contentDescription = preset.label,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                )
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-3).dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
            Text(
                preset.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PermissionsCard(st: BotProfileState, vm: BotProfileViewModel) {
    val f = st.form
    val hc = MaterialTheme.hermesColors
    var menu by remember { mutableStateOf(false) }

    HermesCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("권한 및 작업 환경", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Box {
                val label = ApprovalPolicies.firstOrNull { it.first == f.approvalPolicy }?.second ?: f.approvalPolicy
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { menu = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, hc.cardBorder),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("승인 정책", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(menu, { menu = false }) {
                    ApprovalPolicies.forEach { (value, itemLabel) ->
                        DropdownMenuItem(text = { Text(itemLabel) }, onClick = { vm.edit { it.copy(approvalPolicy = value) }; menu = false })
                    }
                }
            }
            if (f.approvalPolicy == "ask" && !f.readOnly) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = hc.warningContainer,
                ) {
                    Text(
                        "승인 요청은 지시(프롬프트)일 뿐 강제가 아닙니다. 읽기 전용을 함께 켜는 것을 권장합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = hc.onWarningContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            HorizontalDivider(color = hc.subtleDivider)
            SwitchRow("읽기 전용", "파일 수정·명령 실행을 제한합니다.", f.readOnly) { v -> vm.edit { it.copy(readOnly = v) } }
            HorizontalDivider(color = hc.subtleDivider)
            SwitchRow("컴퓨터 사용", "GUI/화면 제어 도구 사용을 허용합니다.", f.computerUse) { v -> vm.edit { it.copy(computerUse = v) } }
            HermesTextField(f.workdir, { v -> vm.edit { it.copy(workdir = v) } }, label = "기본 작업 폴더")
        }
    }
}

@Composable
private fun SwitchRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
