package com.hermes.chatandroid.ui.bots

import androidx.annotation.DrawableRes
import com.hermes.chatandroid.R

/**
 * 봇 아바타로 선택 가능한 내장 16비트 픽셀 아트 프리셋.
 * 서버 `Bot.avatar` 필드에는 `"px:<id>"` 형태로 저장한다.
 */
data class BotAvatarPreset(
    val id: String,
    val label: String,
    @DrawableRes val drawableRes: Int,
) {
    val key: String get() = "$KEY_PREFIX$id"

    companion object {
        const val KEY_PREFIX = "px:"
    }
}

object BotAvatarPresets {
    val all: List<BotAvatarPreset> = listOf(
        BotAvatarPreset("hermes", "Hermes", R.drawable.avatar_hermes),
        BotAvatarPreset("opus", "Opus", R.drawable.avatar_opus),
        BotAvatarPreset("grok", "Grok", R.drawable.avatar_grok),
        BotAvatarPreset("safety", "Safety", R.drawable.avatar_safety),
        BotAvatarPreset("gemini", "Gemini", R.drawable.avatar_gemini),
        BotAvatarPreset("invest", "Invest", R.drawable.avatar_invest),
        BotAvatarPreset("wiki", "Wiki", R.drawable.avatar_wiki),
        BotAvatarPreset("coder", "Coder", R.drawable.avatar_coder),
    )

    private val byKey: Map<String, BotAvatarPreset> = all.associateBy { it.key }

    fun find(value: String?): BotAvatarPreset? {
        val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return byKey[normalized]
    }

    fun isPreset(value: String?): Boolean = find(value) != null
}
