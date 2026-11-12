package com.hermes.chatandroid.ui.bots

import com.hermes.chatandroid.data.BotMessage
import com.hermes.chatandroid.data.BotStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 잡 스트림 구독 로직 (android 의존 없음, 테스트 가능).
 * - queued/running + jobId 인 메시지를 구독 (같은 잡 중복 구독 방지)
 * - 구독마다 서버가 처음부터 재생하므로 시작 시 버퍼 리셋
 * - End: [endDelayMs] 대기 후 reload → 최종본 반영 + 새 in-flight(봇→봇 연쇄) 구독
 */
class BotJobFollower(
    private val scope: CoroutineScope,
    private val streamOf: (Int) -> Flow<BotStreamEvent>,
    private val reload: suspend () -> List<BotMessage>,
    private val onMessages: (List<BotMessage>) -> Unit,
    private val onReset: (messageId: Int) -> Unit,
    private val onDelta: (messageId: Int, text: String) -> Unit,
    private val onStreamError: (messageId: Int, message: String) -> Unit,
    private val onReloadError: (Throwable) -> Unit = {},
    private val endDelayMs: Long = 700L,
) {
    private val active = HashMap<Int, Job>()

    /** 목록을 반영하고 새로 구독해야 할 메시지를 구독한다. */
    fun submit(messages: List<BotMessage>) {
        onMessages(messages)
        messages.filter { it.isInFlight && it.jobId != null }.forEach { follow(it.id, it.jobId!!) }
    }

    fun follow(messageId: Int, jobId: Int) {
        if (active.containsKey(jobId)) return
        active[jobId] = scope.launch {
            onReset(messageId)
            var ended = false
            try {
                streamOf(jobId)
                    .catch { if (it is CancellationException) throw it; onStreamError(messageId, it.message ?: "스트림 오류") }
                    .collect { ev ->
                        when (ev) {
                            is BotStreamEvent.Delta -> onDelta(messageId, ev.text)
                            is BotStreamEvent.End -> ended = true
                            is BotStreamEvent.Error -> onStreamError(messageId, ev.message)
                        }
                    }
            } finally {
                active.remove(jobId)
            }
            if (ended) {
                delay(endDelayMs)
                try {
                    submit(reload())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onReloadError(e)
                }
            }
        }
    }

    fun cancelAll() {
        active.values.toList().forEach { it.cancel() }
        active.clear()
    }
}
