package com.hermes.chatandroid.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.net.ThinkFilter
import com.hermes.chatandroid.ui.components.AnimatedTypingBubble
import com.hermes.chatandroid.ui.components.ChatComposerDock
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.MessageBubble
import com.hermes.chatandroid.ui.theme.hermesColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 특정 봇의 게이트웨이 포트를 대상으로 열 때. null 이면 설정에서 선택된 프로필. */
    port: Int? = null,
) {
    val vm: ChatViewModel = viewModel(key = "chat/$port/$sessionId", factory = ChatViewModel.factory(container, sessionId, port))
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.addAttachments(context.contentResolver, uris)
    }
    val listState = rememberLazyListState()
    val lastLen = state.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(state.messages.size, lastLen, state.recovering) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    val hc = MaterialTheme.hermesColors

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    title = {
                        Column {
                            Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                port?.let { "게이트웨이 :$it" } ?: "게이트웨이 세션",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
                    },
                )
                HorizontalDivider(thickness = 0.7.dp, color = hc.cardBorder)
            }
        },
        bottomBar = {
            ChatComposerDock(
                value = input,
                onValueChange = { input = it },
                onAttach = { picker.launch("*/*") },
                attachEnabled = !state.sending,
                onSend = { vm.send(input); input = "" },
                sendEnabled = input.isNotBlank() || state.attachments.isNotEmpty(),
                sending = state.sending,
                onStop = { vm.stop() },
                topContent = if (state.error != null || state.recovering || state.attachments.isNotEmpty()) {
                    {
                        state.error?.let { ErrorBanner(it, onRetry = null) }
                        if (state.recovering) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                Text("응답 회수 중…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.attachments.isNotEmpty()) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.attachments.forEach { a ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    ) {
                                        Row(
                                            Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(Icons.Rounded.AttachFile, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                            Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Icon(
                                                Icons.Rounded.Close,
                                                "제거",
                                                Modifier.size(16.dp).clip(CircleShape).clickable { vm.removeAttachment(a.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else null,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.messages.isEmpty() -> LoadingBox(label = "세션을 불러오는 중…")
                state.messages.isEmpty() -> EmptyState(
                    title = "대화를 시작해 보세요",
                    subtitle = "아래 입력창에 질문이나 작업을 입력하면 실시간으로 응답합니다.",
                    icon = Icons.Rounded.AutoAwesome,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.key }) { m ->
                        if (m.streaming && ThinkFilter.strip(m.content).isBlank()) {
                            AnimatedTypingBubble(
                                label = if (ThinkFilter.hasOpenThink(m.content)) "생각 중" else "응답 준비 중",
                            )
                        } else {
                            MessageBubble(text = m.content, isUser = m.isUser)
                        }
                    }
                }
            }
        }
    }
}
