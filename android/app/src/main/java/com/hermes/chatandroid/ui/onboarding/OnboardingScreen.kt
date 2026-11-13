package com.hermes.chatandroid.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Settings
import com.hermes.chatandroid.ui.bots.checkBotServer
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.IconBadge
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.launch

private const val DEFAULT_BOT_URL = "http://100.83.59.60:8900"

/** 환영 → Hermes Bot 서버 → (선택) 게이트웨이 → 완료. */
@Composable
fun OnboardingScreen(
    container: AppContainer,
    onFinished: () -> Unit,
    onDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = container.settingsRepository
    var step by rememberSaveable { mutableStateOf(0) }
    var botUrl by rememberSaveable { mutableStateOf(repo.current.botHost.ifBlank { DEFAULT_BOT_URL }) }
    var botToken by rememberSaveable { mutableStateOf(repo.current.botToken) }
    var botOk by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf(repo.current.host) }
    var apiKey by rememberSaveable { mutableStateOf(repo.current.apiKey) }
    var profiles by remember { mutableStateOf(repo.current.profiles) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hc = MaterialTheme.hermesColors

    fun saveBot() = repo.update { it.copy(botHost = botUrl.trim(), botToken = botToken.trim()) }
    fun saveGateway() = repo.update { it.copy(host = host.trim(), apiKey = apiKey.trim(), profiles = profiles) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // 상단 단계 인디케이터
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { idx ->
                    val barColor by animateColorAsState(
                        if (idx <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        animationSpec = tween(220),
                        label = "step$idx",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(barColor),
                    )
                }
            }

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                label = "onboardingStep",
            ) { currentStep ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (currentStep) {
                        0 -> {
                            Spacer(Modifier.height(12.dp))
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(hc.userBubbleStart, hc.userBubbleEnd),
                                            ),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                                Text("HermesChat", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                                Text(
                                    "맥미니의 Hermes 에이전트들과\n어디서나 대화하고 작업을 승인하세요.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            FeatureHighlightCard(
                                icon = Icons.Rounded.Lock,
                                tint = MaterialTheme.colorScheme.primary,
                                title = "사설망 전용 프라이버시",
                                desc = "Tailscale 네트워크 안에서만 통신하며 외부 클라우드로 데이터가 나가지 않습니다.",
                            )
                            FeatureHighlightCard(
                                icon = Icons.Rounded.Forum,
                                tint = Color(0xFF0EA5E9),
                                title = "봇 DM & 멀티 에이전트 채널",
                                desc = "각 프로필 봇과의 1:1 대화는 물론 채널에서 @멘션으로 협업할 수 있습니다.",
                            )
                            FeatureHighlightCard(
                                icon = Icons.Rounded.VerifiedUser,
                                tint = hc.online,
                                title = "실행 단계 & 승인 제어",
                                desc = "도구 실행 과정을 실시간으로 확인하고 위험 작업을 원격으로 승인합니다.",
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        1 -> {
                            StepHeader(
                                icon = Icons.Rounded.Dns,
                                tint = MaterialTheme.colorScheme.primary,
                                title = "Hermes Bot 서버 연결",
                                subtitle = "봇 목록, DM, 채널 대화를 제공하는 서버 주소를 입력하세요.",
                            )
                            HermesCard {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    HermesTextField(
                                        value = botUrl,
                                        onValueChange = { botUrl = it; botOk = false },
                                        label = "서버 주소",
                                        placeholder = DEFAULT_BOT_URL,
                                        keyboardType = KeyboardType.Uri,
                                    )
                                    HermesTextField(
                                        value = botToken,
                                        onValueChange = { botToken = it; botOk = false },
                                        label = "토큰 (서버에 설정된 경우만)",
                                        secret = true,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            saveBot()
                                            busy = true; error = null; info = null
                                            scope.launch {
                                                val api = container.botApi()
                                                if (api == null) error = "서버 주소를 입력하세요."
                                                else {
                                                    val r = checkBotServer(api)
                                                    botOk = r.ok
                                                    if (r.ok) info = r.message else error = r.message
                                                }
                                                busy = false
                                            }
                                        },
                                        enabled = !busy && botUrl.isNotBlank(),
                                        shape = CircleShape,
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                    ) {
                                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else {
                                            Icon(Icons.Rounded.WifiTethering, null, Modifier.size(17.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("연결 확인")
                                        }
                                    }
                                }
                            }
                            error?.let { ErrorBanner(it) }
                            info?.let { ConnectionOkBanner(it) }
                        }

                        2 -> {
                            StepHeader(
                                icon = Icons.Rounded.Hub,
                                tint = Color(0xFF0EA5E9),
                                title = "게이트웨이 (선택 사항)",
                                subtitle = "봇 서버 외에 각 봇의 예전 게이트웨이 세션을 열 때 필요합니다. 나중에 설정해도 됩니다.",
                            )
                            HermesCard {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    HermesTextField(
                                        value = host,
                                        onValueChange = { host = it },
                                        label = "호스트",
                                        placeholder = "100.83.59.60",
                                        keyboardType = KeyboardType.Uri,
                                    )
                                    HermesTextField(
                                        value = apiKey,
                                        onValueChange = { apiKey = it },
                                        label = "API 키",
                                        secret = true,
                                    )
                                    if (profiles.isNotEmpty()) {
                                        Text(
                                            "검색된 프로필: " + profiles.joinToString { "${it.name}(:${it.port})" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            busy = true; error = null; info = null
                                            scope.launch {
                                                val scan = container.profileDiscovery.scan(host, apiKey.trim())
                                                val err = scan.errorMessage
                                                if (err != null) error = err
                                                else {
                                                    profiles = scan.profiles
                                                    info = "${scan.profiles.size}개 프로필을 찾았습니다."
                                                }
                                                busy = false
                                            }
                                        },
                                        enabled = !busy && host.isNotBlank(),
                                        shape = CircleShape,
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                    ) {
                                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else {
                                            Icon(Icons.Rounded.Search, null, Modifier.size(17.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("프로필 자동 검색")
                                        }
                                    }
                                }
                            }
                            error?.let { ErrorBanner(it) }
                            info?.let { ConnectionOkBanner(it) }
                        }

                        else -> {
                            Spacer(Modifier.height(32.dp))
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(84.dp)
                                        .clip(CircleShape)
                                        .background(hc.online.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, null, tint = hc.online, modifier = Modifier.size(44.dp))
                                }
                                Text("모든 준비가 끝났습니다", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                Text(
                                    "대화 탭에서 원하는 봇을 선택해 바로 대화를 시작하세요.\n서버 설정은 더보기 → 설정에서 언제든 바꿀 수 있습니다.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            // 하단 고정 내비게이션 버튼
            Spacer(Modifier.height(12.dp))
            when (step) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { step = 1 },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text("서버 연결하기")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    }
                    OutlinedButton(
                        onClick = onDemo,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text("데모 모드로 둘러보기")
                    }
                    Text(
                        "서버가 없어도 샘플 봇·채널·승인 화면을 바로 체험할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                1 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ step = 0; error = null; info = null }) { Text("이전") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            saveBot()
                            if (host.isBlank()) host = Settings.hostOnly(botUrl)
                            error = null; info = null
                            step = 2
                        },
                        enabled = botUrl.isNotBlank(),
                        shape = CircleShape,
                        modifier = Modifier.height(46.dp),
                    ) { Text(if (botOk) "다음" else "연결 확인 없이 다음") }
                }
                2 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ step = 1; error = null; info = null }) { Text("이전") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { error = null; info = null; step = 3 },
                            shape = CircleShape,
                            modifier = Modifier.height(46.dp),
                        ) { Text("건너뛰기") }
                        Button(
                            onClick = { saveGateway(); error = null; info = null; step = 3 },
                            enabled = host.isNotBlank() && profiles.isNotEmpty(),
                            shape = CircleShape,
                            modifier = Modifier.height(46.dp),
                        ) { Text("다음") }
                    }
                }
                else -> Button(
                    onClick = onFinished,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("HermesChat 시작하기") }
            }
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, tint: Color, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconBadge(icon = icon, tint = tint, size = 48.dp, iconSize = 24.dp, shapeRadius = 14.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeatureHighlightCard(icon: ImageVector, tint: Color, title: String, desc: String) {
    HermesCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon = icon, tint = tint, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectionOkBanner(msg: String) {
    val hc = MaterialTheme.hermesColors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = hc.online.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, hc.online.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = hc.online)
            Text(msg, style = MaterialTheme.typography.bodySmall, color = hc.online, fontWeight = FontWeight.Medium)
        }
    }
}
