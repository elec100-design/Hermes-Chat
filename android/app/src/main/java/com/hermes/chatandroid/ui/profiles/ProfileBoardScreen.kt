package com.hermes.chatandroid.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
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

    suspend fun refresh() {
        refreshing = true
        // 1) Bridge 에서 목록 갱신 (실패해도 조용히 기존 목록 유지)
        val bridge = container.bridgeApi()
        if (bridge != null && !repo.current.demoMode) {
            try {
                val list = bridge.fetchProfiles().filter { it.apiEnabled }.map { ProfileEntry(it.name, it.port) }
                if (list.isNotEmpty()) repo.update { it.copy(profiles = list) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) { /* Bridge 미도달 → 폴백 */ }
        }
        // 2) 여전히 비었으면 포트 스캔
        if (!repo.current.demoMode && repo.current.profiles.isEmpty() && repo.current.cleanHost.isNotEmpty()) {
            val found = container.profileDiscovery.scan(repo.current.host, repo.current.apiKey)
            if (found.isNotEmpty()) repo.update { it.copy(profiles = found) }
        }
        // 3) 헬스 점
        val ps = repo.current.visibleProfiles
        coroutineScope {
            ps.map { p ->
                async { health[p.name] = container.hermesApi(p)?.health() ?: false }
            }.awaitAll()
        }
        refreshing = false
    }

    LaunchedEffect(settings.host, settings.bridgeUrl) { refresh() }

    Column(modifier.fillMaxSize()) {
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
                )
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(settings.visibleProfiles, key = { it.name }) { p ->
                        Card(
                            Modifier.fillMaxWidth().clickable(enabled = creating == null) {
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
                            },
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val color = when (health[p.name]) {
                                        true -> Color(0xFF2E9E5B)
                                        false -> Color(0xFFC0392B)
                                        null -> Color.Gray
                                    }
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                                    Text(
                                        "  ${p.name}", style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    ":${p.port}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (creating == p.name) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else if (p.name == settings.selectedProfile) {
                                    Text("선택됨", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
