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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.ui.theme.hermesColors

/** 채팅 화면 하단 플로팅 알약형 입력 바. */
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
) {
    val hc = MaterialTheme.hermesColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            HorizontalDivider(thickness = 0.7.dp, color = hc.cardBorder)
            if (topContent != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = topContent,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, hc.cardBorder),
                    modifier = Modifier.size(44.dp),
                ) {
                    IconButton(onClick = onAttach, enabled = attachEnabled) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "파일 첨부",
                            tint = if (attachEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, hc.cardBorder),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                when {
                    onStop != null && sending -> {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier.size(44.dp),
                        ) {
                            IconButton(onClick = onStop) {
                                Icon(
                                    Icons.Rounded.Stop,
                                    contentDescription = "중지",
                                    tint = MaterialTheme.colorScheme.error,
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
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            animationSpec = tween(160),
                            label = "sendFg",
                        )
                        Surface(
                            shape = CircleShape,
                            color = activeBg,
                            modifier = Modifier.size(44.dp),
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
                                        Icons.AutoMirrored.Rounded.Send,
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
