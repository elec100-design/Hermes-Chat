package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.HermesApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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

    suspend fun scan(host: String, apiKey: String): List<ProfileEntry> = coroutineScope {
        val clean = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (clean.isEmpty()) return@coroutineScope emptyList()
        ports.map { port ->
            async(Dispatchers.IO) {
                try {
                    val ids = HermesApi(urlFor(clean, port), apiKey, http).fetchModelIds()
                    ids.firstOrNull()?.let { ProfileEntry(it, port) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.port }
    }
}
