package com.hermes.chatandroid.ui.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.glasses.PhotoSize
import com.hermes.chatandroid.live.VisionMode
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.readableWidth
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState

/** 더보기 > 메타 글라스 · Live 화면. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesSettingsScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("메타 글라스 · Live", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
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
            Text(
                "채팅 입력창의 Live 버튼으로 핸즈프리 대화를 시작합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HermesCard { GlassesSettings(container) }
        }
    }
}

/**
 * 글라스 연결 + Live 사진 옵션 (T-187/T-188). 글라스 연결(권한 → Meta AI 등록 → 카메라 권한 → 세션)과
 * Live 사진 옵션. Live 자체는 봇 채팅 입력창의 Live 버튼으로 켠다.
 */
@Composable
fun GlassesSettings(container: AppContainer) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val glasses = container.live.glasses
    val state by glasses.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    val btPerms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.BLUETOOTH)
    var btGranted by remember {
        mutableStateOf(btPerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        btGranted = r.values.all { it }
    }
    val camLauncher = rememberLauncherForActivityResult(Wearables.RequestPermissionContract()) { r ->
        glasses.onPermissionResult(r.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted)
    }
    LaunchedEffect(btGranted) { if (btGranted) glasses.initialize() }
    // Meta AI 앱에서 등록하고 돌아오면 권한 상태를 다시 읽는다.
    LaunchedEffect(state.registration) { if (state.registered) glasses.refreshPermission() }

    val (status, action) = when {
        !btGranted -> "블루투스 권한이 필요해요" to ("권한 허용" to { btLauncher.launch(btPerms) })
        !state.initialized -> "준비 중…" to null
        state.registration == RegistrationState.REGISTERING -> "Meta AI 앱에서 연결 중…" to null
        !state.registered -> "Meta AI 앱에 이 앱을 연결해야 해요 (개발자 모드)" to ("Meta AI에서 연결" to { activity?.let(glasses::register); Unit })
        state.cameraPermission != true -> "글라스 카메라 권한이 필요해요" to ("카메라 권한 요청" to { camLauncher.launch(Permission.CAMERA) })
        state.update != null && !state.cameraReady ->
            "글라스 업데이트가 필요해요 — Meta AI 앱에서 업데이트 후 다시 연결하세요" to ("업데이트 열기" to { activity?.let(glasses::openUpdate); Unit })
        state.transfer != null -> "사진 받는 중… ${(state.transfer!! * 100).toInt()}%" to null
        state.cameraReady -> "연결됨 — Live 중 \"사진 찍어\", \"이거 보여\"라고 하면 찍어요" to ("끊기" to { glasses.disconnect() })
        state.session != null -> "글라스 연결 중… (${state.session})" to ("끊기" to { glasses.disconnect() })
        else -> "글라스 ${state.deviceCount}대 발견 · 연결 안 됨" to ("글라스 연결" to { glasses.connect() })
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            action?.let { (label, fn) -> OutlinedButton(onClick = fn, shape = CircleShape) { Text(label) } }
            if (state.update != null && !state.cameraReady && state.cameraPermission == true) {
                OutlinedButton(onClick = glasses::connect, shape = CircleShape) { Text("업데이트 후 다시 연결") }
            }
        }
        state.error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                IconButton(onClick = glasses::clearError) { Icon(Icons.Rounded.Close, contentDescription = "오류 닫기") }
            }
        }

        Text("시야 사진 첨부", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VisionMode.entries.forEach { m ->
                FilterChip(
                    selected = settings.glassesVision == m.name,
                    onClick = { repo.update { it.copy(glassesVision = m.name) } },
                    label = { Text(m.label) },
                )
            }
        }
        Text("촬영 크기", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhotoSize.entries.forEach { sz ->
                FilterChip(
                    selected = settings.glassesPhotoSize == sz.name,
                    onClick = { repo.update { it.copy(glassesPhotoSize = sz.name) } },
                    label = { Text(sz.label) },
                )
            }
        }
        Text(
            "봇이 줄이지 않고 그대로 보는 크기는 긴 변 1568px까지라, 사진은 그 크기에 맞춰 보내요. " +
                "'보통'이면 충분하고 '최고화질'은 글라스 전송만 느려집니다. " +
                "사진은 Live 중 말로 요청할 때만 찍어 질문과 함께 보내요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
