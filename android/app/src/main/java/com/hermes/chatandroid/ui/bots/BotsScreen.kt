package com.hermes.chatandroid.ui.bots

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotChannel
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.SearchPillField
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BotsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val bots: List<Bot> = emptyList(),
    val channels: List<BotChannel> = emptyList(),
    val error: String? = null,
)

/**
 * 홈 목록 순서: 대화가 있는 봇(미리보기 있음) 먼저, 그 안에서 최근에 연 순서(이 기기 기준 — 서버는 DM 시각을 주지 않는다),
 * 그다음 온라인 → 이름. 보관된 봇/DM 없는 봇은 제외.
 */
internal fun sortBotsForHome(bots: List<Bot>, activity: Map<Int, Long>): List<Bot> =
    bots.filter { !it.isArchived && it.dmChannelId != null }
        .sortedWith(
            compareByDescending<Bot> { !it.preview.isNullOrEmpty() }
                .thenByDescending { activity[it.id] ?: 0L }
                .thenByDescending { it.isOnline }
                .thenBy { it.displayName },
        )

class BotsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(BotsUiState())
    val state = _state.asStateFlow()

    fun refresh(initial: Boolean = false) {
        val api = container.botApi() ?: run {
            _state.update { it.copy(loading = false, refreshing = false) }
            return
        }
        _state.update { it.copy(refreshing = !initial) }
        viewModelScope.launch {
            try {
                if (!initial) runCatching { api.refreshHealth() } // 당겨서 새로고침: 헬스 점도 갱신
                val bots = api.fetchBots()
                val channels = api.fetchChannels()
                _state.update { BotsUiState(loading = false, bots = bots, channels = channels) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = botErrorText(e)) }
            }
        }
    }

    fun createChannel(title: String, topic: String, botIds: List<Int>, onCreated: (Int) -> Unit) {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                val ch = api.createChannel(title.trim(), topic.trim().ifEmpty { null }, botIds)
                onCreated(ch.id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = botErrorText(e)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    container: AppContainer,
    selectedChannelId: Int?,
    onSelectChannel: (channelId: Int?) -> Unit,
    onOpenBotProfile: (botId: Int) -> Unit,
    onOpenChannelSettings: (channelId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: BotsViewModel = viewModel(factory = viewModelFactory { initializer { BotsViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    // 탭으로 돌아올 때마다 조용히 갱신(첫 진입 포함)
    LaunchedEffect(Unit) { vm.refresh(initial = true) }

    var showCreate by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sidebarExpanded by rememberSaveable { mutableStateOf(true) }
    val drawerState = rememberDrawerState(
        initialValue = if (selectedChannelId == null) DrawerValue.Open else DrawerValue.Closed,
    )
    val scope = rememberCoroutineScope()

    val configured = container.botApi() != null
    val hc = MaterialTheme.hermesColors
    val homeBots = remember(state.bots, container.botActivity) { sortBotsForHome(state.bots, container.botActivity) }
    val onlineCount = remember(homeBots) { homeBots.count { it.isOnline } }

    val q = query.trim()
    val visibleBots = remember(homeBots, q) {
        if (q.isEmpty()) homeBots
        else homeBots.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                it.slug.contains(q, ignoreCase = true) ||
                it.previewLine.contains(q, ignoreCase = true)
        }
    }
    val visibleChannels = remember(state.channels, q) {
        if (q.isEmpty()) state.channels
        else state.channels.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.topic.orEmpty().contains(q, ignoreCase = true) ||
                it.preview.orEmpty().contains(q, ignoreCase = true)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        val sidebarWidth = (maxWidth * 0.34f).coerceIn(250.dp, 310.dp)

        LaunchedEffect(state.loading, state.error, homeBots, state.channels, isWide, selectedChannelId) {
            if (state.loading || state.error != null) return@LaunchedEffect
            val validIds = homeBots.mapNotNull { it.dmChannelId }.toSet() + state.channels.map { it.id }.toSet()
            if (selectedChannelId != null && selectedChannelId !in validIds) {
                onSelectChannel(null)
            } else if (isWide && selectedChannelId == null) {
                val firstId = homeBots.firstOrNull()?.dmChannelId ?: state.channels.firstOrNull()?.id
                if (firstId != null) onSelectChannel(firstId)
            }
        }

        val selectChannelAndClose: (Int) -> Unit = { chId ->
            onSelectChannel(chId)
            if (!isWide) scope.launch { drawerState.close() }
        }

        val sidebarContent: @Composable (Boolean, (() -> Unit)?) -> Unit = { applyStatusBarPadding, onClose ->
            BotSidebarPanel(
                configured = configured,
                demoMode = settings.demoMode,
                onlineCount = onlineCount,
                state = state,
                visibleBots = visibleBots,
                visibleChannels = visibleChannels,
                totalBotCount = homeBots.size,
                totalChannelCount = state.channels.size,
                query = query,
                onQueryChange = { query = it },
                selectedChannelId = selectedChannelId,
                applyStatusBarPadding = applyStatusBarPadding,
                onRefresh = { vm.refresh() },
                onSelectBot = { b ->
                    container.touchBotActivity(b.id)
                    b.dmChannelId?.let(selectChannelAndClose)
                },
                onOpenBotProfile = { botId ->
                    if (!isWide) scope.launch { drawerState.close() }
                    onOpenBotProfile(botId)
                },
                onSelectChannel = { ch -> selectChannelAndClose(ch.id) },
                onCreateChannel = { showCreate = true },
                onClose = onClose,
            )
        }

        val detailPane: @Composable (onOpenMenu: () -> Unit) -> Unit = { onOpenMenu ->
            ChatDetailPane(
                container = container,
                configured = configured,
                selectedChannelId = selectedChannelId,
                showOpenSidebarAction = !isWide || !sidebarExpanded,
                onMenuClick = onOpenMenu,
                onOpenBotProfile = onOpenBotProfile,
                onOpenChannelSettings = onOpenChannelSettings,
            )
        }

        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = sidebarExpanded,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    Row(Modifier.fillMaxHeight()) {
                        Surface(
                            modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                        ) {
                            sidebarContent(true, null)
                        }
                        VerticalDivider(thickness = 0.7.dp, color = hc.cardBorder)
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    detailPane { sidebarExpanded = !sidebarExpanded }
                }
            }
        } else {
            BackHandler(enabled = drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.84f),
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        drawerTonalElevation = 0.dp,
                        windowInsets = WindowInsets.statusBars,
                    ) {
                        sidebarContent(false) { scope.launch { drawerState.close() } }
                    }
                },
            ) {
                detailPane { scope.launch { drawerState.open() } }
            }
        }
    }

    if (showCreate) {
        CreateChannelSheet(
            bots = state.bots.filter { !it.isArchived },
            onDismiss = { showCreate = false },
            onCreate = { title, topic, ids ->
                showCreate = false
                vm.createChannel(title, topic, ids) { chId ->
                    onSelectChannel(chId)
                    if (drawerState.isOpen) scope.launch { drawerState.close() }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotSidebarPanel(
    configured: Boolean,
    demoMode: Boolean,
    onlineCount: Int,
    state: BotsUiState,
    visibleBots: List<Bot>,
    visibleChannels: List<BotChannel>,
    totalBotCount: Int,
    totalChannelCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedChannelId: Int?,
    applyStatusBarPadding: Boolean,
    onRefresh: () -> Unit,
    onSelectBot: (Bot) -> Unit,
    onOpenBotProfile: (Int) -> Unit,
    onSelectChannel: (BotChannel) -> Unit,
    onCreateChannel: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val hc = MaterialTheme.hermesColors
    Column(Modifier.fillMaxSize()) {
        // 상단 헤더 — 오른쪽 BotChatScreen 의 TopAppBar 와 높이를 64dp 로 맞춘다
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "대화",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (demoMode) {
                    StatusPill(
                        text = "데모",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                } else if (configured && !state.loading && onlineCount > 0) {
                    StatusPill(
                        text = "온라인 $onlineCount",
                        containerColor = hc.online.copy(alpha = 0.14f),
                        contentColor = hc.online,
                        dotColor = hc.online,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (configured) {
                    IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "새로고침")
                    }
                }
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "사이드바 닫기")
                    }
                }
            }
            HorizontalDivider(thickness = 0.7.dp, color = hc.cardBorder)
        }

        if (!configured) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "봇 서버 미설정",
                    subtitle = "더보기 → 설정에서 서버 주소와 토큰을 입력하면 봇과 채널이 여기에 표시됩니다.",
                    icon = Icons.Rounded.SmartToy,
                )
            }
            return
        }

        if (!state.loading && (totalBotCount > 0 || totalChannelCount > 0)) {
            SearchPillField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "봇 또는 채널 검색",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        state.error?.let { ErrorBanner(it, onRetry = onRefresh) }

        if (state.loading) {
            LoadingBox(Modifier.weight(1f), label = "봇 목록을 불러오는 중…")
        } else {
            val q = query.trim()
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "sec_bots") {
                        SidebarSectionHeader(
                            title = "봇",
                            count = if (q.isEmpty()) totalBotCount else visibleBots.size,
                        )
                    }
                    if (visibleBots.isEmpty()) {
                        item(key = "empty_bots") {
                            SidebarInlineEmpty(
                                text = if (q.isNotEmpty()) "일치하는 봇이 없습니다." else "대화할 봇이 없습니다.",
                            )
                        }
                    } else {
                        items(visibleBots, key = { "b${it.id}" }) { b ->
                            SidebarBotRow(
                                bot = b,
                                selected = b.dmChannelId != null && b.dmChannelId == selectedChannelId,
                                onAvatar = { onOpenBotProfile(b.id) },
                                onClick = { onSelectBot(b) },
                            )
                        }
                    }

                    item(key = "sec_channels") {
                        SidebarSectionHeader(
                            title = "채널",
                            count = if (q.isEmpty()) totalChannelCount else visibleChannels.size,
                            actionLabel = "+ 새 채널",
                            onAction = onCreateChannel,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (visibleChannels.isEmpty()) {
                        item(key = "empty_channels") {
                            SidebarInlineEmpty(
                                text = if (q.isNotEmpty()) "일치하는 채널이 없습니다." else "채널이 없습니다.",
                            )
                        }
                    } else {
                        items(visibleChannels, key = { "c${it.id}" }) { c ->
                            SidebarChannelRow(
                                channel = c,
                                selected = c.id == selectedChannelId,
                                onClick = { onSelectChannel(c) },
                            )
                        }
                    }

                    item(key = "sidebar_bottom_spacer") {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 7.dp, vertical = 1.dp),
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Surface(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAction),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SidebarInlineEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun SidebarBotRow(
    bot: Bot,
    selected: Boolean,
    onAvatar: () -> Unit,
    onClick: () -> Unit,
) {
    val hc = MaterialTheme.hermesColors
    val accent = toneColor(bot.tone) ?: MaterialTheme.colorScheme.primary
    val bgColor = if (selected) {
        accent.copy(alpha = if (hc.isDark) 0.18f else 0.10f)
    } else {
        Color.Transparent
    }
    val border = if (selected) {
        BorderStroke(1.dp, accent.copy(alpha = if (hc.isDark) 0.42f else 0.30f))
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (bot.dmChannelId != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = border,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selected) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(26.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            BotAvatar(
                emoji = bot.avatarText,
                tone = bot.tone,
                size = 38.dp,
                online = bot.isOnline,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAvatar),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        bot.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        "@${bot.slug}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bot.readOnly == true) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = "읽기 전용",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (bot.computerUse == true) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = "컴퓨터 사용",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    bot.previewLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SidebarChannelRow(
    channel: BotChannel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val hc = MaterialTheme.hermesColors
    val accent = MaterialTheme.colorScheme.primary
    val bgColor = if (selected) {
        accent.copy(alpha = if (hc.isDark) 0.18f else 0.10f)
    } else {
        Color.Transparent
    }
    val border = if (selected) {
        BorderStroke(1.dp, accent.copy(alpha = if (hc.isDark) 0.42f else 0.30f))
    } else {
        null
    }
    val sub = channel.preview?.takeIf { it.isNotBlank() }
        ?: channel.topic?.takeIf { it.isNotBlank() }
        ?: channel.members.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.displayName }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = border,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selected) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(26.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            ChannelAvatarGroup(channel.members, size = 38.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        channel.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.botChat == true) {
                        StatusPill(
                            text = "연쇄",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (sub != null) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailPane(
    container: AppContainer,
    configured: Boolean,
    selectedChannelId: Int?,
    showOpenSidebarAction: Boolean,
    onMenuClick: () -> Unit,
    onOpenBotProfile: (Int) -> Unit,
    onOpenChannelSettings: (Int) -> Unit,
) {
    val hc = MaterialTheme.hermesColors
    when {
        !configured -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Column {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                            title = { Text("대화", style = MaterialTheme.typography.titleMedium) },
                            navigationIcon = {
                                IconButton(onClick = onMenuClick) {
                                    Icon(Icons.Rounded.Menu, contentDescription = "사이드바 열기")
                                }
                            },
                        )
                        HorizontalDivider(thickness = 0.7.dp, color = hc.cardBorder)
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    EmptyState(
                        title = "봇 서버가 설정되지 않았습니다",
                        subtitle = "더보기 → 설정에서 Hermes Bot 서버 주소와 토큰을 입력하면 봇들과 바로 대화할 수 있습니다.",
                        icon = Icons.Rounded.SmartToy,
                    )
                }
            }
        }
        selectedChannelId != null -> {
            key(selectedChannelId) {
                BotChatScreen(
                    container = container,
                    channelId = selectedChannelId,
                    onBack = null,
                    onMenuClick = onMenuClick,
                    modifier = Modifier.fillMaxSize(),
                    onOpenBotProfile = onOpenBotProfile,
                    onOpenChannelSettings = onOpenChannelSettings,
                )
            }
        }
        else -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Column {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                            title = { Text("Hermes Bot", style = MaterialTheme.typography.titleMedium) },
                            navigationIcon = {
                                IconButton(onClick = onMenuClick) {
                                    Icon(Icons.Rounded.Menu, contentDescription = "봇 · 채널 목록")
                                }
                            },
                        )
                        HorizontalDivider(thickness = 0.7.dp, color = hc.cardBorder)
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    EmptyState(
                        title = "대화를 선택하세요",
                        subtitle = "왼쪽 사이드바에서 봇이나 채널을 선택하면 여기에 채팅 창이 열립니다.",
                        icon = Icons.Rounded.Forum,
                        actionLabel = if (showOpenSidebarAction) "봇 · 채널 목록 열기" else null,
                        onAction = if (showOpenSidebarAction) onMenuClick else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateChannelSheet(
    bots: List<Bot>,
    onDismiss: () -> Unit,
    onCreate: (title: String, topic: String, botIds: List<Int>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("새 채널 만들기", style = MaterialTheme.typography.titleLarge)
            Text(
                "여러 봇을 한 대화방에 모아 @멘션으로 협업할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HermesTextField(title, { title = it }, label = "채널 제목", placeholder = "예: 주간 스프린트")
            HermesTextField(topic, { topic = it }, label = "주제 (선택)", singleLine = false, maxLines = 3)
            Text("참여 봇 (${selected.size}명 선택)", style = MaterialTheme.typography.labelLarge)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(bots, key = { it.id }) { b ->
                    val checked = b.id in selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selected = if (checked) selected - b.id else selected + b.id },
                        shape = RoundedCornerShape(12.dp),
                        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.hermesColors.cardBorder,
                        ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BotAvatar(b.avatarText, b.tone, size = 34.dp, online = b.isOnline)
                            Column(Modifier.weight(1f)) {
                                Text(b.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("@${b.slug}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onCreate(title, topic, selected.toList()) },
                enabled = title.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("채널 만들기")
            }
        }
    }
}
