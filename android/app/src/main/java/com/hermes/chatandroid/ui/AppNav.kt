package com.hermes.chatandroid.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hermes.chatandroid.notify.Notifications
import com.hermes.chatandroid.notify.OpenRequest
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.VerticalDivider
import com.hermes.chatandroid.ui.components.isWideWindow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.R
import com.hermes.chatandroid.ui.bots.ApprovalsScreen
import com.hermes.chatandroid.ui.bots.BotChatScreen
import com.hermes.chatandroid.ui.bots.BotsScreen
import com.hermes.chatandroid.ui.bots.channel.ChannelSettingsScreen
import com.hermes.chatandroid.ui.bots.profile.BotProfileScreen
import com.hermes.chatandroid.ui.chat.ChatScreen
import com.hermes.chatandroid.ui.cron.CronScreen
import com.hermes.chatandroid.ui.kanban.KanbanScreen
import com.hermes.chatandroid.ui.more.DashboardScreen
import com.hermes.chatandroid.ui.more.MoreScreen
import com.hermes.chatandroid.ui.onboarding.OnboardingScreen
import com.hermes.chatandroid.ui.settings.GlassesSettingsScreen
import com.hermes.chatandroid.ui.settings.SettingsScreen
import com.hermes.chatandroid.ui.theme.hermesColors
import com.hermes.chatandroid.ui.usage.UsageDialog
import com.hermes.chatandroid.ui.workspaces.WorkspacesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object Routes {
    /** 홈(대화) — 봇 DM 목록 + 채널. */
    const val BOTS = "bots"
    const val APPROVALS = "approvals"
    const val KANBAN = "kanban"
    const val CRON = "cron"
    const val MORE = "more"
    const val SETTINGS = "settings"
    const val WORKSPACES = "workspaces"
    const val DASHBOARD = "dashboard"
    const val GLASSES = "glasses"
    /** 게이트웨이 세션 채팅. [port] 가 -1 이면 설정의 선택 프로필, 아니면 그 봇 게이트웨이 포트. */
    const val CHAT = "chat/{sessionId}?port={port}"
    const val BOT_CHAT = "botChat/{channelId}"
    const val BOT_PROFILE = "botProfile/{botId}"
    const val CHANNEL_SETTINGS = "channelSettings/{channelId}"

    fun chat(sessionId: String, port: Int? = null) =
        "chat/${Uri.encode(sessionId)}" + (port?.let { "?port=$it" } ?: "")
    fun botChat(channelId: Int) = "botChat/$channelId"
    fun botProfile(botId: Int) = "botProfile/$botId"
    fun channelSettings(channelId: Int) = "channelSettings/$channelId"
}

private data class Tab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val ChatsTab = Tab(Routes.BOTS, R.string.tab_chats, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat)
private val KanbanTab = Tab(Routes.KANBAN, R.string.tab_kanban, Icons.Filled.ViewKanban, Icons.Outlined.ViewKanban)
private val CronTab = Tab(Routes.CRON, R.string.tab_cron, Icons.Filled.Schedule, Icons.Outlined.Schedule)
private val WorkspacesTab = Tab(Routes.WORKSPACES, R.string.tab_workspaces, Icons.Filled.Code, Icons.Outlined.Code)
private val MoreTab = Tab(Routes.MORE, R.string.tab_more, Icons.Filled.GridView, Icons.Outlined.GridView)
private val DashboardTab = Tab(Routes.DASHBOARD, R.string.tab_dashboard, Icons.Filled.Speed, Icons.Outlined.Speed)
// 승인은 탭이 아니라 대화 탭 헤더의 버튼(대기 건수 배지)으로 연다.
private val Tabs = listOf(ChatsTab, WorkspacesTab, KanbanTab, CronTab, DashboardTab, MoreTab)
private val TopRoutes = Tabs.map { it.route }.toSet()

/** 탭 바가 보이는 화면(승인은 대화의, 설정·글라스는 더보기의 하위라 그 탭이 선택된 채 표시). */
private fun tabOf(route: String?): Tab? = when (route) {
    Routes.BOTS, Routes.APPROVALS -> ChatsTab
    Routes.WORKSPACES -> WorkspacesTab
    Routes.KANBAN -> KanbanTab
    Routes.CRON -> CronTab
    Routes.DASHBOARD -> DashboardTab
    Routes.MORE, Routes.SETTINGS, Routes.GLASSES -> MoreTab
    else -> null
}

@Composable
fun AppNav(
    container: AppContainer,
    openRequest: OpenRequest? = null,
    onOpenHandled: () -> Unit = {},
) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    var pendingInvite by remember { mutableStateOf<Pair<String?, String>?>(null) }

    LaunchedEffect(openRequest) {
        if (openRequest is OpenRequest.Invite) {
            pendingInvite = openRequest.url to openRequest.code
            onOpenHandled()
        }
    }

    if (!settings.onboardingComplete || pendingInvite != null) {
        OnboardingScreen(
            container = container,
            onFinished = {
                pendingInvite = null
                repo.update { it.copy(onboardingComplete = true) }
            },
            onDemo = {
                pendingInvite = null
                repo.update { it.copy(demoMode = true, onboardingComplete = true) }
            },
            initialInviteUrl = pendingInvite?.first,
            initialInviteCode = pendingInvite?.second,
        )
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentTab = tabOf(currentRoute)
    var selectedBotChannelId by rememberSaveable { mutableStateOf<Int?>(null) }
    /** 워크스페이스 탭에서 열어 둔 작업 채팅. */
    var selectedWorkChannelId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showUsage by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val isGuest = settings.isGuestMode
    val visibleTabs = remember(isGuest) { if (isGuest) listOf(ChatsTab, MoreTab) else Tabs }
    val notifyOn = settings.notificationsEnabled && !settings.demoMode && !isGuest

    // 승인 탭 배지 + 알림(T-184): 화면이 보이는 동안 30초마다 확인. 게스트 모드에서는 비활성.
    var pendingCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(settings.botHost, settings.botToken, notifyOn, isGuest) {
        if (isGuest) { pendingCount = 0; return@LaunchedEffect }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val api = container.botApi()
                pendingCount = try {
                    if (api != null && notifyOn) {
                        val (events, n) = container.notifier.check(api)
                        Notifications.post(context, events)
                        n
                    } else api?.fetchApprovals()?.size ?: 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pendingCount
                }
                delay(30_000)
            }
        }
    }
    LaunchedEffect(settings.botHost, notifyOn) {
        Notifications.schedule(context, notifyOn && settings.botBaseUrl != null)
    }
    // 보고 있는 봇 채팅은 알리지 않는다
    LaunchedEffect(currentRoute, selectedBotChannelId, selectedWorkChannelId, lifecycleState) {
        container.notifier.activeChannelId = if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) null else when (currentRoute) {
            Routes.BOTS -> selectedBotChannelId
            Routes.WORKSPACES -> selectedWorkChannelId
            else -> null
        }
    }
    // 알림 권한(Android 13+): 처음 한 번만 묻는다. 거절하면 설정 > 알림에서 다시 켤 수 있다.
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(notifyOn) {
        val prefs = context.getSharedPreferences("notify", android.content.Context.MODE_PRIVATE)
        if (notifyOn && Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(context) && !prefs.getBoolean("asked_permission", false)) {
            prefs.edit().putBoolean("asked_permission", true).apply()
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val wide = isWideWindow()
    // 좁은 창(커버 화면)에서 봇 채팅을 연 상태면 탭 바를 숨겨 채팅·키보드 공간을 확보한다.
    val inCompactChat = !wide && container.botApi() != null && (
        (currentRoute == Routes.BOTS && selectedBotChannelId != null) ||
            (currentRoute == Routes.WORKSPACES && selectedWorkChannelId != null)
        )
    val navigateTab: (Tab) -> Unit = { tab ->
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // 알림을 눌러 들어온 경우 해당 채팅/승인 탭으로
    LaunchedEffect(openRequest) {
        when (openRequest) {
            is OpenRequest.Channel -> { selectedBotChannelId = openRequest.channelId; navigateTab(ChatsTab) }
            OpenRequest.Approvals -> if (!isGuest) navController.navigate(Routes.APPROVALS) { launchSingleTop = true }
            is OpenRequest.Tab ->
                if (openRequest.route == Routes.APPROVALS && !isGuest) navController.navigate(Routes.APPROVALS) { launchSingleTop = true }
                else visibleTabs.firstOrNull { it.route == openRequest.route }?.let(navigateTab)
            // Live 진행 알림 → Live 가 켜져 있는 그 채팅으로
            OpenRequest.Live -> container.live.ui.value.target?.let { selectedBotChannelId = it.channelId; navigateTab(ChatsTab) }
            is OpenRequest.Invite -> Unit
            null -> return@LaunchedEffect
        }
        onOpenHandled()
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (wide) {
            // 넓은 창(폴드 내부 화면): 세로 공간을 아끼도록 왼쪽 내비게이션 레일. 상세 화면에서도 유지.
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                header = { Spacer(Modifier.height(8.dp)) },
            ) {
                visibleTabs.forEach { tab ->
                    val selected = currentTab == tab
                    NavigationRailItem(
                        selected = selected,
                        onClick = { navigateTab(tab) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        icon = { TabIcon(tab, selected, pendingCount) },
                        label = { TabLabel(tab, selected) },
                    )
                }
                if (!isGuest) {
                    // 레일 맨 아래: 에이전트별 사용량 보드
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showUsage = true }) {
                        Icon(
                            Icons.Outlined.DataUsage,
                            contentDescription = stringResource(R.string.usage_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            VerticalDivider(thickness = 0.7.dp, color = MaterialTheme.hermesColors.cardBorder)
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (currentTab != null && !wide && !inCompactChat) {
                    Column {
                        HorizontalDivider(thickness = 0.7.dp, color = MaterialTheme.hermesColors.cardBorder)
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 0.dp,
                        ) {
                            visibleTabs.forEach { tab ->
                                val selected = currentTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTab(tab) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    icon = { TabIcon(tab, selected, pendingCount) },
                                    label = { TabLabel(tab, selected) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            val mod = Modifier.padding(padding).consumeWindowInsets(padding)
            NavHost(
                navController,
                startDestination = Routes.BOTS,
                enterTransition = {
                    if (targetState.destination.route in TopRoutes && initialState.destination.route in TopRoutes) {
                        fadeIn(tween(180))
                    } else {
                        slideInHorizontally(tween(240)) { it / 5 } + fadeIn(tween(220))
                    }
                },
                exitTransition = {
                    if (targetState.destination.route in TopRoutes && initialState.destination.route in TopRoutes) {
                        fadeOut(tween(140))
                    } else {
                        fadeOut(tween(180))
                    }
                },
                popEnterTransition = {
                    if (targetState.destination.route in TopRoutes && initialState.destination.route in TopRoutes) {
                        fadeIn(tween(180))
                    } else {
                        fadeIn(tween(200))
                    }
                },
                popExitTransition = {
                    if (targetState.destination.route in TopRoutes && initialState.destination.route in TopRoutes) {
                        fadeOut(tween(140))
                    } else {
                        slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(180))
                    }
                },
            ) {
                composable(Routes.BOTS) {
                    BotsScreen(
                        container,
                        selectedChannelId = selectedBotChannelId,
                        onSelectChannel = { selectedBotChannelId = it },
                        onOpenBotProfile = { navController.navigate(Routes.botProfile(it)) },
                        onOpenChannelSettings = { navController.navigate(Routes.channelSettings(it)) },
                        modifier = mod,
                        onOpenGlasses = { navController.navigate(Routes.GLASSES) },
                        onOpenWorkspaces = { navigateTab(WorkspacesTab) },
                        pendingApprovals = pendingCount,
                        onOpenApprovals = { navController.navigate(Routes.APPROVALS) { launchSingleTop = true } },
                    )
                }
                composable(Routes.APPROVALS) {
                    ApprovalsScreen(container, modifier = mod, onBack = { navController.popBackStack() })
                }
                composable(Routes.KANBAN) {
                    KanbanScreen(container, modifier = mod)
                }
                composable(Routes.CRON) {
                    CronScreen(container, modifier = mod)
                }
                composable(Routes.MORE) {
                    MoreScreen(
                        container = container,
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenUsage = { showUsage = true },
                        onOpenGlasses = { navController.navigate(Routes.GLASSES) },
                        modifier = mod,
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        container,
                        onRerunOnboarding = { repo.update { it.copy(onboardingComplete = false) } },
                        onExitDemo = { repo.update { it.copy(demoMode = false, onboardingComplete = false) } },
                        modifier = mod,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.DASHBOARD) {
                    DashboardScreen(container, modifier = mod)
                }
                composable(Routes.GLASSES) {
                    GlassesSettingsScreen(container, onBack = { navController.popBackStack() }, modifier = mod)
                }
                composable(Routes.WORKSPACES) {
                    WorkspacesScreen(
                        container,
                        selectedChannelId = selectedWorkChannelId,
                        onSelectChannel = { selectedWorkChannelId = it },
                        modifier = mod,
                        onOpenBotProfile = { navController.navigate(Routes.botProfile(it)) },
                        onOpenChannelSettings = { navController.navigate(Routes.channelSettings(it)) },
                        onOpenGlasses = { navController.navigate(Routes.GLASSES) },
                    )
                }
                composable(
                    Routes.CHAT,
                    arguments = listOf(
                        navArgument("sessionId") { type = NavType.StringType },
                        navArgument("port") { type = NavType.IntType; defaultValue = -1 },
                    ),
                ) { entry ->
                    ChatScreen(
                        container,
                        sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        modifier = mod,
                        port = entry.arguments?.getInt("port")?.takeIf { it >= 0 },
                    )
                }
                composable(
                    Routes.BOT_CHAT,
                    arguments = listOf(navArgument("channelId") { type = NavType.IntType }),
                ) { entry ->
                    BotChatScreen(
                        container,
                        channelId = entry.arguments?.getInt("channelId") ?: 0,
                        onBack = { navController.popBackStack() },
                        modifier = mod,
                        onOpenBotProfile = { navController.navigate(Routes.botProfile(it)) },
                        onOpenChannelSettings = { navController.navigate(Routes.channelSettings(it)) },
                        onOpenGlasses = { navController.navigate(Routes.GLASSES) },
                        onOpenWorkspaces = { navigateTab(WorkspacesTab) },
                    )
                }
                composable(
                    Routes.BOT_PROFILE,
                    arguments = listOf(navArgument("botId") { type = NavType.IntType }),
                ) { entry ->
                    BotProfileScreen(
                        container,
                        botId = entry.arguments?.getInt("botId") ?: 0,
                        onBack = { navController.popBackStack() },
                        onOpenSession = { port, sessionId -> navController.navigate(Routes.chat(sessionId, port)) },
                        onOpenChannel = { chId ->
                            selectedBotChannelId = chId
                            navController.popBackStack(Routes.BOTS, inclusive = false)
                        },
                        modifier = mod,
                    )
                }
                composable(
                    Routes.CHANNEL_SETTINGS,
                    arguments = listOf(navArgument("channelId") { type = NavType.IntType }),
                ) { entry ->
                    val chId = entry.arguments?.getInt("channelId") ?: 0
                    ChannelSettingsScreen(
                        container,
                        channelId = chId,
                        onBack = { navController.popBackStack() },
                        onDeleted = {
                            if (selectedBotChannelId == chId) selectedBotChannelId = null
                            if (selectedWorkChannelId == chId) selectedWorkChannelId = null
                            // 워크스페이스 탭에서 연 채널 설정이면 그 탭으로, 아니면 대화 홈으로
                            if (navController.previousBackStackEntry?.destination?.route == Routes.WORKSPACES) navController.popBackStack()
                            else navController.popBackStack(Routes.BOTS, inclusive = false)
                        },
                        modifier = mod,
                    )
                }
            }
        }
    }
    if (showUsage) UsageDialog(container, onDismiss = { showUsage = false })
}

@Composable
private fun TabIcon(tab: Tab, selected: Boolean, pendingCount: Int) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    if (tab == ChatsTab && pendingCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) { Text("$pendingCount") }
            },
        ) { Icon(icon, contentDescription = null) }
    } else {
        Icon(icon, contentDescription = null)
    }
}

@Composable
private fun TabLabel(tab: Tab, selected: Boolean) {
    Text(
        stringResource(tab.labelRes),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
    )
}
