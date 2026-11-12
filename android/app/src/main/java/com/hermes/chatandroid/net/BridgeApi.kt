package com.hermes.chatandroid.net

import com.hermes.chatandroid.data.BridgeProfile
import com.hermes.chatandroid.data.Parsers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Hermes Bridge(:8765) — 프로필 목록과 첨부 업로드만. [token] 비어 있으면 인증 헤더 없음. */
open class BridgeApi(
    private val baseUrl: String,
    private val token: String,
    client: OkHttpClient = defaultHttpClient(),
) {
    private val http = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    open suspend fun fetchProfiles(): List<BridgeProfile> {
        val req = Request.Builder().url(joinUrl(baseUrl, "profiles")).bearer(token).build()
        return Parsers.parseBridgeProfiles(http.fetchText(req))
    }

    /** 파일을 프로필 uploads 폴더에 올리고 맥미니 절대경로를 돌려준다. */
    open suspend fun upload(bytes: ByteArray, filename: String, profile: String): String {
        // HTTP 헤더는 ASCII 만 안전 — 서버도 [A-Za-z0-9._-가-힣] 외에는 _ 로 치환한다.
        val name = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val req = Request.Builder()
            .url(joinUrl(baseUrl, "upload/${URLEncoder.encode(profile, "UTF-8")}"))
            .bearer(token)
            .header("X-Filename", name)
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val text = http.fetchText(req)
        return parseJsonOrNull(text).asObject()?.text("path")
            ?: throw ApiException(0, "업로드 응답에 path 가 없습니다")
    }
}
