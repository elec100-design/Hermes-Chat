package com.hermes.chatandroid.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.CronForm
import com.hermes.chatandroid.data.CronJob
import com.hermes.chatandroid.data.CronText
import com.hermes.chatandroid.ui.kanban.bridgeErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 편집 중인 잡: [original] 이 null 이면 새로 만들기. */
data class CronEditing(val original: CronJob?, val form: CronForm)

data class CronState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val profiles: List<String> = emptyList(),
    val jobs: List<CronJob> = emptyList(),
    /** null = 모든 프로필. */
    val profileFilter: String? = null,
    /** 잡 키(profile/id) — 토글·실행 중 표시. */
    val busy: Set<String> = emptySet(),
    val editing: CronEditing? = null,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val visible: List<CronJob>
        get() = jobs.filter { profileFilter == null || it.profile == profileFilter }
            .sortedWith(compareBy<CronJob> { it.paused }.thenBy { it.nextRunAt?.let(CronText::parseInstant) ?: java.time.Instant.MAX })
}

fun CronJob.key() = "$profile/$id"

class CronViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CronState())
    val state: StateFlow<CronState> = _state.asStateFlow()

    init { refresh(initial = true) }

    fun refresh(initial: Boolean = false) {
        val api = container.bridgeApi() ?: run {
            _state.update { it.copy(loading = false, error = "Bridge 주소가 없습니다. 더보기 > 설정에서 호스트를 입력하세요.") }
            return
        }
        _state.update { if (initial) it.copy(loading = true) else it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val profiles = api.fetchProfiles().map { it.name }.ifEmpty { listOf("default") }
                // 프로필별로 따로 읽고, 하나가 실패해도 나머지는 보여준다.
                val results = profiles.map { p -> async { p to runCatching { api.fetchCronJobs(p) } } }.awaitAll()
                val jobs = results.flatMap { it.second.getOrNull().orEmpty() }
                val failed = results.filter { it.second.isFailure }
                _state.update {
                    it.copy(
                        profiles = profiles, jobs = jobs,
                        error = failed.firstOrNull()?.let { (p, r) -> "$p: ${bridgeErrorText(r.exceptionOrNull()!!)}" },
                        profileFilter = it.profileFilter?.takeIf { f -> f in profiles },
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = bridgeErrorText(e)) } }
            finally { _state.update { it.copy(loading = false, refreshing = false) } }
        }
    }

    fun setFilter(p: String?) = _state.update { it.copy(profileFilter = p) }

    private fun jobAction(job: CronJob, done: String?, block: suspend (com.hermes.chatandroid.net.BridgeApi) -> Unit) {
        val api = container.bridgeApi() ?: return
        _state.update { it.copy(busy = it.busy + job.key()) }
        viewModelScope.launch {
            try {
                block(api)
                val fresh = runCatching { api.fetchCronJobs(job.profile) }.getOrNull()
                _state.update { st ->
                    st.copy(
                        jobs = if (fresh != null) st.jobs.filter { it.profile != job.profile } + fresh else st.jobs,
                        message = done ?: st.message,
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = bridgeErrorText(e)) } }
            finally { _state.update { it.copy(busy = it.busy - job.key()) } }
        }
    }

    fun toggle(job: CronJob, enabled: Boolean) {
        // 즉시 반영(낙관적) 후 서버 값으로 덮어쓴다.
        _state.update { st -> st.copy(jobs = st.jobs.map { if (it.key() == job.key()) it.copy(enabled = enabled, state = if (enabled) "scheduled" else "paused") else it }) }
        jobAction(job, if (enabled) "‘${job.title}’ 재개" else "‘${job.title}’ 일시정지") { it.setCronEnabled(job.profile, job.id, enabled) }
    }

    fun runNow(job: CronJob) = jobAction(job, "‘${job.title}’을(를) 다음 스케줄러 틱(1분 이내)에 실행합니다") { it.runCronJob(job.profile, job.id) }

    fun delete(job: CronJob) = jobAction(job, "‘${job.title}’ 삭제") { it.deleteCronJob(job.profile, job.id) }

    fun startCreate() = _state.update {
        it.copy(editing = CronEditing(null, CronForm(profile = it.profileFilter ?: it.profiles.firstOrNull() ?: "default")))
    }

    fun startEdit(job: CronJob) = _state.update { it.copy(editing = CronEditing(job, CronForm.of(job))) }

    fun updateForm(f: CronForm) = _state.update { st -> st.copy(editing = st.editing?.copy(form = f)) }

    fun cancelEdit() = _state.update { it.copy(editing = null) }

    fun save() {
        val api = container.bridgeApi() ?: return
        val ed = _state.value.editing ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val orig = ed.original
                if (orig == null) api.createCronJob(ed.form)
                else api.updateCronJob(orig.profile, orig.id, CronForm.of(orig), ed.form)
                val fresh = api.fetchCronJobs(ed.form.profile)
                _state.update { st ->
                    st.copy(
                        editing = null,
                        jobs = st.jobs.filter { it.profile != ed.form.profile } + fresh,
                        message = if (orig == null) "‘${ed.form.name}’ 크론을 만들었습니다" else "저장했습니다",
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = bridgeErrorText(e)) } }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
