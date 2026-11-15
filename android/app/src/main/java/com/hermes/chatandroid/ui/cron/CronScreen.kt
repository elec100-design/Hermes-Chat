package com.hermes.chatandroid.ui.cron

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.CronForm
import com.hermes.chatandroid.data.CronJob
import com.hermes.chatandroid.data.CronText
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.components.readableWidth
import com.hermes.chatandroid.ui.theme.hermesColors

/**
 * 크론 탭 (T-182). 모든 프로필의 cron/jobs.json 을 Bridge 로 모아 보여주고,
 * 켜기/끄기·지금 실행·편집·삭제는 Bridge 가 `hermes cron` CLI 로 반영한다.
 * 좁은 창은 1열, 넓은 창(폴드 펼침)은 카드 그리드.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val vm: CronViewModel = viewModel(factory = viewModelFactory { initializer { CronViewModel(container) } })
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<CronJob?>(null) }

    LaunchedEffect(st.message) { st.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("크론", style = MaterialTheme.typography.headlineSmall)
                        val active = st.jobs.count { !it.paused }
                        if (!st.loading) {
                            StatusPill("활성 $active / ${st.jobs.size}", MaterialTheme.hermesColors.online.copy(alpha = 0.14f), MaterialTheme.hermesColors.online)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !st.refreshing) { Icon(Icons.Rounded.Refresh, contentDescription = "새로고침") }
                },
            )
        },
        floatingActionButton = {
            if (!st.loading && st.profiles.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = vm::startCreate, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("크론 추가") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            st.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
            if (st.profiles.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(selected = st.profileFilter == null, onClick = { vm.setFilter(null) }, label = { Text("전체 ${st.jobs.size}") }) }
                    items(st.profiles) { p ->
                        val n = st.jobs.count { it.profile == p }
                        FilterChip(
                            selected = st.profileFilter == p,
                            onClick = { vm.setFilter(if (st.profileFilter == p) null else p) },
                            label = { Text("$p $n") },
                        )
                    }
                }
            }
            when {
                st.loading -> LoadingBox(label = "크론잡을 불러오는 중…")
                else -> PullToRefreshBox(isRefreshing = st.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                    val list = st.visible
                    if (list.isEmpty()) {
                        // 불러오기 실패와 진짜 0건을 구분 — 오류일 땐 "없습니다" 대신 확인 안내
                        if (st.error != null && st.jobs.isEmpty()) EmptyState(
                            title = "크론을 불러오지 못했습니다",
                            subtitle = "위 안내대로 설정을 확인한 뒤 다시 시도하세요.",
                            icon = Icons.Rounded.Schedule,
                        ) else EmptyState(
                            title = "등록된 크론이 없습니다",
                            subtitle = "‘크론 추가’로 정해진 시간에 봇이 할 일을 예약하세요.",
                            icon = Icons.Rounded.Schedule,
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 340.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(list, key = { it.key() }) { job ->
                                CronCard(
                                    job,
                                    busy = job.key() in st.busy,
                                    showProfile = st.profileFilter == null,
                                    onToggle = { vm.toggle(job, it) },
                                    onOpen = { vm.startEdit(job) },
                                    onRun = { vm.runNow(job) },
                                    onDelete = { confirmDelete = job },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    st.editing?.let { ed ->
        CronEditDialog(
            editing = ed,
            profiles = st.profiles,
            saving = st.saving,
            onChange = vm::updateForm,
            onDismiss = vm::cancelEdit,
            onSave = vm::save,
        )
    }
    confirmDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("크론을 삭제할까요?") },
            text = { Text("‘${job.title}’ (${job.profile})을(를) 삭제합니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(job) }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun CronCard(
    job: CronJob,
    busy: Boolean,
    showProfile: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    val hc = MaterialTheme.hermesColors
    var menu by remember { mutableStateOf(false) }
    val dim = if (job.paused) 0.55f else 1f
    HermesCard(onClick = onOpen, contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Schedule, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = dim))
                    Text(
                        CronText.humanize(job.schedule) ?: job.schedule,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = dim),
                    )
                    if (CronText.humanize(job.schedule) != null) {
                        Text(job.schedule, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = hc.offline)
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(top = 12.dp, end = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Switch(checked = !job.paused, onCheckedChange = onToggle, enabled = !busy)
            Column {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "더보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("지금 실행") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                        onClick = { menu = false; onRun() }, enabled = !busy)
                    DropdownMenuItem(text = { Text("편집") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
        if (job.prompt.isNotBlank() && job.name.isNotBlank()) {
            Text(
                job.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp, top = 4.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                job.paused -> StatusPill("일시정지", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
                job.nextRunAt != null -> StatusPill(
                    "다음 ${CronText.relative(job.nextRunAt) ?: ""}", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary,
                )
            }
            if (job.lastStatus != null) {
                val ok = !job.failed
                StatusPill(
                    (if (ok) "성공" else "실패") + (CronText.relative(job.lastRunAt)?.let { " · $it" } ?: ""),
                    if (ok) hc.online.copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer,
                    if (ok) hc.online else MaterialTheme.colorScheme.error,
                )
            }
            if (showProfile) StatusPill(job.profile, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (job.failed && job.lastError != null) {
            Text(
                job.lastError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 12.dp),
            )
        }
    }
}

/** 자주 쓰는 일정. 시각이 들어가는 것은 현재 식의 시:분을 유지한다. */
private data class Preset(val label: String, val build: (h: Int, m: Int) -> String)

private val Presets = listOf(
    Preset("매일") { h, m -> "$m $h * * *" },
    Preset("평일") { h, m -> "$m $h * * 1-5" },
    Preset("매주 월") { h, m -> "$m $h * * 1" },
    Preset("매월 1일") { h, m -> "$m $h 1 * *" },
    Preset("매시간") { _, m -> "$m * * * *" },
    Preset("30분마다") { _, _ -> "*/30 * * * *" },
)

private val DeliverTargets = listOf(
    "origin" to "만든 곳(기본)", "telegram" to "텔레그램", "discord" to "디스코드", "slack" to "슬랙",
    "bot-chat" to "봇 채팅", "local" to "보내지 않음",
)

/** 현재 cron 식에서 시·분을 뽑는다(없으면 09:00). */
private fun hourMinuteOf(expr: String): Pair<Int, Int> {
    val p = expr.trim().split(Regex("\\s+"))
    val m = p.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
    val h = p.getOrNull(1)?.split(',')?.firstOrNull()?.toIntOrNull()?.takeIf { it in 0..23 } ?: 9
    return h to m
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CronEditDialog(
    editing: CronEditing,
    profiles: List<String>,
    saving: Boolean,
    onChange: (CronForm) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val f = editing.form
    val isNew = editing.original == null
    var pickTime by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        title = { Text(if (isNew) "새 크론" else "크론 편집") },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "닫기") } },
                        actions = {
                            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            TextButton(onClick = onSave, enabled = !saving && f.name.isNotBlank() && f.schedule.isNotBlank()) {
                                Text(if (isNew) "만들기" else "저장")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().readableWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp).imePadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (isNew && profiles.size > 1) {
                        Section("프로필") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                profiles.forEach { p -> FilterChip(selected = f.profile == p, onClick = { onChange(f.copy(profile = p)) }, label = { Text(p) }) }
                            }
                        }
                    } else {
                        Text("프로필: ${f.profile}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HermesTextField(f.name, { onChange(f.copy(name = it)) }, label = "이름", placeholder = "예: 아침 브리핑")

                    Section("일정") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val (h, m) = hourMinuteOf(f.schedule)
                            Presets.forEach { p ->
                                val expr = p.build(h, m)
                                FilterChip(selected = f.schedule.trim() == expr, onClick = { onChange(f.copy(schedule = expr)) }, label = { Text(p.label) })
                            }
                            AssistChip(
                                onClick = { pickTime = true },
                                label = { Text("시각 %02d:%02d".format(h, m)) },
                                leadingIcon = { Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp)) },
                            )
                        }
                        HermesTextField(
                            f.schedule, { onChange(f.copy(schedule = it)) }, label = "cron 식 또는 간격",
                            placeholder = "0 9 * * *  ·  30m  ·  every 2h",
                            supportingText = {
                                Text(CronText.humanize(f.schedule)?.let { "→ $it" } ?: "분 시 일 월 요일 (요일 0=일요일)")
                            },
                        )
                    }

                    HermesTextField(
                        f.prompt, { onChange(f.copy(prompt = it)) }, label = "할 일 (프롬프트)", singleLine = false, minLines = 4, maxLines = 14,
                        placeholder = "봇이 매번 실행할 지시. 결과를 어디로 보낼지는 아래에서 고릅니다.",
                    )

                    Section("결과 보내기") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DeliverTargets.forEach { (key, label) ->
                                FilterChip(selected = f.deliver == key, onClick = { onChange(f.copy(deliver = key)) }, label = { Text(label) })
                            }
                        }
                        if (DeliverTargets.none { it.first == f.deliver }) {
                            HermesTextField(f.deliver, { onChange(f.copy(deliver = it)) }, label = "전달 대상", placeholder = "platform:chat_id")
                        }
                    }

                    HermesTextField(
                        f.skills, { onChange(f.copy(skills = it)) }, label = "스킬 (쉼표로 구분, 선택)",
                        placeholder = "llm-wiki, arxiv",
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("사용", style = MaterialTheme.typography.titleSmall)
                            Text("끄면 일정이 멈추고 설정은 유지됩니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = f.enabled, onCheckedChange = { onChange(f.copy(enabled = it)) })
                    }

                    editing.original?.let { j ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CronText.formatTime(j.nextRunAt)?.let { Info("다음 실행", it) }
                                CronText.formatTime(j.lastRunAt)?.let { Info("마지막 실행", it + (j.lastStatus?.let { s -> " ($s)" } ?: "")) }
                                j.lastError?.let { Info("마지막 오류", it, MaterialTheme.colorScheme.error) }
                                j.model?.let { Info("모델", it) }
                                Info("ID", j.id)
                            }
                        }
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }

    if (pickTime) {
        val (h, m) = hourMinuteOf(f.schedule)
        val tp = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("실행 시각") },
            text = { TimePicker(tp) },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    val parts = f.schedule.trim().split(Regex("\\s+")).toMutableList()
                    val expr = if (parts.size == 5 && !parts[1].contains('/') && !parts[0].contains('/')) {
                        parts[0] = tp.minute.toString(); if (parts[1] != "*") parts[1] = tp.hour.toString(); parts.joinToString(" ")
                    } else "${tp.minute} ${tp.hour} * * *"
                    onChange(f.copy(schedule = expr))
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Info(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
