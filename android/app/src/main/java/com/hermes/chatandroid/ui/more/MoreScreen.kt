package com.hermes.chatandroid.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** 더보기 탭: 설정, 워크스페이스, 그리고 2차 예정 기능 자리. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("더보기") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MoreRow(Icons.Filled.Settings, "설정", "서버 연결, 토큰, 게이트웨이", onOpenSettings)
            HorizontalDivider()
            MoreRow(Icons.Filled.FolderOpen, "워크스페이스", "봇이 일할 폴더와 GitHub 리포", onOpenWorkspaces)
            HorizontalDivider()
            MoreRow(null, "칸반 · 크론 · 대시보드", "2차 예정", null)
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector?, title: String, subtitle: String, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        colors = if (onClick == null) ListItemDefaults.colors(
            headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) else ListItemDefaults.colors(),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
