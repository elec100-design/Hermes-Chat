package com.hermes.chatandroid.ui.bots.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.data.BotAttachment
import com.hermes.chatandroid.data.BotStep

const val MAX_BOT_ATTACHMENT_BYTES = 20 * 1024 * 1024

/** 전송 대기 중인 첨부(바이트는 선택 시점에 읽어 둔다). */
class PendingBotFile(val id: Long, val name: String, val mime: String?, val bytes: ByteArray)

/** Uri → 파일. 20MB 초과면 [IllegalStateException]. 블로킹 — IO 디스패처에서 호출. */
fun readPendingFile(resolver: ContentResolver, uri: Uri, id: Long): PendingBotFile {
    var name: String? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0)
    }
    val filename = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    val out = java.io.ByteArrayOutputStream()
    resolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BOT_ATTACHMENT_BYTES) throw IllegalStateException("$filename: 20MB를 넘는 파일은 첨부할 수 없습니다")
        }
    } ?: throw IllegalStateException("$filename: 파일을 열 수 없습니다")
    return PendingBotFile(id, filename, resolver.getType(uri), out.toByteArray())
}

/** 메시지에 붙은 첨부 — 서버에 첨부 다운로드 경로가 없어 파일명 칩으로 보여 준다. */
@Composable
fun BotAttachmentChips(items: List<BotAttachment>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { a ->
            AssistChip(
                onClick = {},
                label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(if (a.isImage) Icons.Filled.Image else Icons.Filled.AttachFile, null, Modifier.size(18.dp)) },
            )
        }
    }
}

/** 대기/생각 중 점 3개. */
@Composable
fun TypingDots(label: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(600, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * 접이식 "실행 단계" 행. [steps] == null 이면 아직 못 불러온 것 — 펼칠 때 [onExpand] 로 로드를 요청한다.
 */
@Composable
fun BotStepsSection(
    messageId: Int,
    steps: List<BotStep>?,
    running: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable {
                expanded = !expanded
                if (expanded) onExpand()
            }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
            Text(
                if (steps.isNullOrEmpty()) "실행 단계" else "실행 단계 ${steps.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
        if (expanded) {
            when {
                steps == null -> Text("불러오는 중…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                steps.isEmpty() -> Text("기록된 단계가 없습니다", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEach { s -> StepRow(s) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: BotStep) {
    var showAll by rememberSaveable(step.id, step.seq) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            step.status == "failed" || step.kind == "error" ->
                Icon(Icons.Filled.Error, "실패", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            step.status == "running" ->
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            else -> Icon(Icons.Filled.CheckCircle, "완료", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.title.ifBlank { step.kind }, style = MaterialTheme.typography.bodySmall)
            if (step.detail.isNotBlank()) {
                Text(
                    step.detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (showAll) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showAll = !showAll },
                )
            }
        }
    }
}
