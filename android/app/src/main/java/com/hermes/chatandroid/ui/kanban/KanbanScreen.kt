package com.hermes.chatandroid.ui.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.CronText
import com.hermes.chatandroid.data.KanbanAction
import com.hermes.chatandroid.data.KanbanCreateMode
import com.hermes.chatandroid.data.KanbanStatus
import com.hermes.chatandroid.data.KanbanTask
import com.hermes.chatandroid.data.KanbanTaskDetail
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.components.isWideWindow
import com.hermes.chatandroid.ui.components.readableWidth
import com.hermes.chatandroid.ui.theme.hermesColors

/** 목록(전체) 에서 보이는 상태 순서 — 손이 가는 것부터. */
private val ListOrder = listOf(
    KanbanStatus.RUNNING, KanbanStatus.BLOCKED, KanbanStatus.READY, KanbanStatus.TODO,
    KanbanStatus.TRIAGE, KanbanStatus.SCHEDULED, KanbanStatus.DONE,
)

/** 보드(넓은 화면) 컬럼 순서 — 흐름 순. */
private val BoardOrder = listOf(
    KanbanStatus.TRIAGE, KanbanStatus.TODO, KanbanStatus.SCHEDULED, KanbanStatus.READY,
    KanbanStatus.RUNNING, KanbanStatus.BLOCKED, KanbanStatus.DONE,
)

val KanbanStatus.color: Color get() = Color(argb)

/**
 * 칸반 탭 (T-181). hermes-agent 내장 칸반을 Bridge 로 읽고 CLI 로 쓴다.
 * 커버(좁은 창): 상태 필터 칩 + 카드 목록 / 펼침(600dp+): 상태별 컬럼 보드(가로 스크롤).
 * 카드 탭 → 상세 시트(본문·결과·오류·댓글·이력·상태 전이).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val vm: KanbanViewModel = viewModel(factory = viewModelFactory { initializer { KanbanViewModel(container) } })
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var boardMenu by remember { mutableStateOf(false) }
    var newBoard by remember { mutableStateOf(false) }
    var composer by remember { mutableStateOf(false) }
    val wide = isWideWindow()

    LaunchedEffect(st.message) { st.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Box {
                        TextButton(onClick = { boardMenu = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(st.boardName, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Rounded.ExpandMore, contentDescription = "보드 선택", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
                            st.boards.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text("${b.name}  ·  ${b.activeCount}") },
                                    onClick = { boardMenu = false; vm.selectBoard(b.board) },
                                    trailingIcon = if (b.board == st.selected) ({ Text("✓") }) else null,
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("새 보드 만들기") },
                                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                onClick = { boardMenu = false; newBoard = true },
                            )
                        }
                    }
                },
                actions = {
                    if (st.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { vm.refresh() }, enabled = !st.refreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "새로고침")
                    }
                },
            )
        },
        floatingActionButton = {
            if (st.board != null) {
                ExtendedFloatingActionButton(
                    onClick = { composer = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("작업 추가") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            st.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }) }
            val board = st.board
            when {
                st.loading && board == null -> LoadingBox(label = "칸반을 불러오는 중…")
                board == null -> EmptyState(
                    title = "보드를 불러오지 못했습니다",
                    subtitle = "Bridge 연결과 토큰을 확인한 뒤 새로고침하세요.",
                    icon = Icons.Rounded.ViewKanban,
                    actionLabel = "다시 시도",
                    onAction = { vm.refresh() },
                )
                else -> PullToRefreshBox(isRefreshing = st.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                    if (wide) BoardColumns(board.tasks, onOpen = vm::openTask)
                    else CompactList(st, onFilter = vm::setFilter, onOpen = vm::openTask)
                }
            }
        }
    }

    st.detail?.let { d ->
        TaskDetailSheet(
            detail = d,
            loading = st.detailLoading,
            busy = st.busy,
            onDismiss = vm::closeTask,
            onAction = { a, reason -> vm.perform(d.task, a, reason) },
            onComment = { vm.comment(d.task, it) },
        )
    }
    if (composer) {
        TaskComposerSheet(
            profiles = st.profiles,
            onDismiss = { composer = false },
            onCreate = { t, body, who, mode -> composer = false; vm.createTask(t, body, who, mode) },
        )
    }
    if (newBoard) {
        TextInputDialog(
            title = "새 보드",
            label = "보드 이름",
            confirm = "만들기",
            onDismiss = { newBoard = false },
            onConfirm = { newBoard = false; vm.createBoard(it) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactList(st: KanbanState, onFilter: (KanbanStatus?) -> Unit, onOpen: (KanbanTask) -> Unit) {
    val tasks = st.board?.tasks.orEmpty()
    val activeCount = tasks.count { it.status != KanbanStatus.DONE }
    Column(Modifier.fillMaxSize().readableWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = st.filter == null, onClick = { onFilter(null) }, label = { Text("진행 중 $activeCount") })
            }
            items(ListOrder) { s ->
                val n = st.count(s)
                FilterChip(
                    selected = st.filter == s,
                    onClick = { onFilter(if (st.filter == s) null else s) },
                    label = { Text("${s.label} $n") },
                    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(s.color)) },
                    enabled = n > 0 || st.filter == s,
                )
            }
        }
        val sections = if (st.filter != null) listOf(st.filter to tasks.filter { it.status == st.filter })
        else ListOrder.filter { it != KanbanStatus.DONE }.map { s -> s to tasks.filter { it.status == s } }.filter { it.second.isNotEmpty() }
        if (sections.all { it.second.isEmpty() }) {
            EmptyState(
                title = if (st.filter == null) "진행 중인 작업이 없습니다" else "${st.filter.label} 작업이 없습니다",
                subtitle = "오른쪽 아래 ‘작업 추가’로 봇에게 일을 맡겨 보세요.",
                icon = Icons.Rounded.ViewKanban,
            )
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sections.forEach { (status, list) ->
                if (st.filter == null) {
                    item(key = "h_${status.key}") { SectionHeader(status, list.size) }
                }
                items(list, key = { it.id }) { TaskCard(it, onClick = { onOpen(it) }) }
            }
        }
    }
}

@Composable
private fun SectionHeader(status: KanbanStatus, count: Int) {
    Row(
        Modifier.padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(status.color))
        Text(status.label, style = MaterialTheme.typography.titleSmall)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BoardColumns(tasks: List<KanbanTask>, onOpen: (KanbanTask) -> Unit) {
    Row(
        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoardOrder.forEach { s ->
            val list = tasks.filter { it.status == s }
            Surface(
                Modifier.width(290.dp).fillMaxHeight().padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                        Spacer(Modifier.width(8.dp))
                        Text(s.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${list.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id }) { TaskCard(it, onClick = { onOpen(it) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: KanbanTask, onClick: () -> Unit) {
    val hc = MaterialTheme.hermesColors
    HermesCard(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(task.status.color))
            Column(Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (task.detail.isNotBlank()) {
                    Text(
                        task.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (task.assignee.isNotBlank()) {
                        StatusPill("@${task.assignee}", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                    }
                    if (task.status == KanbanStatus.RUNNING) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    if (task.error != null) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = "오류", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    CronText.relative(task.updatedAt ?: task.createdAt)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = hc.offline)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailSheet(
    detail: KanbanTaskDetail,
    loading: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAction: (KanbanAction, String?) -> Unit,
    onComment: (String) -> Unit,
) {
    val t = detail.task
    var reasonFor by remember { mutableStateOf<KanbanAction?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    var comment by remember(t.id) { mutableStateOf("") }
    var showEvents by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().readableWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(t.status.label, t.status.color.copy(alpha = 0.15f), t.status.color, dotColor = t.status.color)
                if (t.assignee.isNotBlank()) StatusPill("@${t.assignee}", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                if (loading || busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            SelectionContainer { Text(t.title, style = MaterialTheme.typography.titleLarge) }
            Text(
                listOfNotNull(
                    CronText.formatTime(t.createdAt)?.let { "생성 $it" },
                    CronText.relative(t.updatedAt)?.let { "갱신 $it" },
                    t.id,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (t.status.actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.status.actions.forEach { a ->
                        if (a == KanbanAction.ARCHIVE) {
                            OutlinedButton(onClick = { confirmArchive = true }, enabled = !busy) { Text(a.label) }
                        } else {
                            FilledTonalButton(
                                onClick = { if (a.reasonHint != null) reasonFor = a else onAction(a, null) },
                                enabled = !busy,
                            ) { Text(a.label) }
                        }
                    }
                }
            } else if (t.status == KanbanStatus.RUNNING) {
                Text("실행 중인 작업은 디스패처가 관리합니다. 끝나면 자동으로 완료·보류로 바뀝니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (t.detail.isNotBlank()) Block("내용") { SelectionContainer { Text(t.detail, style = MaterialTheme.typography.bodyMedium) } }
            t.result?.let { Block("결과", tint = Color(0xFF4C8C5F)) { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } } }
            t.error?.let {
                Block("오류" + if (t.failures > 0) " (연속 ${t.failures}회)" else "", tint = MaterialTheme.colorScheme.error) {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            Block("댓글 ${detail.comments.size}") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    detail.comments.forEach { c ->
                        Column {
                            Text("${c.author} · ${CronText.relative(c.createdAt).orEmpty()}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(c.body, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HermesTextField(comment, { comment = it }, label = "댓글 (워커에게 전달됨)", modifier = Modifier.weight(1f), singleLine = false, maxLines = 4)
                        IconButton(onClick = { onComment(comment.trim()); comment = "" }, enabled = comment.isNotBlank() && !busy) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "댓글 보내기")
                        }
                    }
                }
            }

            if (detail.events.isNotEmpty()) {
                TextButton(onClick = { showEvents = !showEvents }) {
                    Text("이력 ${detail.events.size}건")
                    Icon(if (showEvents) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (showEvents) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        detail.events.forEach { e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(CronText.relative(e.createdAt).orEmpty(), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                                Text(eventLabel(e.kind), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
                                Text(e.payload, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    reasonFor?.let { a ->
        TextInputDialog(
            title = a.label, label = a.reasonHint ?: "", confirm = a.label, optional = true,
            onDismiss = { reasonFor = null },
            onConfirm = { reasonFor = null; onAction(a, it.ifBlank { null }) },
        )
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("보관할까요?") },
            text = { Text("‘${t.title}’을(를) 보드에서 숨깁니다. 기록은 kanban.db 에 남습니다.") },
            confirmButton = { TextButton(onClick = { confirmArchive = false; onAction(KanbanAction.ARCHIVE, null) }) { Text("보관") } },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("취소") } },
        )
    }
}

private fun eventLabel(kind: String) = when (kind) {
    "created" -> "생성"; "promoted" -> "실행 대기"; "claimed" -> "할당"; "spawned" -> "워커 시작"
    "completed" -> "완료"; "blocked" -> "보류"; "unblocked" -> "재개"; "crashed" -> "비정상 종료"
    "gave_up" -> "포기"; "commented" -> "댓글"; "reclaimed" -> "회수"; "archived" -> "보관"
    "dependency_wait" -> "선행 대기"; "status" -> "상태 변경"; "linked" -> "연결"; "unlinked" -> "연결 해제"
    else -> kind
}

@Composable
private fun Block(title: String, tint: Color? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = (tint ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.06f),
            modifier = Modifier.fillMaxWidth(),
        ) { Box(Modifier.padding(12.dp)) { content() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskComposerSheet(
    profiles: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, KanbanCreateMode) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(KanbanCreateMode.READY) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().readableWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("새 작업", style = MaterialTheme.typography.titleLarge)
            HermesTextField(title, { title = it }, label = "제목", placeholder = "예: 이번 주 안전 점검 보고서 정리")
            HermesTextField(body, { body = it }, label = "내용 (선택)", singleLine = false, minLines = 3, maxLines = 10,
                placeholder = "봇이 알아야 할 맥락·완료 기준")
            Text("담당 프로필", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = assignee.isEmpty(), onClick = { assignee = "" }, label = { Text("자동") })
                profiles.forEach { p -> FilterChip(selected = assignee == p, onClick = { assignee = p }, label = { Text(p) }) }
            }
            Text("시작 방식", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                KanbanCreateMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m, onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, KanbanCreateMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            Text(mode.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onCreate(title.trim(), body.trim(), assignee, mode) }, enabled = title.isNotBlank()) {
                    Text("추가")
                }
            }
        }
    }
}

@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    optional: Boolean = false,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { HermesTextField(text, { text = it }, label = label, singleLine = false, maxLines = 4) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = optional || text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
