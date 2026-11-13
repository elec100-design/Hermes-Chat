package com.hermes.chatandroid.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.data.Settings
import com.hermes.chatandroid.ui.bots.checkBotServer
import com.hermes.chatandroid.ui.components.ErrorBanner
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

    fun saveBot() = repo.update { it.copy(botHost = botUrl.trim(), botToken = botToken.trim()) }
    fun saveGateway() = repo.update { it.copy(host = host.trim(), apiKey = apiKey.trim(), profiles = profiles) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        when (step) {
            0 -> {
                Text("HermesChat", style = MaterialTheme.typography.displaySmall)
                Text(
                    "맥미니의 Hermes 봇들과 대화하는 메신저입니다. " +
                        "Tailscale 사설망으로 연결하며, 데이터는 사설망 밖으로 나가지 않습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button({ step = 1 }, Modifier.fillMaxWidth()) { Text("시작하기") }
                OutlinedButton(onDemo, Modifier.fillMaxWidth()) { Text("데모 모드로 둘러보기") }
                Text(
                    "서버 없이 샘플 데이터로 모든 화면을 체험할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            1 -> {
                Text("Hermes Bot 서버", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "봇 목록과 대화를 제공하는 서버입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    botUrl, { botUrl = it; botOk = false }, Modifier.fillMaxWidth(),
                    label = { Text("서버 주소") }, placeholder = { Text(DEFAULT_BOT_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    botToken, { botToken = it; botOk = false }, Modifier.fillMaxWidth(),
                    label = { Text("토큰 (서버에 설정된 경우만)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { ErrorBanner(it) }
                info?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("연결 확인")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ step = 0 }) { Text("이전") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            saveBot()
                            if (host.isBlank()) host = Settings.hostOnly(botUrl)
                            error = null; info = null
                            step = 2
                        },
                        enabled = botUrl.isNotBlank(),
                    ) { Text(if (botOk) "다음" else "연결 확인 없이 다음") }
                }
            }
            2 -> {
                Text("게이트웨이 (지난 세션 보기용)", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "봇 서버와 별개로, 각 봇의 예전 게이트웨이 세션을 보려면 게이트웨이 호스트와 API 키를 입력하세요. 건너뛰어도 됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    host, { host = it }, Modifier.fillMaxWidth(),
                    label = { Text("호스트") }, placeholder = { Text("100.83.59.60") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    apiKey, { apiKey = it }, Modifier.fillMaxWidth(),
                    label = { Text("API 키") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { ErrorBanner(it) }
                info?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (profiles.isNotEmpty()) {
                    Text(
                        "프로필: " + profiles.joinToString { "${it.name}(${it.port})" },
                        style = MaterialTheme.typography.bodySmall,
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("프로필 자동 검색")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ step = 1; error = null; info = null }) { Text("이전") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { saveGateway(); step = 3 },
                        enabled = host.isNotBlank() && profiles.isNotEmpty(),
                    ) { Text("다음") }
                }
                TextButton({ step = 3 }, Modifier.fillMaxWidth()) { Text("건너뛰기") }
            }
            else -> {
                Text("준비 완료", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "대화 탭에서 봇을 눌러 대화를 시작하세요. 서버 설정은 더보기 → 설정에서 언제든 바꿀 수 있습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button({ onFinished() }, Modifier.fillMaxWidth()) { Text("시작") }
            }
        }
    }
}
