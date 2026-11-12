package com.hermes.chatandroid.ui.bots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hermes.chatandroid.AppContainer

/** STUB — 다른 에이전트가 이 파일만 교체한다. */
@Composable
fun BotChatScreen(
    container: AppContainer,
    channelId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("BotChatScreen (stub)") }
}
