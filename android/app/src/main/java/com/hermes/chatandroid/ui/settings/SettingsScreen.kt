package com.hermes.chatandroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.ui.bots.botErrorText
import com.hermes.chatandroid.ui.bots.checkBotServer
import com.hermes.chatandroid.ui.components.ErrorBanner
import kotlinx.coroutines.launch

private const val PRIVACY_URL = "https://hermeschat.app/privacy"

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Uri,
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

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

    var newName by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var botTesting by remember { mutableStateOf(false) }
    var botResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            Text("설정", style = MaterialTheme.typography.headlineSmall)
        }
        if (settings.demoMode) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("데모 모드", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "샘플 데이터로 앱을 체험 중입니다. 서버에는 연결하지 않습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onExitDemo, Modifier.fillMaxWidth()) { Text("데모 모드 종료") }
                }
            }
        }
        error?.let { ErrorBanner(it) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Section("Hermes Bot 서버") {
            Field("서버 주소", settings.botHost, { v -> repo.update { it.copy(botHost = v) } },
                placeholder = "http://100.83.59.60:8900", keyboard = KeyboardType.Uri)
            Field("토큰 (서버에 설정된 경우만)", settings.botToken, { v -> repo.update { it.copy(botToken = v) } }, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                ) {
                    if (botTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("연결 테스트")
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
                ) { Text("봇 프로필 재검색") }
            }
            botResult?.let { (ok, msg) ->
                Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }

        Section("게이트웨이 (지난 세션 보기용)") {
            Field("호스트", settings.host, { v -> repo.update { it.copy(host = v) } }, placeholder = "http://100.83.59.60")
            Field("API 키", settings.apiKey, { v -> repo.update { it.copy(apiKey = v) } }, secret = true)
        }

        Section("프로필") {
            settings.profiles.forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.name}  :${p.port}", Modifier.weight(1f))
                    IconButton({
                        repo.update { s -> s.copy(profiles = s.profiles.filter { it.name != p.name }) }
                    }) { Icon(Icons.Filled.Close, contentDescription = "삭제") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("이름") }, singleLine = true)
                OutlinedTextField(
                    newPort, { newPort = it.filter(Char::isDigit) }, Modifier.weight(0.6f),
                    label = { Text("포트") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                ) { Text("추가") }
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
                ) {
                    if (discovering) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("자동 검색")
                }
            }
        }

        Section("Bridge (고급)") {
            Field("Bridge URL", settings.bridgeUrl, { v -> repo.update { it.copy(bridgeUrl = v) } },
                placeholder = "비우면 호스트:8765")
            Field("Bridge 토큰", settings.bridgeToken, { v -> repo.update { it.copy(bridgeToken = v) } }, secret = true)
        }

        Section("정보") {
            OutlinedButton({ uriHandler.openUri(PRIVACY_URL) }, Modifier.fillMaxWidth()) { Text("개인정보 처리방침") }
            Text("버전 $versionName", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Button(onRerunOnboarding, Modifier.fillMaxWidth()) { Text("온보딩 다시 보기") }
    }
}
