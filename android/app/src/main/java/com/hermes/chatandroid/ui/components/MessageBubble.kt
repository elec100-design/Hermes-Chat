package com.hermes.chatandroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.chatandroid.net.ThinkFilter
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.delay

/** 채팅 말풍선. 사용자 = 오른쪽(브랜드 그라데이션), 봇 = 왼쪽(토널 카드). 본문은 <think> 제거 후 마크다운 렌더. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
    /** null 이면 기본 색. 봇 tone 틴트 등에 사용. */
    containerColor: Color? = null,
    borderColor: Color? = null,
    /** 지정되면 길게 누르기를 이 콜백이 처리한다(텍스트 선택 대신). */
    onLongClick: (() -> Unit)? = null,
    /** 말풍선 아래(같은 배경 안)에 붙일 추가 내용. */
    footer: (@Composable () -> Unit)? = null,
) {
    val visible = ThinkFilter.strip(text)
    val hc = MaterialTheme.hermesColors
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // 폴더블(Z Fold) 등 넓은 화면에서도 가독성을 위해 최대 폭 제한
        val maxBubbleWidth = minOf(maxWidth * 0.85f, 540.dp)
        val fg = if (isUser) hc.onUserBubble else MaterialTheme.colorScheme.onSurface
        val bubbleBrush: Brush = when {
            containerColor != null -> SolidColor(containerColor)
            isUser -> Brush.linearGradient(listOf(hc.userBubbleStart, hc.userBubbleEnd))
            else -> SolidColor(MaterialTheme.colorScheme.surface)
        }
        val resolvedBorder = when {
            borderColor != null -> borderColor
            isUser -> null
            else -> hc.cardBorder
        }
        val inlineCodeBg = if (isUser) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        val linkColor = if (isUser) Color.White else MaterialTheme.colorScheme.primary

        Column(
            Modifier
                .widthIn(max = maxBubbleWidth)
                .clip(shape)
                .background(bubbleBrush, shape)
                .then(if (resolvedBorder != null) Modifier.border(1.dp, resolvedBorder, shape) else Modifier)
                .then(if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.72f))
            }
            @Composable
            fun content() {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (block in splitMarkdownBlocks(visible)) {
                        when (block) {
                            is MdBlock.Heading -> {
                                val style = when (block.level) {
                                    1 -> MaterialTheme.typography.titleMedium
                                    2 -> MaterialTheme.typography.titleSmall
                                    else -> MaterialTheme.typography.labelLarge
                                }
                                Text(
                                    inlineMarkdown(block.text, codeBackground = inlineCodeBg, linkColor = linkColor),
                                    color = fg,
                                    style = style,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is MdBlock.Quote -> {
                                val barColor = if (isUser) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .drawBehind {
                                            drawLine(
                                                color = barColor,
                                                start = Offset(0f, 2.dp.toPx()),
                                                end = Offset(0f, size.height - 2.dp.toPx()),
                                                strokeWidth = 3.dp.toPx(),
                                            )
                                        }
                                        .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                                ) {
                                    Text(
                                        inlineMarkdown(block.text, codeBackground = inlineCodeBg, linkColor = linkColor),
                                        color = fg.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            is MdBlock.Text -> Text(
                                inlineMarkdown(
                                    normalizeMarkdownLists(block.text),
                                    codeBackground = inlineCodeBg,
                                    linkColor = linkColor,
                                ),
                                color = fg,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            is MdBlock.Code -> CodeBlockCard(language = block.language, code = block.code)
                        }
                    }
                }
            }
            if (onLongClick != null) content() else SelectionContainer { content() }
            footer?.invoke()
        }
    }
}

@Composable
private fun CodeBlockCard(language: String?, code: String) {
    val hc = MaterialTheme.hermesColors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = hc.codeSurface,
        border = BorderStroke(1.dp, hc.cardBorder),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(hc.codeHeader)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    language?.lowercase() ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = "코드 복사",
                        modifier = Modifier.size(13.dp),
                        tint = if (copied) hc.online else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (copied) "복사됨" else "복사",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) hc.online else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
