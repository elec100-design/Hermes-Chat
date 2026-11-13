package com.hermes.chatandroid

import com.hermes.chatandroid.ui.bots.BotAvatarPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotAvatarPresetsTest {

    @Test
    fun hasEightUniqueThemedPresets() {
        val all = BotAvatarPresets.all
        assertEquals(8, all.size)
        assertEquals(
            listOf("hermes", "opus", "grok", "safety", "gemini", "invest", "wiki", "coder"),
            all.map { it.id },
        )
        assertEquals(8, all.map { it.key }.toSet().size)
        assertEquals(8, all.map { it.drawableRes }.toSet().size)
    }

    @Test
    fun findMatchesKeysCaseInsensitivelyAndIgnoresEmojis() {
        assertEquals("hermes", BotAvatarPresets.find("px:hermes")?.id)
        assertEquals("opus", BotAvatarPresets.find(" PX:OPUS ")?.id)
        assertNotNull(BotAvatarPresets.find("px:grok"))
        assertNotNull(BotAvatarPresets.find("px:safety"))
        assertNotNull(BotAvatarPresets.find("px:gemini"))
        assertNotNull(BotAvatarPresets.find("px:invest"))
        assertNotNull(BotAvatarPresets.find("px:wiki"))
        assertNotNull(BotAvatarPresets.find("px:coder"))

        assertTrue(BotAvatarPresets.isPreset("px:wiki"))
        assertFalse(BotAvatarPresets.isPreset("🤖"))
        assertFalse(BotAvatarPresets.isPreset("hermes"))
        assertNull(BotAvatarPresets.find(null))
        assertNull(BotAvatarPresets.find(""))
        assertNull(BotAvatarPresets.find("px:unknown"))
    }
}
