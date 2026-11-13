package com.hermes.chatandroid.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.HermesCard
import com.hermes.chatandroid.ui.components.StatusPill
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 프로필 카드 그리드. 카드 탭 → 해당 프로필 선택 + 새 세션 생성 → 채팅. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBoardScreen(
    container: AppContainer,
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val health = remember { mutableStateMapOf<String, Boolean>() }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hc = MaterialTheme.hermesColors

    suspend fun refresh() {
        refreshing = true
        val bridge = container.bridgeApi()
        if (bridge != null && !repo.current.demoMode) {
            try {
                val list = bridge.fetchProfiles().filter { it.apiEnabled }.map { ProfileEntry(it.name, it.port) }
                if (list.isNotEmpty()) repo.update { it.copy(profiles = list) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) { /* Bridge 미도달 → 폴백 */ }
        }
        if (!repo.current.demoMode && repo.current.profiles.isEmpty() && repo.current.cleanHost.isNotEmpty()) {
            val found = container.profileDiscovery.scan(repo.current.host, repo.current.apiKey).profiles
            if (found.isNotEmpty()) repo.update { it.copy(profiles = found) }
        }
        val ps = repo.current.visibleProfiles
        coroutineScope {
            ps.map { p ->
                async { health[p.name] = container.hermesApi(p)?.health() ?: false }
            }.awaitAll()
        }
        refreshing = false
    }

    LaunchedEffect(settings.host, settings.bridgeUrl) { refresh() }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text("프로필", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        error?.let { ErrorBanner(it, onRetry = { error = null }) }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refresh() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (settings.visibleProfiles.isEmpty()) {
                EmptyState(
                    "프로필이 없습니다",
                    subtitle = "설정에서 호스트를 입력하고 자동 검색하세요. 당겨서 새로고침할 수 있습니다.",
                    icon = Icons.Rounded.Hub,
                )
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(155.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(settings.visibleProfiles, key = { it.name }) { p ->
                        val selected = p.name == settings.selectedProfile
                        HermesCard(
                            onClick = if (creating == null) {
                                {
                                    creating = p.name; error = null
                                    scope.launch {
                                        try {
                                            repo.update { it.copy(selectedProfile = p.name) }
                                            val api = container.hermesApi(p)
                                                ?: throw IllegalStateException("호스트가 설정되지 않았습니다")
                                            val s = api.createSession()
                                            onOpenSession(s.id)
                                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = "세션 생성 실패 (${p.name}): ${e.message}"
                                        } finally {
                                            creating = null
                                        }
                                    }
                                }
                            } else null,
                            borderColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else hc.cardBorder,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val color = when (health[p.name]) {
                                        true -> hc.online
                                        false -> MaterialTheme.colorScheme.error
                                        null -> hc.offline
                                    }
                                    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                                    Text(
                                        p.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    ":${p.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    if (creating == p.name) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else if (selected) {
                                        StatusPill(
                                            text = "선택됨",
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
