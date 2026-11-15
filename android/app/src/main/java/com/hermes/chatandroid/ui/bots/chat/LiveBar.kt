package com.hermes.chatandroid.ui.bots.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.glasses.GlassesState
import com.hermes.chatandroid.live.LivePhase
import com.hermes.chatandroid.live.LiveUi
import com.hermes.chatandroid.ui.components.ComposerButtonSize
import com.hermes.chatandroid.ui.components.ComposerIconButton
import com.hermes.chatandroid.ui.theme.hermesColors

@Composable
private fun phaseColor(phase: LivePhase): Color = when (phase) {
    LivePhase.LISTENING -> MaterialTheme.colorScheme.primary
    LivePhase.THINKING -> MaterialTheme.hermesColors.warning
    LivePhase.SPEAKING -> MaterialTheme.hermesColors.online
    LivePhase.WAITING, LivePhase.IDLE -> MaterialTheme.colorScheme.outline
}

/**
 * 입력 카드의 Live 버튼. 꺼져 있으면 시작, 켜져 있으면 글라스·이어폰 탭과 같은 동작
 * (듣는 중 = 바로 보내기, 답하는 중 = 끼어들기, 대기 = 다시 듣기).
 */
@Composable
fun LiveButton(phase: LivePhase?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (phase == null || phase == LivePhase.IDLE) {
        ComposerIconButton(Icons.Rounded.GraphicEq, "Live 대화 시작", onClick, modifier)
        return
    }
    val color = phaseColor(phase)
    val (icon, label) = when (phase) {
        LivePhase.LISTENING -> Icons.Rounded.Mic to "바로 보내기"
        LivePhase.SPEAKING -> Icons.Rounded.VolumeUp to "끼어들기"
        LivePhase.WAITING -> Icons.Rounded.Replay to "다시 듣기"
        else -> null to "생각 중"
    }
    val enabled = phase != LivePhase.THINKING
    Surface(
        modifier = modifier
            .height(ComposerButtonSize)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            else CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = color)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Live 가 이 채팅에서 켜져 있을 때 입력 카드 위에 뜨는 상태 줄: 상태·받아쓰기·사진 버튼·종료. */
@Composable
fun LiveBar(
    ui: LiveUi,
    glasses: GlassesState,
    onPhoto: () -> Unit,
    onGlassesSettings: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = phaseColor(ui.phase)
    val label = when (ui.phase) {
        LivePhase.LISTENING -> "듣는 중"
        LivePhase.THINKING -> "생각 중"
        LivePhase.SPEAKING -> "답하는 중"
        LivePhase.WAITING -> "대기"
        LivePhase.IDLE -> "꺼짐"
    }
    val detail = when {
        glasses.transfer != null -> "글라스 사진 받는 중… ${(glasses.transfer * 100).toInt()}%"
        ui.partial.isNotBlank() -> ui.partial
        ui.photoPending -> "사진 준비됨 — 질문하면 함께 보내요"
        ui.phase == LivePhase.THINKING && ui.lastUser.isNotBlank() -> ui.lastUser
        ui.phase == LivePhase.WAITING -> "버튼이나 글라스를 탭하면 다시 들어요"
        else -> if (ui.bluetooth) "블루투스 헤드셋(글라스)으로 듣고 말해요" else "폰 마이크·스피커 사용 중"
    }
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.hermesColors.cardBorder),
    ) {
        Column {
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .alpha(if (ui.phase == LivePhase.LISTENING || ui.phase == LivePhase.SPEAKING) pulse else 1f)
                        .clip(CircleShape)
                        .background(color)
                        .semantics { contentDescription = "Live 상태: $label" },
                )
                Column(Modifier.weight(1f)) {
                    Text("Live · $label", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (glasses.cameraReady) {
                    IconButton(onClick = onPhoto, enabled = glasses.transfer == null) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = "글라스로 사진 찍기")
                    }
                } else {
                    IconButton(onClick = onGlassesSettings) {
                        Icon(
                            Icons.Rounded.NoPhotography,
                            contentDescription = "글라스 카메라 연결 안 됨 — 설정 열기",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onStop) { Text("종료") }
            }
            (ui.error ?: glasses.error)?.let { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Rounded.Close, contentDescription = "오류 닫기", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}
