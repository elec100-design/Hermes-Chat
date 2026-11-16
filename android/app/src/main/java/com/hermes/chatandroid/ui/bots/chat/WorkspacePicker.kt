package com.hermes.chatandroid.ui.bots.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.chatandroid.data.Workspace
import com.hermes.chatandroid.ui.components.ComposerButtonSize
import com.hermes.chatandroid.ui.components.ComposerIconButton
import com.hermes.chatandroid.ui.theme.hermesColors

/** 경로의 마지막 폴더 이름(버튼 표시용). */
fun workdirLabel(workdir: String?): String? =
    workdir?.trim()?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

/** 입력 카드의 작업 폴더 버튼. 넓은 화면에서는 고른 폴더 이름까지 보여 준다. */
@Composable
fun WorkspaceButton(workdir: String?, showLabel: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = workdirLabel(workdir)
    if (label == null || !showLabel) {
        ComposerIconButton(
            icon = if (label == null) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            contentDescription = if (label == null) "작업 폴더 선택" else "작업 폴더: $label — 바꾸기",
            onClick = onClick,
            modifier = modifier,
            tint = if (label == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        return
    }
    Surface(
        modifier = modifier.height(ComposerButtonSize).clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.hermesColors.cardBorder),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = "작업 폴더", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
    }
}

/**
 * 이 채팅의 봇(또는 채널)이 일할 작업 폴더 고르기. 목록은 더보기 > 워크스페이스에 등록한 위치.
 * [workspaces] null = 불러오는 중.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacePickerSheet(
    workspaces: List<Workspace>?,
    current: String?,
    onPick: (path: String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("작업 폴더", style = MaterialTheme.typography.titleLarge)
                Text(
                    "이 대화의 봇이 파일을 읽고 고칠 위치예요. 다음 메시지부터 적용됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PickerRow(Icons.Rounded.FolderOff, "지정 안 함", "봇 기본 위치에서 일해요", selected = current.isNullOrBlank()) { onPick("") }
            when {
                workspaces == null -> Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                workspaces.isEmpty() -> Text(
                    "등록된 워크스페이스가 없습니다. 아래에서 먼저 폴더나 GitHub 리포를 등록하세요.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> workspaces.forEach { ws ->
                    PickerRow(
                        icon = if (ws.isRepo) Icons.Rounded.Code else Icons.Rounded.Folder,
                        title = ws.label,
                        subtitle = ws.path,
                        selected = current == ws.path,
                        monoSubtitle = true,
                    ) { onPick(ws.path) }
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("워크스페이스 관리")
            }
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    monoSubtitle: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monoSubtitle) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "선택됨", tint = MaterialTheme.colorScheme.primary)
    }
}
