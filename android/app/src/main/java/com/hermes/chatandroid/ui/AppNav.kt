package com.hermes.chatandroid.ui

import android.net.Uri
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.hermes.chatandroid.ui.more.MoreScreen
import com.hermes.chatandroid.ui.onboarding.OnboardingScreen
import com.hermes.chatandroid.ui.settings.SettingsScreen
import com.hermes.chatandroid.ui.theme.hermesColors
import com.hermes.chatandroid.ui.workspaces.WorkspacesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object Routes {
    /** 홈(대화) — 봇 DM 목록 + 채널. */
    const val BOTS = "bots"
    const val APPROVALS = "approvals"
    const val MORE = "more"
    const val SETTINGS = "settings"
    const val WORKSPACES = "workspaces"
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
private val ApprovalsTab = Tab(Routes.APPROVALS, R.string.tab_approvals, Icons.Filled.Notifications, Icons.Outlined.Notifications)
private val MoreTab = Tab(Routes.MORE, R.string.tab_more, Icons.Filled.GridView, Icons.Outlined.GridView)
private val Tabs = listOf(ChatsTab, ApprovalsTab, MoreTab)
private val TopRoutes = Tabs.map { it.route }.toSet()

/** 탭 바가 보이는 화면(설정은 더보기의 하위라 더보기 탭이 선택된 채 표시). */
private fun tabOf(route: String?): Tab? = when (route) {
    Routes.BOTS -> ChatsTab
    Routes.APPROVALS -> ApprovalsTab
    Routes.MORE, Routes.SETTINGS -> MoreTab
    else -> null
}

@Composable
fun AppNav(container: AppContainer) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()

    if (!settings.onboardingComplete) {
        OnboardingScreen(
            container = container,
            onFinished = { repo.update { it.copy(onboardingComplete = true) } },
            onDemo = { repo.update { it.copy(demoMode = true, onboardingComplete = true) } },
        )
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentTab = tabOf(currentRoute)
    var selectedBotChannelId by rememberSaveable { mutableStateOf<Int?>(null) }

    // 승인 탭 배지: 화면 이동마다 + 30초마다 대기 건수 갱신
    var pendingCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentRoute, settings.botHost, settings.botToken, settings.demoMode) {
        while (true) {
            pendingCount = try {
                container.botApi()?.fetchApprovals()?.size ?: 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingCount
            }
            delay(30_000)
        }
    }

    val wide = isWideWindow()
    // 좁은 창(커버 화면)에서 봇 채팅을 연 상태면 탭 바를 숨겨 채팅·키보드 공간을 확보한다.
    val inCompactChat = !wide && currentRoute == Routes.BOTS && selectedBotChannelId != null &&
        container.botApi() != null
    val navigateTab: (Tab) -> Unit = { tab ->
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (wide) {
            // 넓은 창(폴드 내부 화면): 세로 공간을 아끼도록 왼쪽 내비게이션 레일. 상세 화면에서도 유지.
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                header = { Spacer(Modifier.height(8.dp)) },
            ) {
                Tabs.forEach { tab ->
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
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                        ) {
                            Tabs.forEach { tab ->
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
                    )
                }
                composable(Routes.APPROVALS) {
                    ApprovalsScreen(container, modifier = mod)
                }
                composable(Routes.MORE) {
                    MoreScreen(
                        container = container,
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenWorkspaces = { navController.navigate(Routes.WORKSPACES) },
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
                composable(Routes.WORKSPACES) {
                    WorkspacesScreen(container, onBack = { navController.popBackStack() }, modifier = mod)
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
                            navController.popBackStack(Routes.BOTS, inclusive = false)
                        },
                        modifier = mod,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabIcon(tab: Tab, selected: Boolean, pendingCount: Int) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    if (tab == ApprovalsTab && pendingCount > 0) {
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
