package com.hermes.chatandroid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** 맥미니 호스트(IP 또는 이름). 스킴/포트 없이. */
    val host: String = "",
    val profiles: List<ProfileEntry> = emptyList(),
    val selectedProfile: String = "",
    /** 비어 있으면 host:8765 로 간주. */
    val bridgeUrl: String = "",
    /** 비어 있으면 봇 기능 비활성. "host", "host:8900", "http://host:8900" 모두 허용. */
    val botHost: String = "",
    // 비밀값 — EncryptedSharedPreferences 에만 저장
    val apiKey: String = "",
    val bridgeToken: String = "",
    val botToken: String = "",
    val onboardingComplete: Boolean = false,
) {
    val currentProfile: ProfileEntry? get() = profiles.firstOrNull { it.name == selectedProfile } ?: profiles.firstOrNull()

    val cleanHost: String get() = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/').substringBefore('/')

    fun gatewayUrl(profile: ProfileEntry? = currentProfile): String? =
        if (profile == null || cleanHost.isEmpty()) null else "http://$cleanHost:${profile.port}"

    val bridgeBaseUrl: String?
        get() = normalizeUrl(bridgeUrl, 8765) ?: if (cleanHost.isNotEmpty()) "http://$cleanHost:8765" else null

    val botBaseUrl: String? get() = normalizeUrl(botHost, 8900)

    val botConfigured: Boolean get() = botHost.isNotBlank()

    companion object {
        /** "host" -> http://host:defaultPort, "host:1234" -> http://host:1234, 스킴 있으면 그대로. */
        fun normalizeUrl(raw: String, defaultPort: Int): String? {
            val s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (s.contains("://")) return s
            return if (s.contains(':')) "http://$s" else "http://$s:$defaultPort"
        }
    }
}

/** 영속화 추상화 — Android 구현은 [com.hermes.chatandroid.AndroidSettingsStore]. 테스트는 메모리 구현. */
interface SettingsStore {
    fun load(): Settings
    fun save(settings: Settings)
}

class SettingsRepository(private val store: SettingsStore) {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        store.save(next)
        _settings.value = next
    }
}
