package com.hermes.chatandroid.ui.live

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.glasses.PhotoSize
import com.hermes.chatandroid.live.LivePhase
import com.hermes.chatandroid.live.LiveTarget
import com.hermes.chatandroid.live.VisionMode
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState

/**
 * Live 전체 화면 (T-187/T-188). [target] 은 이 화면을 연 봇 채팅. 채널이면 [members] 중 답할 봇을 고른다.
 * 화면을 떠나도 Live 는 포그라운드 서비스로 계속된다(알림의 종료로 끝냄).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    container: AppContainer,
    target: LiveTarget?,
    members: List<String> = emptyList(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = container.live
    val glasses = live.glasses
    val ui by live.ui.collectAsStateWithLifecycle()
    val gs by glasses.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var mention by remember(target) { mutableStateOf(target?.mentionSlug ?: members.firstOrNull()) }
    val effectiveTarget = (ui.target ?: target)?.let { if (members.isNotEmpty()) it.copy(mentionSlug = mention) else it }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val btPerms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.BLUETOOTH)
    var btGranted by remember { mutableStateOf(btPerms.all(::granted)) }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        btGranted = r.values.all { it }
        if (btGranted) glasses.initialize()
    }
    val camLauncher = rememberLauncherForActivityResult(Wearables.RequestPermissionContract()) { r ->
        glasses.onPermissionResult(r.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) effectiveTarget?.let(live::start)
    }

    LaunchedEffect(btGranted) { if (btGranted) glasses.initialize() }
    // Meta AI 앱에서 등록하고 돌아오면 권한 상태를 다시 읽는다.
    LaunchedEffect(gs.registration) { if (gs.registered) glasses.refreshPermission() }

    fun startLive() {
        val needs = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) effectiveTarget?.let(live::start)
        else micLauncher.launch(needs.toTypedArray())
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Live · ${effectiveTarget?.title ?: ""}", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassesCard(
                btGranted = btGranted,
                state = gs,
                onGrantBt = { btLauncher.launch(btPerms) },
                onRegister = { activity?.let(glasses::register) },
                onRequestCamera = { camLauncher.launch(Permission.CAMERA) },
                onConnect = glasses::connect,
                onUpdate = { activity?.let(glasses::openUpdate) },
                onDisconnect = glasses::disconnect,
                onDismissError = glasses::clearError,
            )

            if (members.size > 1 && !live.active) {
                Text("답할 봇", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    members.forEach { slug ->
                        FilterChip(selected = mention == slug, onClick = { mention = slug }, label = { Text("@$slug") })
                    }
                }
            }

            Text("시야 사진 첨부", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VisionMode.entries.forEach { m ->
                    FilterChip(selected = ui.vision == m, onClick = { live.setVision(m) }, label = { Text(m.label) })
                }
            }
            Text("사진 크기 (클수록 글이 잘 보이지만 전송이 느려요)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhotoSize.entries.forEach { sz ->
                    FilterChip(selected = ui.photoSize == sz, onClick = { live.setPhotoSize(sz) }, label = { Text(sz.label) })
                }
            }
            Text(
                "\"사진 찍어\", \"이거 보여\", \"읽어줘\"처럼 말하면 그때 고화질로 찍어 질문과 함께 보내요. 사진만 따로 보내지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PhaseOrb(ui.phase, ui.bluetooth, live.active)

            if (ui.partial.isNotBlank() || ui.lastUser.isNotBlank()) {
                Text(
                    ui.partial.ifBlank { ui.lastUser },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ui.partial.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (ui.reply.isNotBlank()) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(ui.reply, Modifier.padding(12.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
                }
            }
            ui.photo?.let { bmp ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = "글라스 사진",
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        (if (ui.photoPending) "다음 질문과 함께 보냄 · " else "") + (ui.photoInfo ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ui.error?.let { e ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = live::clearError) { Icon(Icons.Rounded.Close, contentDescription = "닫기") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!live.active) {
                    Button(onClick = ::startLive, enabled = effectiveTarget != null, modifier = Modifier.weight(1f)) { Text("Live 시작") }
                } else {
                    Button(onClick = live::primary, modifier = Modifier.weight(1f), enabled = ui.phase != LivePhase.THINKING) {
                        Text(
                            when (ui.phase) {
                                LivePhase.LISTENING -> "바로 보내기"
                                LivePhase.SPEAKING -> "끼어들기"
                                LivePhase.WAITING -> "다시 듣기"
                                else -> "생각 중…"
                            },
                        )
                    }
                    OutlinedButton(onClick = live::stop) { Text("종료") }
                }
                FilledTonalButton(
                    onClick = live::takePhotoNow,
                    enabled = gs.cameraReady && live.active,
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("사진")
                }
            }
            Text(
                "글라스·이어폰 탭: 듣는 중 = 바로 보내기, 답하는 중 = 끼어들기, 대기 = 다시 듣기. 대화는 이 봇 채팅에 그대로 기록돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GlassesCard(
    btGranted: Boolean,
    state: com.hermes.chatandroid.glasses.GlassesState,
    onGrantBt: () -> Unit,
    onRegister: () -> Unit,
    onRequestCamera: () -> Unit,
    onConnect: () -> Unit,
    onUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("메타 글라스 카메라", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val (status, action) = when {
                !btGranted -> "블루투스 권한이 필요해요" to ("권한 허용" to onGrantBt)
                !state.initialized -> "준비 중…" to null
                state.registration == RegistrationState.REGISTERING -> "Meta AI 앱에서 연결 중…" to null
                !state.registered -> "Meta AI 앱에 이 앱을 연결해야 해요 (개발자 모드)" to ("Meta AI에서 연결" to onRegister)
                state.cameraPermission != true -> "글라스 카메라 권한이 필요해요" to ("카메라 권한 요청" to onRequestCamera)
                state.update != null && !state.cameraReady ->
                    "글라스 업데이트가 필요해요 — Meta AI 앱에서 업데이트 후 돌아와 '카메라 켜기'" to ("업데이트 열기" to onUpdate)
                state.transfer != null -> "사진 받는 중… ${(state.transfer * 100).toInt()}%" to null
                state.cameraReady -> "연결됨 · \"사진 찍어\", \"이거 보여\"라고 하면 찍어요" to ("끄기" to onDisconnect)
                state.session != null -> "글라스 연결 중… (${state.session})" to ("끄기" to onDisconnect)
                else -> "글라스 ${state.deviceCount}대 발견 · 연결 안 됨" to ("글라스 연결" to onConnect)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                action?.let { (label, fn) -> OutlinedButton(onClick = fn) { Text(label) } }
                if (state.update != null && !state.cameraReady && state.cameraPermission == true) {
                    OutlinedButton(onClick = onConnect) { Text("업데이트 후 다시 연결") }
                }
            }
            state.error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    IconButton(onClick = onDismissError) { Icon(Icons.Rounded.Close, contentDescription = "오류 닫기") }
                }
            }
        }
    }
}

@Composable
private fun PhaseOrb(phase: LivePhase, bluetooth: Boolean, active: Boolean) {
    val (label, color) = when (phase) {
        LivePhase.LISTENING -> "듣는 중" to MaterialTheme.colorScheme.primary
        LivePhase.THINKING -> "생각 중" to MaterialTheme.colorScheme.tertiary
        LivePhase.SPEAKING -> "답하는 중" to MaterialTheme.colorScheme.secondary
        LivePhase.WAITING -> "대기 — 탭하면 다시 듣기" to MaterialTheme.colorScheme.outline
        LivePhase.IDLE -> "꺼짐" to MaterialTheme.colorScheme.outlineVariant
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f))
                .semantics { contentDescription = "Live 상태: $label" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(color))
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
        if (active) {
            Text(
                if (bluetooth) "블루투스 헤드셋(글라스)으로 듣고 말해요" else "폰 마이크·스피커 사용 중 (블루투스 없음)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
