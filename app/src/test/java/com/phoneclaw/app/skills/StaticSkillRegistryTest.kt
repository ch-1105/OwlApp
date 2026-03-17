package com.phoneclaw.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StaticSkillRegistryTest {
    private val registry = StaticSkillRegistry()

    @Test
    fun matchesWifiBeforeGenericSettings() {
        val action = registry.matchUserMessage("open wifi settings")

        assertNotNull(action)
        assertEquals("open_wifi_settings", action?.actionId)
    }

    @Test
    fun matchesBluetoothChinesePrompt() {
        val action = registry.matchUserMessage("打开蓝牙设置")

        assertNotNull(action)
        assertEquals("open_bluetooth_settings", action?.actionId)
    }

    @Test
    fun fallsBackToGenericSystemSettings() {
        val action = registry.matchUserMessage("带我去设置页")

        assertNotNull(action)
        assertEquals("open_system_settings", action?.actionId)
    }

    @Test
    fun matchesBrowserOpenActionWhenUrlIsPresent() {
        val action = registry.matchUserMessage("打开 https://openai.com")

        assertNotNull(action)
        assertEquals("open_web_url", action?.actionId)
    }

    @Test
    fun matchesBrowserFetchActionWhenReadingContent() {
        val action = registry.matchUserMessage("帮我读取 https://example.com 的网页内容")

        assertNotNull(action)
        assertEquals("fetch_web_page_content", action?.actionId)
    }
}
