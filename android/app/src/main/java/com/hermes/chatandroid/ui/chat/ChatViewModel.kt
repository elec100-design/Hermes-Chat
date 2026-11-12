package com.hermes.chatandroid.ui.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.StreamEvent
import com.hermes.chatandroid.net.HermesApi
import com.hermes.chatandroid.net.ThinkFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiMessage(
    val key: Long,
    val isUser: Boolean,
    val content: String,
    val streaming: Boolean = false,
)

class PendingAttachment(val name: String, val bytes: ByteArray) {
    val id: Long = nextId++
    companion object { private var nextId = 0L }
}

data class ChatUiState(
    val title: String = "대화",
    val loading: Boolean = true,
    val messages: List<UiMessage> = emptyList(),
    val attachments: List<PendingAttachment> = emptyList(),
    val sending: Boolean = false,
    val recovering: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(private val container: AppContainer, private val sessionId: String) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sendJob: Job? = null
    private var nextKey = 0L
    private var hasTitle = true // 알 수 없으면 제목 있음으로 간주(덮어쓰기 방지)
    private var titleAttempted = false

    init { loadHistory() }

    fun loadHistory() {
        val api = container.hermesApi()
        if (api == null) {
            _state.update { it.copy(loading = false, error = "게이트웨이가 설정되지 않았습니다. 설정에서 호스트/프로필을 확인하세요.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val msgs = api.fetchMessages(sessionId)
                    .filter { it.role == "user" || it.role == "assistant" }
                    .filter { it.isUser || ThinkFilter.strip(it.content).isNotBlank() }
                    .map { UiMessage(nextKey++, it.isUser, it.content) }
                _state.update { s ->
                    // 로딩 중 전송이 시작됐다면 덮어쓰지 않는다
                    if (s.sending) s.copy(loading = false) else s.copy(loading = false, messages = msgs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "기록을 불러오지 못했습니다: ${e.message}") }
            }
            // 제목 확인(best effort)
            try {
                val s = api.fetchSessions(limit = 100).sessions.firstOrNull { it.id == sessionId }
                if (s != null) {
                    hasTitle = !s.title.isNullOrBlank()
                    _state.update { it.copy(title = s.displayTitle) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun addAttachments(resolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    val att = withContext(Dispatchers.IO) { readAttachment(resolver, uri) }
                    _state.update { it.copy(attachments = it.attachments + att) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "첨부 파일을 읽지 못했습니다") }
                }
            }
        }
    }

    private fun readAttachment(resolver: ContentResolver, uri: Uri): PendingAttachment {
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val filename = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val out = java.io.ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_ATTACHMENT_BYTES) throw IllegalStateException("$filename: 20MB를 넘는 파일은 첨부할 수 없습니다")
            }
        } ?: throw IllegalStateException("$filename: 파일을 열 수 없습니다")
        return PendingAttachment(filename, out.toByteArray())
    }

    fun removeAttachment(id: Long) {
        _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun stop() { sendJob?.cancel() }

    fun send(textRaw: String) {
        val text = textRaw.trim()
        val s = _state.value
        if (s.sending || (text.isEmpty() && s.attachments.isEmpty())) return
        val api = container.hermesApi()
        if (api == null) {
            _state.update { it.copy(error = "게이트웨이가 설정되지 않았습니다.") }
            return
        }
        val atts = s.attachments
        val userCount = s.messages.count { it.isUser } + 1
        val isFirst = s.messages.isEmpty()
        val shown = if (atts.isEmpty()) text else
            (atts.joinToString("\n") { "📎 ${it.name}" } + if (text.isEmpty()) "" else "\n\n$text")
        val assistantKey = nextKey + 1
        _state.update {
            it.copy(
                messages = it.messages + UiMessage(nextKey, true, shown) + UiMessage(assistantKey, false, "", streaming = true),
                attachments = emptyList(), sending = true, recovering = false, error = null,
            )
        }
        nextKey += 2

        sendJob = viewModelScope.launch {
            var raw = ""
            var error: String? = null
            try {
                // 업로드
                val paths = ArrayList<String>()
                if (atts.isNotEmpty()) {
                    val bridge = container.bridgeApi()
                    val profile = container.settingsRepository.current.currentProfile?.name
                    if (bridge == null || profile == null) {
                        error = "첨부 업로드를 하려면 Bridge 설정이 필요합니다. 설정에서 호스트를 확인하세요."
                    } else {
                        try {
                            for (a in atts) paths += bridge.upload(a.bytes, a.name, profile)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "첨부 업로드 실패: ${e.message}"
                        }
                    }
                }
                if (error == null) {
                    try {
                        api.streamChat(sessionId, text, paths).collect { ev ->
                            when (ev) {
                                is StreamEvent.Delta -> { raw += ev.text; setAssistant(assistantKey, raw) }
                                is StreamEvent.Completed -> if (ev.content.isNotBlank()) { raw = ev.content; setAssistant(assistantKey, raw) }
                                is StreamEvent.Error -> error = ev.message
                                else -> {}
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "스트림 오류"
                    }
                    if (error == null && ThinkFilter.strip(raw).isBlank()) {
                        _state.update { it.copy(recovering = true) }
                        val deadline = if (raw.isBlank()) 300_000L else 6_000L
                        val recovered = api.pollForMissedReply(sessionId, userCount, deadline)
                        if (recovered != null) { raw = recovered; setAssistant(assistantKey, raw) }
                        else error = "응답을 받지 못했습니다."
                    }
                }
                finish(assistantKey, error)
                if (error == null && isFirst) autoTitle(api, text.ifEmpty { atts.firstOrNull()?.name.orEmpty() })
            } catch (e: CancellationException) {
                finish(assistantKey, null)
                throw e
            }
        }
    }

    private fun setAssistant(key: Long, content: String) {
        _state.update { s -> s.copy(messages = s.messages.map { if (it.key == key) it.copy(content = content) else it }) }
    }

    /** 스트리밍 종료: 빈 assistant 말풍선 제거, 나머지는 streaming 해제. */
    private fun finish(key: Long, error: String?) {
        _state.update { s ->
            val msgs = s.messages.mapNotNull {
                if (it.key != key) it
                else if (ThinkFilter.strip(it.content).isBlank()) null
                else it.copy(streaming = false)
            }
            s.copy(messages = msgs, sending = false, recovering = false, error = error)
        }
    }

    private suspend fun autoTitle(api: HermesApi, source: String) {
        if (hasTitle || titleAttempted || source.isBlank()) return
        titleAttempted = true
        val title = source.trim().replace('\n', ' ').take(30)
        try {
            api.renameSession(sessionId, title)
            hasTitle = true
            _state.update { it.copy(title = title) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

        fun factory(container: AppContainer, sessionId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container, sessionId) as T
        }
    }
}
