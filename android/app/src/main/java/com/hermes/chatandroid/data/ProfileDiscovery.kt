package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.ApiException
import com.hermes.chatandroid.net.HermesApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** [unauthorized]: 응답은 했지만 401 을 준 포트가 하나라도 있음(= API 키 문제). */
data class ScanResult(val profiles: List<ProfileEntry>, val unauthorized: Boolean = false) {
    /** 비어 있고 401 이 있었다면 "네트워크"가 아니라 키 문제라고 알려 줄 문구. */
    val errorMessage: String?
        get() = when {
            profiles.isNotEmpty() -> null
            unauthorized -> "API 키가 올바르지 않습니다"
            else -> "프로필을 찾지 못했습니다. 호스트/네트워크(Tailscale)를 확인하세요."
        }
}

/** 게이트웨이 포트 스캔(8642–8651): /v1/models 의 첫 id = 프로필 이름. */
class ProfileDiscovery(
    private val ports: IntRange = 8642..8651,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build(),
    private val urlFor: (host: String, port: Int) -> String = { h, p -> "http://$h:$p" },
) {
    private val http = client

    suspend fun scan(host: String, apiKey: String): ScanResult = coroutineScope {
        val clean = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (clean.isEmpty()) return@coroutineScope ScanResult(emptyList())
        val results = ports.map { port ->
            async<Pair<ProfileEntry?, Boolean>?>(Dispatchers.IO) {
                try {
                    val ids = HermesApi(urlFor(clean, port), apiKey, http).fetchModelIds()
                    ids.firstOrNull()?.let { ProfileEntry(it, port) to false }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.code == 401) Pair(null, true) else null
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
        ScanResult(results.mapNotNull { it.first }.sortedBy { it.port }, results.any { it.second })
    }
}
