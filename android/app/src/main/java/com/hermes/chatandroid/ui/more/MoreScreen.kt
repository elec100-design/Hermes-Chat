package com.hermes.chatandroid.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.IconBadge
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.components.readableWidth
import com.hermes.chatandroid.ui.theme.hermesColors

/** 더보기 탭: 연결 요약, 설정, 사용량, 메타 글라스. (대시보드·워크스페이스는 탭으로 나갔다.) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenGlasses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val hc = MaterialTheme.hermesColors

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("더보기", style = MaterialTheme.typography.headlineSmall) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxHeight().readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 현재 연결 상태 요약 카드
            HermesCard(
                onClick = onOpenSettings,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(
                        icon = Icons.Rounded.Dns,
                        tint = if (settings.botConfigured) hc.online else hc.warning,
                        size = 44.dp,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Hermes Bot 서버", style = MaterialTheme.typography.titleSmall)
                            StatusPill(
                                text = when {
                                    settings.demoMode -> "데모 모드"
                                    settings.isGuestMode -> "게스트"
                                    settings.botConfigured -> "설정됨"
                                    else -> "미설정"
                                },
                                containerColor = when {
                                    settings.demoMode -> MaterialTheme.colorScheme.primaryContainer
                                    settings.isGuestMode -> MaterialTheme.colorScheme.secondaryContainer
                                    settings.botConfigured -> hc.online.copy(alpha = 0.14f)
                                    else -> hc.warningContainer
                                },
                                contentColor = when {
                                    settings.demoMode -> MaterialTheme.colorScheme.primary
                                    settings.isGuestMode -> MaterialTheme.colorScheme.onSecondaryContainer
                                    settings.botConfigured -> hc.online
                                    else -> hc.onWarningContainer
                                },
                            )
                        }
                        Text(
                            text = when {
                                settings.demoMode -> "샘플 데이터로 동작 중입니다"
                                settings.isGuestMode -> "${settings.botDisplayName.ifBlank { "게스트" }} · ${settings.botBaseUrl ?: ""}"
                                settings.botBaseUrl != null -> settings.botBaseUrl!!
                                else -> "탭해서 서버 주소와 토큰을 설정하세요"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (settings.botBaseUrl != null && !settings.demoMode) FontFamily.Monospace else FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                "도구 및 관리",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            MoreActionCard(
                icon = Icons.Rounded.Settings,
                tint = MaterialTheme.colorScheme.primary,
                title = "설정",
                subtitle = if (settings.isGuestMode) "게스트 계정, 연결 정보, 로그아웃" else "서버 연결, 토큰, 게이트웨이 프로필",
                onClick = onOpenSettings,
            )
            if (!settings.isGuestMode) {
                MoreActionCard(
                    icon = Icons.Rounded.DataUsage,
                    tint = Color(0xFF4F86A8),
                    title = "사용량",
                    subtitle = "Claude · Grok 등 에이전트 계정별 한도와 재설정 시각",
                    onClick = onOpenUsage,
                )
                MoreActionCard(
                    icon = Icons.Rounded.Visibility,
                    tint = Color(0xFF4C8C5F),
                    title = "메타 글라스 · Live",
                    subtitle = if (settings.demoMode) "데모 모드에서는 사용할 수 없습니다" else "글라스 연결, 시야 사진 방식, 촬영 크기",
                    onClick = onOpenGlasses.takeIf { !settings.demoMode },
                )
            }

        }
    }
}

@Composable
private fun MoreActionCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    trailingBadge: String? = null,
    onClick: (() -> Unit)?,
) {
    HermesCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(icon = icon, tint = tint, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (onClick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailingBadge != null) {
                StatusPill(
                    text = trailingBadge,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (onClick != null) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
