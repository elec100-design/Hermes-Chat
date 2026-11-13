package com.hermes.chatandroid.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * SSE 응답을 [SseFrame] 흐름으로. 수집이 취소되면 OkHttp Call 을 취소해 연결을 닫는다(중단 버튼용).
 * 비 2xx 는 [ApiException].
 */
fun OkHttpClient.sseFrames(request: Request): Flow<SseFrame> = callbackFlow {
    val call = newCall(request)
    launch(Dispatchers.IO) {
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    throw ApiException(resp.code, errorMessageFor(resp.code, text), errorDetailOf(text))
                }
                val source = resp.body?.source() ?: return@use
                for (frame in SseParser.parse(source)) {
                    send(frame)
                }
            }
            close()
        } catch (e: Throwable) {
            close(e)
        }
    }
    awaitClose { call.cancel() }
}
