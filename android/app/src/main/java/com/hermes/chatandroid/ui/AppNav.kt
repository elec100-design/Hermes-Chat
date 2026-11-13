package com.hermes.chatandroid.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
import com.hermes.chatandroid.ui.chat.ChatScreen
import com.hermes.chatandroid.ui.onboarding.OnboardingScreen
import com.hermes.chatandroid.ui.bots.channel.ChannelSettingsScreen
import com.hermes.chatandroid.ui.bots.profile.BotProfileScreen
import com.hermes.chatandroid.ui.more.MoreScreen
import com.hermes.chatandroid.ui.settings.SettingsScreen
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

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val ChatsTab = Tab(Routes.BOTS, R.string.tab_chats, Icons.Filled.Chat)
private val ApprovalsTab = Tab(Routes.APPROVALS, R.string.tab_approvals, Icons.Filled.Notifications)
private val MoreTab = Tab(Routes.MORE, R.string.tab_more, Icons.Filled.MoreHoriz)
private val Tabs = listOf(ChatsTab, ApprovalsTab, MoreTab)

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

    Scaffold(
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab == ApprovalsTab && pendingCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$pendingCount") } }) { Icon(tab.icon, contentDescription = null) }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        NavHost(navController, startDestination = Routes.BOTS) {
            composable(Routes.BOTS) {
                BotsScreen(
                    container,
                    onOpenChannel = { navController.navigate(Routes.botChat(it)) },
                    onOpenBotProfile = { navController.navigate(Routes.botProfile(it)) },
                    modifier = mod,
                )
            }
            composable(Routes.APPROVALS) {
                ApprovalsScreen(container, modifier = mod)
            }
            composable(Routes.MORE) {
                MoreScreen(
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
                    onOpenChannel = { navController.navigate(Routes.botChat(it)) },
                    modifier = mod,
                )
            }
            composable(
                Routes.CHANNEL_SETTINGS,
                arguments = listOf(navArgument("channelId") { type = NavType.IntType }),
            ) { entry ->
                ChannelSettingsScreen(
                    container,
                    channelId = entry.arguments?.getInt("channelId") ?: 0,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack(Routes.BOTS, inclusive = false) },
                    modifier = mod,
                )
            }
        }
    }
}
