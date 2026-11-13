package com.hermes.chatandroid.ui.bots.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.data.BotAttachment
import com.hermes.chatandroid.data.BotStep
import com.hermes.chatandroid.ui.components.AnimatedTypingBubble
import com.hermes.chatandroid.ui.theme.hermesColors

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
    val hc = MaterialTheme.hermesColors
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { a ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, hc.cardBorder),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (a.isImage) Icons.Rounded.Image else Icons.Rounded.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        a.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 대기/생각 중 점 3개 말풍선. */
@Composable
fun TypingDots(label: String, modifier: Modifier = Modifier, accentColor: Color? = null) {
    AnimatedTypingBubble(label = label, modifier = modifier, accentColor = accentColor)
}

/**
 * 접이식 "실행 단계" 카드. [steps] == null 이면 아직 못 불러온 것 — 펼칠 때 [onExpand] 로 로드를 요청한다.
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
    val hc = MaterialTheme.hermesColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, hc.cardBorder),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (expanded) onExpand()
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Rounded.Build,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (steps.isNullOrEmpty()) "실행 단계" else "실행 단계 · ${steps.size}개",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        steps == null -> Text(
                            "불러오는 중…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        steps.isEmpty() -> Text(
                            "기록된 단계가 없습니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> steps.forEach { s -> StepRow(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: BotStep) {
    var showAll by rememberSaveable(step.id, step.seq) { mutableStateOf(false) }
    val hc = MaterialTheme.hermesColors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp)) {
            when {
                step.status == "failed" || step.kind == "error" ->
                    Icon(Icons.Rounded.Error, "실패", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                step.status == "running" ->
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Rounded.CheckCircle, "완료", Modifier.size(16.dp), tint = hc.online)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        step.kind.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    step.title.ifBlank { step.kind },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.detail.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = hc.codeSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showAll = !showAll },
                ) {
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (showAll) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}
