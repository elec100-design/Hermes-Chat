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
import com.hermes.chatandroid.ui.components.ErrorBanner
import kotlinx.coroutines.launch

/** 환영 → 서버 연결 → 완료. */
@Composable
fun OnboardingScreen(
    container: AppContainer,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = container.settingsRepository
    var step by rememberSaveable { mutableStateOf(0) }
    var host by rememberSaveable { mutableStateOf(repo.current.host.ifBlank { "http://100.83.59.60" }) }
    var apiKey by rememberSaveable { mutableStateOf(repo.current.apiKey) }
    var profiles by remember { mutableStateOf(repo.current.profiles) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() = repo.update { it.copy(host = host.trim(), apiKey = apiKey.trim(), profiles = profiles) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        when (step) {
            0 -> {
                Text("HermesChat", style = MaterialTheme.typography.displaySmall)
                Text(
                    "맥미니의 hermes-agent를 안드로이드에서 쓰는 클라이언트입니다. " +
                        "Tailscale 사설망으로 연결하며, 데이터는 사설망 밖으로 나가지 않습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button({ step = 1 }, Modifier.fillMaxWidth()) { Text("시작하기") }
            }
            1 -> {
                Text("서버 연결", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    host, { host = it }, Modifier.fillMaxWidth(),
                    label = { Text("호스트") }, placeholder = { Text("http://100.83.59.60") },
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
                            val found = container.profileDiscovery.scan(host, apiKey.trim())
                            if (found.isEmpty()) {
                                error = "프로필을 찾지 못했습니다. 호스트/API 키/네트워크(Tailscale)를 확인하세요."
                            } else {
                                profiles = found
                                info = "${found.size}개 프로필을 찾았습니다."
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
                OutlinedButton(
                    onClick = {
                        val p = profiles.firstOrNull()
                        if (p == null) { error = "먼저 프로필을 검색하세요."; return@OutlinedButton }
                        save()
                        busy = true; error = null; info = null
                        scope.launch {
                            val ok = container.hermesApi(p)?.health() ?: false
                            if (ok) info = "연결 성공 (${p.name})" else error = "헬스 체크 실패 (${p.name}:${p.port})"
                            busy = false
                        }
                    },
                    enabled = !busy && host.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("연결 확인") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ step = 0 }) { Text("이전") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { save(); step = 2 },
                        enabled = host.isNotBlank() && profiles.isNotEmpty(),
                    ) { Text("다음") }
                }
                TextButton({ save(); step = 2 }, Modifier.fillMaxWidth()) { Text("나중에 설정하기") }
            }
            else -> {
                Text("준비 완료", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (profiles.isEmpty()) "서버 설정은 설정 탭에서 언제든 바꿀 수 있습니다."
                    else "${profiles.size}개 프로필이 연결되었습니다. 보드에서 프로필을 눌러 대화를 시작하세요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button({ save(); onFinished() }, Modifier.fillMaxWidth()) { Text("시작") }
            }
        }
    }
}
