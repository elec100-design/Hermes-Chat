package com.hermes.chatandroid.ui.bots.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotPatch
import com.hermes.chatandroid.data.Session
import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.ui.bots.botErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 서버(approvals.py policy_of)가 이해하는 승인 정책: auto(기본) / ask. */
val ApprovalPolicies = listOf("auto" to "자동 실행", "ask" to "실행 전 승인 요청")

/** 편집 폼 값. 서버 값과 비교해 dirty/패치를 만든다. */
data class BotForm(
    val name: String = "",
    val avatar: String = "",
    val personaNote: String = "",
    val approvalPolicy: String = "auto",
    val readOnly: Boolean = false,
    val computerUse: Boolean = false,
    val workdir: String = "",
) {
    companion object {
        fun of(b: Bot) = BotForm(
            name = b.name.orEmpty(),
            avatar = b.avatar.orEmpty(),
            personaNote = b.personaNote.orEmpty(),
            approvalPolicy = b.approvalPolicy?.takeIf { it.isNotEmpty() } ?: "auto",
            readOnly = b.readOnly == true,
            computerUse = b.computerUse == true,
            workdir = b.workdir.orEmpty(),
        )
    }
}

data class BotProfileState(
    val bot: Bot? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val form: BotForm = BotForm(),
    val saved: BotForm = BotForm(),
    val saving: Boolean = false,
    val healthRefreshing: Boolean = false,
    val message: String? = null,
    // 세션
    val gatewayReady: Boolean = true,
    val sessions: List<Session> = emptyList(),
    val hasMore: Boolean = false,
    val sessionsLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val sessionsError: String? = null,
) {
    val dirty: Boolean get() = form != saved
}

class BotProfileViewModel(private val container: AppContainer, private val botId: Int) : ViewModel() {
    private val _state = MutableStateFlow(BotProfileState())
    val state: StateFlow<BotProfileState> = _state.asStateFlow()
    private val pageSize = 30
    private var gen = 0

    init { loadBot(); loadSessions() }

    private fun mergeBot(b: Bot, resetForm: Boolean = true) = _state.update {
        val f = BotForm.of(b)
        it.copy(bot = b, loading = false, error = null, saved = f, form = if (resetForm) f else it.form)
    }

    fun loadBot() {
        val api = container.botApi()
        if (api == null) { _state.update { it.copy(loading = false, error = "봇 서버가 설정되지 않았습니다") }; return }
        viewModelScope.launch {
            try {
                val b = api.fetchBots(activeOnly = false).firstOrNull { it.id == botId }
                if (b == null) _state.update { it.copy(loading = false, error = "봇을 찾을 수 없습니다") } else mergeBot(b)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = botErrorText(e)) } }
        }
    }

    fun edit(transform: (BotForm) -> BotForm) = _state.update { it.copy(form = transform(it.form)) }

    fun refreshHealth() {
        val api = container.botApi() ?: return
        if (_state.value.healthRefreshing) return
        _state.update { it.copy(healthRefreshing = true) }
        viewModelScope.launch {
            try {
                api.refreshHealth()
                val b = api.fetchBots(activeOnly = false).firstOrNull { it.id == botId }
                _state.update { cur ->
                    // 편집 중인 폼은 보존하고 health 등 서버 값만 갱신.
                    cur.copy(bot = b ?: cur.bot, healthRefreshing = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(healthRefreshing = false, message = botErrorText(e)) } }
        }
    }

    fun save() {
        val cur = _state.value
        val api = container.botApi() ?: return
        if (!cur.dirty || cur.saving) return
        val f = cur.form; val s = cur.saved
        val patch = BotPatch(
            name = f.name.takeIf { it != s.name },
            avatar = f.avatar.takeIf { it != s.avatar },
            personaNote = f.personaNote.takeIf { it != s.personaNote },
            workdir = f.workdir.takeIf { it != s.workdir },
            readOnly = f.readOnly.takeIf { it != s.readOnly },
            approvalPolicy = f.approvalPolicy.takeIf { it != s.approvalPolicy },
            computerUse = f.computerUse.takeIf { it != s.computerUse },
        )
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val updated = api.updateBot(botId, patch)
                // 응답엔 dm_channel_id/preview/tone 이 없으므로 기존 값과 병합.
                val old = _state.value.bot
                mergeBot(updated.copy(
                    dmChannelId = updated.dmChannelId ?: old?.dmChannelId,
                    tone = updated.tone ?: old?.tone,
                    preview = updated.preview ?: old?.preview,
                    health = updated.health ?: old?.health,
                ))
                _state.update { it.copy(saving = false, message = "저장했습니다") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(saving = false, message = botErrorText(e)) } }
        }
    }

    fun setArchived(archived: Boolean) {
        val api = container.botApi() ?: return
        viewModelScope.launch {
            try {
                val updated = api.updateBot(botId, BotPatch(status = if (archived) "archived" else "active"))
                _state.update { cur ->
                    val old = cur.bot
                    cur.copy(
                        bot = old?.copy(status = updated.status ?: if (archived) "archived" else "active"),
                        message = if (archived) "보관했습니다" else "보관을 해제했습니다",
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = botErrorText(e)) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    // ---- 지난 세션 (봇 게이트웨이 직접) ----

    /** 이 봇 프로필의 게이트웨이. 설정(host/키)이 없거나 포트를 모르면 null. */
    private fun gateway(): com.hermes.chatandroid.net.HermesApi? {
        val s = container.settingsRepository.current
        if (!s.demoMode && s.apiKey.isBlank()) return null
        val b = _state.value.bot
        val port = b?.gatewayPort
        val profile = if (port != null) com.hermes.chatandroid.data.ProfileEntry(b.profileName ?: b.slug, port) else null
        return container.hermesApi(profile)
    }

    fun gatewayPort(): Int? = _state.value.bot?.gatewayPort

    fun loadSessions() {
        val my = ++gen
        viewModelScope.launch {
            // 봇 정보가 아직 없으면 포트를 모른다 — 짧게 대기하지 않고 loadBot 이후 다시 호출되게 한다.
            _state.update { it.copy(sessionsLoading = true, sessionsError = null) }
            val api = gatewayAfterBot()
            if (api == null) {
                if (my == gen) _state.update { it.copy(sessionsLoading = false, gatewayReady = false, sessions = emptyList(), hasMore = false) }
                return@launch
            }
            try {
                val page = api.fetchSessions(pageSize, 0)
                if (my == gen) _state.update { it.copy(gatewayReady = true, sessionsLoading = false, sessions = page.sessions, hasMore = page.hasMore) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (my == gen) _state.update { it.copy(gatewayReady = true, sessionsLoading = false, sessionsError = gatewayError(e)) } }
        }
    }

    private suspend fun gatewayAfterBot(): com.hermes.chatandroid.net.HermesApi? {
        // loadBot 이 끝날 때까지(최대 ~10초) 기다린 뒤 게이트웨이를 만든다.
        var tries = 0
        while (_state.value.bot == null && _state.value.loading && tries < 100) {
            kotlinx.coroutines.delay(100); tries++
        }
        return gateway()
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loadingMore || s.sessionsLoading) return
        val api = gateway() ?: return
        val my = gen
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = api.fetchSessions(pageSize, s.sessions.size)
                if (my != gen) return@launch
                _state.update { cur ->
                    val known = cur.sessions.map { it.id }.toSet()
                    cur.copy(
                        sessions = cur.sessions + page.sessions.filter { it.id !in known },
                        hasMore = page.hasMore && page.sessions.isNotEmpty(),
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loadingMore = false, sessionsError = gatewayError(e)) } }
        }
    }

    fun renameSession(id: String, title: String) = sessionOp { api ->
        api.renameSession(id, title)
        _state.update { cur -> cur.copy(sessions = cur.sessions.map { if (it.id == id) it.copy(title = title) else it }) }
    }

    fun deleteSession(id: String) = sessionOp { api ->
        api.deleteSession(id)
        _state.update { cur -> cur.copy(sessions = cur.sessions.filter { it.id != id }) }
    }

    fun createSession(onCreated: (port: Int, sessionId: String) -> Unit) = sessionOp { api ->
        val id = api.createSession().id
        onCreated(gatewayPort() ?: 0, id)
    }

    fun dismissSessionsError() = _state.update { it.copy(sessionsError = null) }

    private fun sessionOp(block: suspend (com.hermes.chatandroid.net.HermesApi) -> Unit) {
        val api = gateway() ?: return
        viewModelScope.launch {
            try { block(api) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(sessionsError = gatewayError(e)) } }
        }
    }

    private fun gatewayError(e: Throwable): String =
        if (e is ApiException && e.code == 401) "인증 실패(401): 더보기 > 설정에서 게이트웨이 키를 확인하세요."
        else e.message?.takeIf { it.isNotBlank() } ?: "세션 요청 실패"

    class Factory(private val container: AppContainer, private val botId: Int) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BotProfileViewModel(container, botId) as T
    }
}
