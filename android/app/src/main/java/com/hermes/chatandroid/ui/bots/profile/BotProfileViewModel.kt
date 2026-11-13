package com.hermes.chatandroid.ui.bots.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Bot
import com.hermes.chatandroid.data.BotPatch
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
) {
    val dirty: Boolean get() = form != saved
}

class BotProfileViewModel(private val container: AppContainer, private val botId: Int) : ViewModel() {
    private val _state = MutableStateFlow(BotProfileState())
    val state: StateFlow<BotProfileState> = _state.asStateFlow()

    init { loadBot() }

    private fun mergeBot(b: Bot, resetForm: Boolean = true) = _state.update {
        val f = BotForm.of(b)
        it.copy(bot = b, loading = false, error = null, saved = f, form = if (resetForm) f else it.form)
    }

    fun loadBot() {
        val api = container.botApi()
        if (api == null) { _state.update { it.copy(loading = false, error = "봇 서버가 설정되지 않았습니다") }; return }
        viewModelScope.launch {
            try {
                val all = api.fetchBots(activeOnly = false)
                val b = all.firstOrNull { it.id == botId }
                if (b == null) _state.update { it.copy(loading = false, error = "봇을 찾을 수 없습니다") } else {
                    mergeBot(b)
                }
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

    class Factory(private val container: AppContainer, private val botId: Int) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BotProfileViewModel(container, botId) as T
    }
}
