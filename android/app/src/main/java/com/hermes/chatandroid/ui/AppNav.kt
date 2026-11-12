package com.hermes.chatandroid.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.hermes.chatandroid.ui.profiles.ProfileBoardScreen
import com.hermes.chatandroid.ui.sessions.SessionListScreen
import com.hermes.chatandroid.ui.settings.SettingsScreen

object Routes {
    const val BOTS = "bots"
    const val BOARD = "board"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val APPROVALS = "approvals"
    const val CHAT = "chat/{sessionId}"
    const val BOT_CHAT = "botChat/{channelId}"

    fun chat(sessionId: String) = "chat/${Uri.encode(sessionId)}"
    fun botChat(channelId: Int) = "botChat/$channelId"
}

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val BotsTab = Tab(Routes.BOTS, R.string.tab_bots, Icons.Filled.Chat)
private val BoardTab = Tab(Routes.BOARD, R.string.tab_board, Icons.Filled.Dashboard)
private val SessionsTab = Tab(Routes.SESSIONS, R.string.tab_sessions, Icons.Filled.History)
private val SettingsTab = Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings)

@Composable
fun AppNav(container: AppContainer) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()

    if (!settings.onboardingComplete) {
        OnboardingScreen(
            container = container,
            onFinished = { repo.update { it.copy(onboardingComplete = true) } },
        )
        return
    }

    val navController = rememberNavController()
    val botConfigured = settings.botConfigured
    val tabs = if (botConfigured) listOf(BotsTab, BoardTab, SessionsTab, SettingsTab)
    else listOf(BoardTab, SessionsTab, SettingsTab)
    // 시작 화면은 진입 시점에 한 번만 결정
    val startRoute = remember { if (botConfigured) Routes.BOTS else Routes.BOARD }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        NavHost(navController, startDestination = startRoute) {
            composable(Routes.BOTS) {
                BotsScreen(
                    container,
                    onOpenChannel = { navController.navigate(Routes.botChat(it)) },
                    onOpenApprovals = { navController.navigate(Routes.APPROVALS) },
                    modifier = mod,
                )
            }
            composable(Routes.BOARD) {
                ProfileBoardScreen(container, onOpenSession = { navController.navigate(Routes.chat(it)) }, modifier = mod)
            }
            composable(Routes.SESSIONS) {
                SessionListScreen(container, onOpenSession = { navController.navigate(Routes.chat(it)) }, modifier = mod)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container,
                    onRerunOnboarding = { repo.update { it.copy(onboardingComplete = false) } },
                    modifier = mod,
                )
            }
            composable(Routes.APPROVALS) {
                ApprovalsScreen(container, onBack = { navController.popBackStack() }, modifier = mod)
            }
            composable(
                Routes.CHAT,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
                ChatScreen(
                    container,
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    modifier = mod,
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
                )
            }
        }
    }
}
