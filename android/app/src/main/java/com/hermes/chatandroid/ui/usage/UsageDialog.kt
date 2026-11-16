package com.hermes.chatandroid.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.UsageSource
import com.hermes.chatandroid.data.UsageText
import com.hermes.chatandroid.data.UsageWindow
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 에이전트별 계정 사용량 보드(T-197). 맥미니 Bridge 가 `paseo usage` 를 읽어 준다. */
@Composable
fun UsageDialog(container: AppContainer, onDismiss: () -> Unit) {
    var sources by remember { mutableStateOf<List<UsageSource>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        val api = container.bridgeApi()
        if (api == null) {
            loading = false
            error = "Bridge 가 설정되지 않았습니다. 설정에서 Bridge 주소와 토큰을 입력하세요."
            return
        }
        loading = true
        try {
            sources = api.fetchUsage(refresh)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"
        }
        loading = false
    }
    LaunchedEffect(Unit) { load(refresh = false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("사용량", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { scope.launch { load(refresh = true) } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "새로 조회")
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "닫기") }
                }
                HorizontalDivider(thickness = 0.7.dp, color = MaterialTheme.hermesColors.cardBorder)
                Column(
                    Modifier
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let { ErrorBanner(it, onRetry = { scope.launch { load(refresh = false) } }) }
                    val list = sources
                    when {
                        list == null && loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("사용량을 불러오는 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        list != null && list.isEmpty() -> Text(
                            "표시할 계정이 없습니다. 맥미니 Paseo 에 로그인된 에이전트가 있어야 합니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                        else -> list?.forEach { UsageCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageCard(s: UsageSource) {
    val hc = MaterialTheme.hermesColors
    val shown = s.shown
    HermesCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                shown.planLabel?.let {
                    StatusPill(it, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.weight(1f))
                StatusPill(
                    text = if (s.report.available) "사용 가능" else "사용할 수 없음",
                    containerColor = Color.Transparent,
                    contentColor = if (s.report.available) hc.online else MaterialTheme.colorScheme.onSurfaceVariant,
                    dotColor = if (s.report.available) hc.online else hc.offline,
                )
            }
            s.problemText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            // 지금 못 읽는 계정은 마지막 성공 값을 흐리게
            Column(
                Modifier.alpha(if (s.report.available) 1f else 0.6f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                shown.windows.forEach { UsageBar(it) }
                shown.balances.forEach { b ->
                    LabelValue(b.label, listOfNotNull(b.remaining, "남음").joinToString(" "), danger = b.tone == "danger")
                }
                shown.details.forEach { LabelValue(it.label, it.value) }
            }
            if (s.report.available && shown.windows.isEmpty() && shown.balances.isEmpty() && shown.details.isEmpty()) {
                Text("한도 정보가 없습니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.account.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                UsageText.ago(s.shownAt)?.let {
                    Text("마지막 업데이트: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UsageBar(w: UsageWindow) {
    val hc = MaterialTheme.hermesColors
    val color = when (w.tone) {
        "danger" -> MaterialTheme.colorScheme.error
        "warning" -> hc.warning
        else -> hc.online
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Text(w.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                w.usedPct?.let { "$it% 사용됨" } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(
            progress = { (w.usedPct ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        UsageText.reset(w.resetsAt)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String, danger: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
