package com.hermes.chatandroid.ui.bots.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.data.BotMember
import com.hermes.chatandroid.data.Discussion
import com.hermes.chatandroid.data.DiscussionRequest
import com.hermes.chatandroid.ui.components.ComposerIconButton
import com.hermes.chatandroid.ui.theme.hermesColors

/** 서버 기본값(HB_DISCUSSION_TURNS)·상한(HB_DISCUSSION_TURNS_MAX)과 같다. 진행자는 이 값의 2배. */
private const val DefaultTurns = 3
private val TurnChoices = listOf(2, 3, 4)

/** 입력 카드의 토론 버튼 — 봇이 둘 이상인 채널에서만 보인다. */
@Composable
fun DiscussionButton(running: Boolean, onClick: () -> Unit) {
    ComposerIconButton(
        icon = Icons.Rounded.Forum,
        contentDescription = if (running) "토론 진행 중" else "토론 열기",
        onClick = onClick,
        enabled = !running,
        tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 토론 열기 — 주제·진행자·참가자·발언 횟수를 정한다.
 * [error] 는 서버가 거절한 이유(봇 수 초과·이미 진행 중 등)로, 시트를 닫지 않고 그 자리에 보여 준다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionSheet(
    members: List<BotMember>,
    initialTopic: String,
    starting: Boolean,
    error: String?,
    onStart: (DiscussionRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var topic by remember { mutableStateOf(initialTopic) }
    var moderatorId by remember { mutableStateOf(members.firstOrNull()?.id) }
    var selected by remember { mutableStateOf(members.map { it.id }.toSet()) }
    var turns by remember { mutableStateOf(DefaultTurns) }
    var allowTools by remember { mutableStateOf(false) }
    val participants = selected - setOfNotNull(moderatorId)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("토론 열기", style = MaterialTheme.typography.titleLarge)
                Text(
                    "참가 봇들이 먼저 각자 의견을 내고, 진행자가 쟁점을 정리하며 지목해 조율한 뒤 최종 결론을 씁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("주제") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("진행자")
            ChipRow {
                members.forEach { m ->
                    FilterChip(
                        selected = moderatorId == m.id,
                        onClick = { moderatorId = m.id },
                        label = { Text("@${m.slug}") },
                    )
                }
            }

            SectionLabel("참가자")
            ChipRow {
                members.filter { it.id != moderatorId }.forEach { m ->
                    FilterChip(
                        selected = m.id in selected,
                        onClick = { selected = if (m.id in selected) selected - m.id else selected + m.id },
                        label = { Text("@${m.slug}") },
                    )
                }
            }

            SectionLabel("봇마다 발언 횟수 · 진행자는 ${turns * 2}회")
            ChipRow {
                TurnChoices.forEach { n ->
                    FilterChip(selected = turns == n, onClick = { turns = n }, label = { Text("${n}회") })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("도구 사용 허용", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "웹 검색·파일 읽기 등. 켜면 한 발언이 몇 분씩 걸릴 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowTools, onCheckedChange = { allowTools = it })
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    onStart(
                        DiscussionRequest(
                            topic = topic.trim(),
                            moderatorId = moderatorId,
                            botIds = (participants + setOfNotNull(moderatorId)).toList(),
                            turns = turns,
                            allowTools = allowTools,
                        ),
                    )
                },
                enabled = topic.isNotBlank() && moderatorId != null && participants.isNotEmpty() && !starting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("토론 시작")
            }
        }
    }
}

/** 토론이 도는 동안 입력 카드 위에 뜨는 줄 — 누가 몇 번 말했는지와 중지. */
@Composable
fun DiscussionBar(discussion: Discussion, onStop: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Forum, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text("토론 중", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                discussion.members.forEach { m ->
                    Text(
                        (if (m.isModerator) "진행 " else "") + "@${m.slug} ${m.turnsUsed}/${m.turnsMax}" +
                            (if (m.active) "" else " 제외"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (m.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            TextButton(onClick = onStop) { Text("중지") }
        }
    }
}

/** 봇 이름 옆 꼬리표 — 진행자의 정리와 최종 결론을 보통 발언과 구분한다. */
@Composable
fun DiscussionKindLabel(kind: String?) {
    val label = when (kind) {
        "moderation" -> "진행"
        "conclusion" -> "최종 결론"
        else -> return
    }
    val strong = kind == "conclusion"
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.hermesColors.cardBorder,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (strong) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
