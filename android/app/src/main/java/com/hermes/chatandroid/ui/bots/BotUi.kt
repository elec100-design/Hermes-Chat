package com.hermes.chatandroid.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.net.BotApi

/** 봇 API 오류 → 사용자 문구. 401 은 토큰 안내. */
fun botErrorText(e: Throwable): String =
    if (e is ApiException && e.code == 401) "인증 실패(401): 설정에서 봇 토큰을 확인하세요."
    else e.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"

private val TonePalette = listOf(
    Color(0xFF5B8DEF), Color(0xFF3FB68B), Color(0xFFE59A3A),
    Color(0xFFD9607A), Color(0xFF9B6FD6), Color(0xFF3FA9C9),
)

/** tone 1..6 → 포인트 색. null/범위 밖 = 기본(null). */
fun toneColor(tone: Int?): Color? = tone?.takeIf { it in 1..6 }?.let { TonePalette[it - 1] }

@Composable
fun BotAvatar(emoji: String, tone: Int?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val bg = toneColor(tone)?.copy(alpha = 0.25f) ?: MaterialTheme.colorScheme.secondaryContainer
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.5f).sp)
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
