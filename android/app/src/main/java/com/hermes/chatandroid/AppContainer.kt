package com.hermes.chatandroid

import android.app.Application
import com.hermes.chatandroid.data.DemoBotApi
import com.hermes.chatandroid.data.DemoBridgeApi
import com.hermes.chatandroid.data.DemoHermesApi
import com.hermes.chatandroid.data.ProfileDiscovery
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.data.SettingsRepository
import com.hermes.chatandroid.net.BotApi
import com.hermes.chatandroid.net.BridgeApi
import com.hermes.chatandroid.net.HermesApi
import com.hermes.chatandroid.net.defaultHttpClient

/** 수동 DI. 팩토리는 호출 시점의 최신 설정으로 클라이언트를 만든다(설정 변경 즉시 반영). */
class AppContainer(val settingsRepository: SettingsRepository) {
    val httpClient = defaultHttpClient()
    val profileDiscovery = ProfileDiscovery()

    // 데모 모드용 인메모리 페이크 — 상태(세션/메시지/승인)를 유지하려고 컨테이너당 1개.
    private val demoHermes by lazy { DemoHermesApi() }
    private val demoBot by lazy { DemoBotApi() }
    private val demoBridge by lazy { DemoBridgeApi() }

    /** 선택된(또는 지정한) 프로필의 게이트웨이. host/프로필 미설정이면 null. */
    fun hermesApi(profile: ProfileEntry? = null): HermesApi? {
        val s = settingsRepository.current
        if (s.demoMode) return demoHermes
        val url = s.gatewayUrl(profile ?: s.currentProfile) ?: return null
        return HermesApi(url, s.apiKey, httpClient)
    }

    fun bridgeApi(): BridgeApi? {
        val s = settingsRepository.current
        if (s.demoMode) return demoBridge
        return s.bridgeBaseUrl?.let { BridgeApi(it, s.bridgeToken, httpClient) }
    }

    fun botApi(): BotApi? {
        val s = settingsRepository.current
        if (s.demoMode) return demoBot
        return s.botBaseUrl?.let { BotApi(it, s.botToken, httpClient) }
    }
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(SettingsRepository(AndroidSettingsStore(this)))
    }
}
