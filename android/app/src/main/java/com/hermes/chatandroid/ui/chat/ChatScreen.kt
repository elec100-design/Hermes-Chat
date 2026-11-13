package com.hermes.chatandroid.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.net.ThinkFilter
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.LoadingBox
import com.hermes.chatandroid.ui.components.MessageBubble

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

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                state.error?.let { ErrorBanner(it, onRetry = null) }
                if (state.recovering) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("응답 회수 중", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.attachments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.attachments.forEach { a ->
                            InputChip(
                                selected = false,
                                onClick = { vm.removeAttachment(a.id) },
                                label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp)) },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { picker.launch("*/*") }, enabled = !state.sending) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "첨부")
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("메시지") },
                        maxLines = 6,
                    )
                    if (state.sending) {
                        IconButton(onClick = { vm.stop() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "중지", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            onClick = { vm.send(input); input = "" },
                            enabled = input.isNotBlank() || state.attachments.isNotEmpty(),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "전송") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.messages.isEmpty() -> LoadingBox()
                state.messages.isEmpty() -> EmptyState("메시지를 입력해 대화를 시작하세요")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.key }) { m ->
                        if (m.streaming && ThinkFilter.strip(m.content).isBlank()) {
                            Text(
                                if (ThinkFilter.hasOpenThink(m.content)) "생각 중…" else "…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp),
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
