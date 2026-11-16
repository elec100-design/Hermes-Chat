package com.hermes.chatandroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.data.ComposerCaret
import com.hermes.chatandroid.ui.theme.hermesColors

/**
 * 채팅 화면 하단 입력 카드. 위 = 글 입력, 아래 = 도구 줄(왼쪽: 첨부 + [leadingActions], 오른쪽: [trailingActions] + 보내기).
 * 넓은 화면에서는 가운데 정렬·최대 폭 제한.
 */
@Composable
fun ChatComposerDock(
    value: String,
    onValueChange: (String) -> Unit,
    onAttach: () -> Unit,
    attachEnabled: Boolean,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    modifier: Modifier = Modifier,
    sending: Boolean = false,
    onStop: (() -> Unit)? = null,
    placeholder: String = "메시지를 입력하세요…",
    topContent: (@Composable ColumnScope.() -> Unit)? = null,
    /** 첨부(+) 버튼 오른쪽에 붙는 도구(작업 폴더 등). */
    leadingActions: (@Composable RowScope.() -> Unit)? = null,
    /** 보내기 버튼 왼쪽에 붙는 도구(Live 등). */
    trailingActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val hc = MaterialTheme.hermesColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (topContent != null) {
                Column(
                    Modifier.widthIn(max = ComposerMaxWidth).fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = topContent,
                )
            }
            Surface(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
                    .widthIn(max = ComposerMaxWidth)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, hc.cardBorder),
                shadowElevation = 2.dp,
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                            )
                        }
                        // String 오버로드는 칩이 글을 밖에서 바꿔도 선택을 0에 남긴다.
                        // 바깥 변경이면 커서를 글 끝(에이전트 이름 뒤)으로 옮긴다.
                        var textFieldValueState by remember {
                            mutableStateOf(
                                TextFieldValue(
                                    text = value,
                                    selection = TextRange(ComposerCaret.caretAfterExternalEdit(value)),
                                ),
                            )
                        }
                        val textFieldValue = if (textFieldValueState.text == value) {
                            textFieldValueState
                        } else {
                            TextFieldValue(
                                text = value,
                                selection = TextRange(ComposerCaret.caretAfterExternalEdit(value)),
                            )
                        }
                        SideEffect {
                            if (textFieldValueState != textFieldValue) textFieldValueState = textFieldValue
                        }
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { updated ->
                                textFieldValueState = updated
                                if (updated.text != value) onValueChange(updated.text)
                            },
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ComposerIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "파일 첨부",
                            onClick = onAttach,
                            enabled = attachEnabled,
                        )
                        leadingActions?.invoke(this)
                        Spacer(Modifier.weight(1f))
                        trailingActions?.invoke(this)

                        when {
                            onStop != null && sending -> {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(ComposerButtonSize),
                                ) {
                                    IconButton(onClick = onStop) {
                                        Icon(
                                            Icons.Rounded.Stop,
                                            contentDescription = "중지",
                                            tint = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                            else -> {
                                val activeBg by animateColorAsState(
                                    if (sendEnabled && !sending) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    animationSpec = tween(160),
                                    label = "sendBg",
                                )
                                val activeFg by animateColorAsState(
                                    if (sendEnabled && !sending) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    animationSpec = tween(160),
                                    label = "sendFg",
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = activeBg,
                                    modifier = Modifier.size(ComposerButtonSize),
                                ) {
                                    if (sending) {
                                        Box(contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(
                                                Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    } else {
                                        IconButton(onClick = onSend, enabled = sendEnabled) {
                                            Icon(
                                                Icons.Rounded.ArrowUpward,
                                                contentDescription = "보내기",
                                                tint = activeFg,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 입력 카드 최대 폭(폴드 펼침 화면에서 줄이 너무 길어지지 않게). */
val ComposerMaxWidth = 760.dp
val ComposerButtonSize = 40.dp

/** 입력 카드 도구 줄의 테두리 원형 아이콘 버튼. */
@Composable
fun ComposerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.hermesColors.cardBorder),
        modifier = modifier.size(ComposerButtonSize),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 대기/생각 중 애니메이션 말풍선. */
@Composable
fun AnimatedTypingBubble(
    label: String,
    modifier: Modifier = Modifier,
    accentColor: androidx.compose.ui.graphics.Color? = null,
) {
    val hc = MaterialTheme.hermesColors
    val t = rememberInfiniteTransition(label = "dots")
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = accentColor?.copy(alpha = if (hc.isDark) 0.14f else 0.08f) ?: MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accentColor?.copy(alpha = 0.32f) ?: hc.cardBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i ->
                val a by t.animateFloat(
                    0.25f, 1f,
                    infiniteRepeatable(tween(550, delayMillis = i * 150), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .size(7.dp)
                        .alpha(a)
                        .clip(CircleShape)
                        .background(accentColor ?: MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
