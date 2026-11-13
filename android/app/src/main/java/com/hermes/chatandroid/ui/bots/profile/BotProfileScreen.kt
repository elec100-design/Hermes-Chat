package com.hermes.chatandroid.ui.bots.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.ui.components.EmptyState

/**
 * 봇 프로필 화면 (T-172 가 구현). 시그니처는 고정.
 * - [onOpenSession]: 이 봇 게이트웨이([port])의 과거 세션 보기 → 라우트 chat/{sessionId}?port={port}
 * - [onOpenChannel]: 봇 DM 등 채널 채팅 열기
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotProfileScreen(
    container: AppContainer,
    botId: Int,
    onBack: () -> Unit,
    onOpenSession: (port: Int, sessionId: String) -> Unit,
    onOpenChannel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("봇 프로필") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            EmptyState("봇 프로필", subtitle = "준비 중입니다 (봇 #$botId)")
        }
    }
}
