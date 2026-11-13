package com.hermes.chatandroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.ui.bots.botErrorText
import com.hermes.chatandroid.ui.bots.checkBotServer
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.HermesTextField
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.components.readableWidth
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.launch

private const val PRIVACY_URL = "https://hermeschat.app/privacy"

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    HermesCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onRerunOnboarding: () -> Unit,
    onExitDemo: () -> Unit,
    modifier: Modifier = Modifier,
    /** 더보기 → 설정으로 들어온 경우 뒤로 버튼. */
    onBack: (() -> Unit)? = null,
) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "-"
    }
    val hc = MaterialTheme.hermesColors

    var newName by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var botTesting by remember { mutableStateOf(false) }
    var botResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("설정", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxHeight().readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (settings.demoMode) {
                HermesCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("데모 모드 활성", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "샘플 데이터로 앱을 체험 중입니다. 실제 서버에는 연결하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onExitDemo, Modifier.fillMaxWidth().height(44.dp), shape = CircleShape) {
                            Text("데모 모드 종료")
                        }
                    }
                }
            }

            error?.let { ErrorBanner(it) }
            message?.let {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = hc.online.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = hc.online)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = hc.online)
                    }
                }
            }

            SectionCard("Hermes Bot 서버", "봇 목록, DM, 채널, 승인 기능을 제공합니다.") {
                HermesTextField(
                    value = settings.botHost,
                    onValueChange = { v -> repo.update { it.copy(botHost = v) } },
                    label = "서버 주소",
                    placeholder = "http://100.83.59.60:8900",
                    keyboardType = KeyboardType.Uri,
                )
                HermesTextField(
                    value = settings.botToken,
                    onValueChange = { v -> repo.update { it.copy(botToken = v) } },
                    label = "토큰 (서버에 설정된 경우만)",
                    secret = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val api = container.botApi()
                            if (api == null) { botResult = false to "서버 주소를 입력하세요"; return@OutlinedButton }
                            botTesting = true; botResult = null
                            scope.launch {
                                val r = checkBotServer(api)
                                botResult = r.ok to r.message
                                botTesting = false
                            }
                        },
                        enabled = !botTesting,
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (botTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Rounded.WifiTethering, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("연결 테스트")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val api = container.botApi() ?: return@OutlinedButton
                            botTesting = true; botResult = null
                            scope.launch {
                                botResult = try {
                                    val r = api.discoverBots()
                                    true to "프로필 ${r.found}개 확인 (새 봇 ${r.created}개)"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    false to botErrorText(e)
                                }
                                botTesting = false
                            }
                        },
                        enabled = !botTesting && settings.botConfigured,
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("프로필 재검색")
                    }
                }
                botResult?.let { (ok, msg) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (ok) hc.online.copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                null,
                                Modifier.size(18.dp),
                                tint = if (ok) hc.online else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) hc.online else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            SectionCard("게이트웨이 (지난 세션 보기용)", "각 봇의 게이트웨이 포트로 직결할 때 사용합니다.") {
                HermesTextField(
                    value = settings.host,
                    onValueChange = { v -> repo.update { it.copy(host = v) } },
                    label = "호스트",
                    placeholder = "http://100.83.59.60",
                    keyboardType = KeyboardType.Uri,
                )
                HermesTextField(
                    value = settings.apiKey,
                    onValueChange = { v -> repo.update { it.copy(apiKey = v) } },
                    label = "API 키",
                    secret = true,
                )
            }

            SectionCard("프로필 (${settings.profiles.size})") {
                if (settings.profiles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        settings.profiles.forEachIndexed { idx, p ->
                            if (idx > 0) HorizontalDivider(color = hc.subtleDivider)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                StatusPill(
                                    text = ":${p.port}",
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton({
                                    repo.update { s -> s.copy(profiles = s.profiles.filter { it.name != p.name }) }
                                }) { Icon(Icons.Rounded.Close, contentDescription = "삭제", modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HermesTextField(newName, { newName = it }, label = "이름", modifier = Modifier.weight(1f))
                    HermesTextField(
                        newPort,
                        { newPort = it.filter(Char::isDigit) },
                        label = "포트",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(0.6f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val port = newPort.toIntOrNull()
                            if (newName.isBlank() || port == null) { error = "이름과 포트를 입력하세요"; return@OutlinedButton }
                            error = null
                            repo.update { s ->
                                s.copy(profiles = s.profiles.filter { it.name != newName.trim() } + ProfileEntry(newName.trim(), port))
                            }
                            newName = ""; newPort = ""
                        },
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("추가")
                    }
                    OutlinedButton(
                        onClick = {
                            discovering = true; error = null; message = null
                            scope.launch {
                                val scan = container.profileDiscovery.scan(settings.host, settings.apiKey)
                                val err = scan.errorMessage
                                if (err != null) error = err
                                else {
                                    repo.update { it.copy(profiles = scan.profiles) }
                                    message = "${scan.profiles.size}개 프로필을 찾았습니다."
                                }
                                discovering = false
                            }
                        },
                        enabled = !discovering && settings.host.isNotBlank(),
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (discovering) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Rounded.Search, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("자동 검색")
                        }
                    }
                }
            }

            SectionCard("Bridge (고급)") {
                HermesTextField(
                    value = settings.bridgeUrl,
                    onValueChange = { v -> repo.update { it.copy(bridgeUrl = v) } },
                    label = "Bridge URL",
                    placeholder = "비우면 호스트:8765",
                    keyboardType = KeyboardType.Uri,
                )
                HermesTextField(
                    value = settings.bridgeToken,
                    onValueChange = { v -> repo.update { it.copy(bridgeToken = v) } },
                    label = "Bridge 토큰",
                    secret = true,
                )
            }

            SectionCard("앱 정보") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("앱 버전", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(versionName, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { uriHandler.openUri(PRIVACY_URL) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("개인정보 처리방침")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
            }

            OutlinedButton(
                onClick = onRerunOnboarding,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("온보딩 다시 보기")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
