package com.hermes.chatandroid.ui.bots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.chatandroid.data.BotMember
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.net.BotApi
import com.hermes.chatandroid.ui.theme.hermesColors

/** 봇 API 오류 → 사용자 문구. 401 은 토큰 안내. */
fun botErrorText(e: Throwable): String =
    if (e is ApiException && e.code == 401) "인증 실패(401): 설정에서 봇 토큰을 확인하세요."
    else e.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"

private val TonePalette = listOf(
    Color(0xFF5B8DEF), Color(0xFF2CB67D), Color(0xFFF59E0B),
    Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF06B6D4),
)

/** tone 1..6 → 포인트 색. null/범위 밖 = 기본(null). */
fun toneColor(tone: Int?): Color? = tone?.takeIf { it in 1..6 }?.let { TonePalette[it - 1] }

@Composable
fun BotAvatar(
    emoji: String,
    tone: Int?,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    online: Boolean? = null,
) {
    val accent = toneColor(tone) ?: MaterialTheme.colorScheme.primary
    val isDark = MaterialTheme.hermesColors.isDark
    val preset = BotAvatarPresets.find(emoji)
    val bgBrush = Brush.linearGradient(
        listOf(
            accent.copy(alpha = if (isDark) 0.28f else 0.18f),
            accent.copy(alpha = if (isDark) 0.12f else 0.08f),
        ),
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(bgBrush)
                .border(
                    width = if (preset != null && size >= 56.dp) 1.5.dp else 1.dp,
                    color = accent.copy(alpha = if (isDark) 0.48f else 0.32f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (preset != null) {
                Image(
                    bitmap = ImageBitmap.imageResource(preset.drawableRes),
                    contentDescription = preset.label,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(emoji, fontSize = (size.value * 0.46f).sp)
            }
        }
        if (online != null) {
            val dotSize = (size * 0.27f).coerceIn(9.dp, 16.dp)
            val dotColor = if (online) MaterialTheme.hermesColors.online else MaterialTheme.hermesColors.offline
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/** 채널 목록용 겹쳐진 미니 멤버 아바타 스택. */
@Composable
fun ChannelAvatarGroup(
    members: List<BotMember>,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    val shown = members.take(3)
    if (shown.isEmpty()) {
        BotAvatar("💬", null, size = size, modifier = modifier)
        return
    }
    if (shown.size == 1) {
        val m = shown.first()
        BotAvatar(m.avatarText, m.tone, size = size, modifier = modifier)
        return
    }
    val scale = size.value / 48f
    val itemSize = (if (shown.size == 2) 32f else 28f).let { (it * scale).dp }
    Box(modifier.size(size)) {
        shown.forEachIndexed { idx, m ->
            val offset = when (shown.size) {
                2 -> if (idx == 0) 0.dp to (2f * scale).dp else (16f * scale).dp to (14f * scale).dp
                else -> when (idx) {
                    0 -> 0.dp to (2f * scale).dp
                    1 -> (18f * scale).dp to (2f * scale).dp
                    else -> (9f * scale).dp to (18f * scale).dp
                }
            }
            Box(
                Modifier
                    .offset(x = offset.first, y = offset.second)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .clip(CircleShape),
            ) {
                BotAvatar(m.avatarText, m.tone, size = itemSize)
            }
        }
    }
}

/** 봇 서버 연결 확인 결과. [ok] 면 [message] 는 성공 안내. */
data class BotConnectionResult(val ok: Boolean, val message: String)

/**
 * `/health`(무인증)로 도달성, 이어서 인증이 필요한 `/api/bots` 로 토큰을 확인한다.
 * 401 은 "네트워크"가 아니라 토큰 문제로 분명히 안내한다.
 */
suspend fun checkBotServer(api: BotApi): BotConnectionResult {
    if (!api.health()) return BotConnectionResult(false, "서버에 연결할 수 없습니다. 주소와 Tailscale 연결을 확인하세요.")
    return try {
        val n = api.fetchBots(activeOnly = false).size
        BotConnectionResult(true, "연결 성공 (봇 ${n}개)")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code == 401) BotConnectionResult(false, "토큰이 올바르지 않거나 필요합니다. 서버에 HB_API_TOKEN 이 설정돼 있으면 같은 토큰을 입력하세요.")
        else BotConnectionResult(false, e.message ?: "서버 오류")
    } catch (e: Exception) {
        BotConnectionResult(false, e.message?.takeIf { it.isNotBlank() } ?: "서버에 연결할 수 없습니다.")
    }
}
