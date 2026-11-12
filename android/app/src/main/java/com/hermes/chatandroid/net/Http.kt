package com.hermes.chatandroid.net

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP 오류(비 2xx). [code] 401 = 인증 문제. */
class ApiException(val code: Int, message: String) : IOException(message)

val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

fun jsonBody(json: String): RequestBody = json.toRequestBody(JSON_MEDIA)

/** "http://host:port/" 형태 베이스 + 경로 결합. */
fun joinUrl(base: String, path: String): String =
    base.trimEnd('/') + "/" + path.trimStart('/')

fun errorMessageFor(code: Int, body: String): String {
    val detail = parseJsonOrNull(body).asObject()?.let { it.text("detail") ?: it.text("message") ?: it.text("error") }
        ?: body.take(200).ifBlank { null }
    return when (code) {
        401 -> "인증 실패(401) - API 키/토큰을 확인하세요" + (detail?.let { ": $it" } ?: "")
        404 -> "찾을 수 없음(404)" + (detail?.let { ": $it" } ?: "")
        else -> "HTTP $code" + (detail?.let { ": $it" } ?: "")
    }
}

/** 취소 시 Call 을 닫는 suspend 실행. 2xx 가 아니면 [ApiException]. 본문 문자열을 돌려준다. */
suspend fun OkHttpClient.fetchText(request: Request): String =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val text = r.body?.string().orEmpty()
                        if (r.isSuccessful) {
                            if (cont.isActive) cont.resume(text)
                        } else if (cont.isActive) {
                            cont.resumeWithException(ApiException(r.code, errorMessageFor(r.code, text)))
                        }
                        Unit
                    }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

fun Request.Builder.bearer(token: String): Request.Builder =
    if (token.isBlank()) this else header("Authorization", "Bearer $token")
