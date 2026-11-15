package com.hermes.chatandroid.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.KanbanAction
import com.hermes.chatandroid.data.KanbanBoard
import com.hermes.chatandroid.data.KanbanBoardSummary
import com.hermes.chatandroid.data.KanbanCreateMode
import com.hermes.chatandroid.data.KanbanStatus
import com.hermes.chatandroid.data.KanbanTask
import com.hermes.chatandroid.data.KanbanTaskDetail
import com.hermes.chatandroid.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bridge 오류 → 안내 문구. 401 은 토큰 위치를 알려준다. */
fun bridgeErrorText(e: Throwable): String = when {
    e is ApiException && e.code == 401 -> "Bridge 인증 실패(401): 더보기 > 설정에서 Bridge 토큰을 확인하세요."
    e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
        "Bridge(:8765)에 연결할 수 없습니다. Tailscale 과 맥미니 Bridge 상태를 확인하세요."
    else -> e.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"
}

data class KanbanState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val boards: List<KanbanBoardSummary> = emptyList(),
    val selected: String? = null,
    val board: KanbanBoard? = null,
    /** null = 전체(진행 중인 것 위주로 상태별 묶음). */
    val filter: KanbanStatus? = null,
    val profiles: List<String> = emptyList(),
    val busy: Boolean = false,
    val detail: KanbanTaskDetail? = null,
    val detailLoading: Boolean = false,
    val message: String? = null,
) {
    val boardName: String get() = boards.firstOrNull { it.board == selected }?.name ?: board?.name ?: "칸반"
    fun count(s: KanbanStatus): Int = board?.tasks?.count { it.status == s } ?: 0
}

class KanbanViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(KanbanState())
    val state: StateFlow<KanbanState> = _state.asStateFlow()

    init { refresh(initial = true) }

    private inline fun guarded(crossinline onError: (String) -> Unit = { msg -> _state.update { it.copy(error = msg) } }, crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(bridgeErrorText(e)) }
        }
    }

    fun refresh(initial: Boolean = false) {
        val api = container.bridgeApi() ?: run {
            _state.update { it.copy(loading = false, error = "Bridge 주소가 없습니다. 더보기 > 설정에서 호스트를 입력하세요.") }
            return
        }
        _state.update { if (initial) it.copy(loading = true) else it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val boards = api.fetchKanbanBoards()
                val slug = _state.value.selected?.takeIf { s -> boards.any { it.board == s } }
                    ?: boards.maxByOrNull { it.activeCount - it.counts.getOrDefault("done", 0) }?.board
                val board = slug?.let { api.fetchKanbanBoard(it) }
                _state.update { it.copy(boards = boards, selected = slug, board = board, error = null) }
                if (_state.value.profiles.isEmpty()) {
                    runCatching { api.fetchProfiles().map { p -> p.name } }.getOrNull()?.let { names ->
                        _state.update { it.copy(profiles = names) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = bridgeErrorText(e)) } }
            finally { _state.update { it.copy(loading = false, refreshing = false) } }
        }
    }

    fun selectBoard(slug: String) {
        if (slug == _state.value.selected) return
        _state.update { it.copy(selected = slug, board = null, loading = true, filter = null) }
        refresh()
    }

    fun setFilter(f: KanbanStatus?) = _state.update { it.copy(filter = f) }

    private fun reloadBoard() {
        val api = container.bridgeApi() ?: return
        val slug = _state.value.selected ?: return
        guarded {
            val b = api.fetchKanbanBoard(slug)
            val boards = runCatching { api.fetchKanbanBoards() }.getOrNull()
            _state.update { it.copy(board = b, boards = boards ?: it.boards) }
        }
    }

    fun openTask(task: KanbanTask) {
        val api = container.bridgeApi() ?: return
        val slug = _state.value.selected ?: return
        _state.update { it.copy(detail = KanbanTaskDetail(task, emptyList(), emptyList()), detailLoading = true) }
        guarded(onError = { msg -> _state.update { it.copy(detailLoading = false, message = msg) } }) {
            val d = api.fetchKanbanTask(slug, task.id)
            _state.update { if (it.detail?.task?.id == task.id) it.copy(detail = d, detailLoading = false) else it }
        }
    }

    fun closeTask() = _state.update { it.copy(detail = null, detailLoading = false) }

    fun perform(task: KanbanTask, action: KanbanAction, reason: String?) =
        mutate("‘${task.title}’ ${action.label} 완료", closeDetail = action == KanbanAction.ARCHIVE) { api, slug ->
            api.kanbanAction(slug, task.id, action.key, reason)
        }

    fun comment(task: KanbanTask, text: String) {
        val api = container.bridgeApi() ?: return
        val slug = _state.value.selected ?: return
        _state.update { it.copy(busy = true) }
        guarded(onError = { msg -> _state.update { it.copy(busy = false, message = msg) } }) {
            api.kanbanAction(slug, task.id, "comment", text)
            val d = api.fetchKanbanTask(slug, task.id)
            _state.update { it.copy(busy = false, detail = d) }
        }
    }

    fun createTask(title: String, detail: String, assignee: String, mode: KanbanCreateMode) =
        mutate("작업을 추가했습니다") { api, slug -> api.createKanbanTask(slug, title, detail, assignee, mode) }

    fun createBoard(name: String) {
        val api = container.bridgeApi() ?: return
        _state.update { it.copy(busy = true) }
        guarded(onError = { msg -> _state.update { it.copy(busy = false, message = msg) } }) {
            api.createKanbanBoard(name)
            val boards = api.fetchKanbanBoards()
            val slug = boards.firstOrNull { it.name == name }?.board ?: _state.value.selected
            _state.update { it.copy(busy = false, boards = boards, selected = slug, board = null, message = "‘$name’ 보드를 만들었습니다") }
            refresh()
        }
    }

    private fun mutate(done: String, closeDetail: Boolean = false, block: suspend (com.hermes.chatandroid.net.BridgeApi, String) -> Unit) {
        val api = container.bridgeApi() ?: return
        val slug = _state.value.selected ?: return
        _state.update { it.copy(busy = true) }
        guarded(onError = { msg -> _state.update { it.copy(busy = false, message = msg) } }) {
            block(api, slug)
            val openId = _state.value.detail?.task?.id
            val d = if (!closeDetail && openId != null) runCatching { api.fetchKanbanTask(slug, openId) }.getOrNull() else null
            _state.update { it.copy(busy = false, message = done, detail = if (closeDetail) null else d ?: it.detail) }
            reloadBoard()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
