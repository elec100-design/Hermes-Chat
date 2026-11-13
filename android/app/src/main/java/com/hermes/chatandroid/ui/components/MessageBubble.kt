package com.hermes.chatandroid.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.net.ThinkFilter

/** 채팅 말풍선. 사용자 = 오른쪽, 그 외 = 왼쪽. 본문은 <think> 제거 후 마크다운-lite 렌더. */
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
    Box(modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        val bg = containerColor ?: if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .background(bg, RoundedCornerShape(16.dp))
                .then(if (borderColor != null) Modifier.border(1.dp, borderColor, RoundedCornerShape(16.dp)) else Modifier)
                .then(if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
            }
            @Composable
            fun content() {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (block in splitMarkdownBlocks(visible)) {
                        when (block) {
                            is MdBlock.Text -> Text(inlineMarkdown(normalizeMarkdownLists(block.text)), color = fg, style = MaterialTheme.typography.bodyMedium)
                            is MdBlock.Code -> Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    .horizontalScroll(rememberScrollState())
                                    .padding(8.dp)
                            ) {
                                Text(
                                    block.code,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            if (onLongClick != null) content() else SelectionContainer { content() }
            footer?.invoke()
        }
    }
}
