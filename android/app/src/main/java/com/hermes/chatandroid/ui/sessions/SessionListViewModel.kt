package com.hermes.chatandroid.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

class SessionListViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SessionListState())
    val state: StateFlow<SessionListState> = _state.asStateFlow()

    /** 프로필 전환/새로고침마다 증가. 늦게 도착한 이전 요청 응답은 버린다. */
    private var generation = 0
    private var profile: ProfileEntry? = null
    private val pageSize = 50

    fun load(p: ProfileEntry?) {
        profile = p
        val gen = ++generation
        _state.value = SessionListState(loading = true)
        val api = container.hermesApi(p)
        if (api == null) {
            _state.value = SessionListState(error = "호스트/프로필이 설정되지 않았습니다")
            return
        }
        viewModelScope.launch {
            try {
                val page = api.fetchSessions(pageSize, 0)
                if (gen != generation) return@launch
                _state.value = SessionListState(sessions = page.sessions, hasMore = page.hasMore)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.value = SessionListState(error = e.message ?: "세션을 불러오지 못했습니다")
            }
        }
    }

    fun refresh() = load(profile)

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loadingMore || s.loading) return
        val gen = generation
        val api = container.hermesApi(profile) ?: return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = api.fetchSessions(pageSize, s.sessions.size)
                if (gen != generation) return@launch
                _state.update { cur ->
                    val known = cur.sessions.map { it.id }.toSet()
                    cur.copy(
                        sessions = cur.sessions + page.sessions.filter { it.id !in known },
                        hasMore = page.hasMore && page.sessions.isNotEmpty(),
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(loadingMore = false, error = e.message ?: "추가 로드 실패") }
            }
        }
    }

    fun rename(id: String, title: String) = mutate { api ->
        api.renameSession(id, title)
        _state.update { cur -> cur.copy(sessions = cur.sessions.map { if (it.id == id) it.copy(title = title) else it }) }
    }

    fun delete(id: String) = mutate { api ->
        api.deleteSession(id)
        _state.update { cur -> cur.copy(sessions = cur.sessions.filter { it.id != id }) }
    }

    fun create(onCreated: (String) -> Unit) = mutate { api ->
        onCreated(api.createSession().id)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun mutate(block: suspend (com.hermes.chatandroid.net.HermesApi) -> Unit) {
        val gen = generation
        val api = container.hermesApi(profile) ?: return
        viewModelScope.launch {
            try {
                block(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) _state.update { it.copy(error = e.message ?: "요청 실패") }
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionListViewModel(container) as T
    }
}
