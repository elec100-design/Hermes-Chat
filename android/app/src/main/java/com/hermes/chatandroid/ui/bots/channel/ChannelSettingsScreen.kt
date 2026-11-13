package com.hermes.chatandroid.ui.bots.channel

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
 * 채널 설정 화면 (T-173 이 구현). 시그니처는 고정.
 * [onDeleted]: 채널 삭제 성공 후 호출 → 네비게이션이 목록(홈)으로 복귀시킨다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsScreen(
    container: AppContainer,
    channelId: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("채널 설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            EmptyState("채널 설정", subtitle = "준비 중입니다 (채널 #$channelId)")
        }
    }
}
