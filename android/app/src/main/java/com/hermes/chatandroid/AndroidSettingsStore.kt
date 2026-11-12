package com.hermes.chatandroid

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermes.chatandroid.data.ProfileEntry
import com.hermes.chatandroid.data.Settings
import com.hermes.chatandroid.data.SettingsStore

/** 비밀값(apiKey/bridgeToken/botToken)은 EncryptedSharedPreferences, 나머지는 일반 SharedPreferences. */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val plain = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): Settings = Settings(
        host = plain.getString("host", "") ?: "",
        // "name:port,name:port"
        profiles = (plain.getString("profiles", "") ?: "").split(',').mapNotNull {
            val i = it.lastIndexOf(':')
            val port = if (i > 0) it.substring(i + 1).toIntOrNull() else null
            if (port == null) null else ProfileEntry(it.substring(0, i), port)
        },
        selectedProfile = plain.getString("selectedProfile", "") ?: "",
        bridgeUrl = plain.getString("bridgeUrl", "") ?: "",
        botHost = plain.getString("botHost", "") ?: "",
        apiKey = secure.getString("apiKey", "") ?: "",
        bridgeToken = secure.getString("bridgeToken", "") ?: "",
        botToken = secure.getString("botToken", "") ?: "",
        onboardingComplete = plain.getBoolean("onboardingComplete", false),
    )

    override fun save(settings: Settings) {
        plain.edit()
            .putString("host", settings.host)
            .putString("profiles", settings.profiles.joinToString(",") { "${it.name}:${it.port}" })
            .putString("selectedProfile", settings.selectedProfile)
            .putString("bridgeUrl", settings.bridgeUrl)
            .putString("botHost", settings.botHost)
            .putBoolean("onboardingComplete", settings.onboardingComplete)
            .apply()
        secure.edit()
            .putString("apiKey", settings.apiKey)
            .putString("bridgeToken", settings.bridgeToken)
            .putString("botToken", settings.botToken)
            .apply()
    }
}
