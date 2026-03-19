package com.phoneclaw.app.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractsTest {
    @Test
    fun pageSpec_defaultsSchemaVersion() {
        val pageSpec = PageSpec(
            pageId = "settings_home",
            pageName = "Settings Home",
            appPackage = "com.android.settings",
            activityName = "com.android.settings.Settings",
            matchRules = listOf(PageMatchRule(type = "activity_name", value = "Settings")),
            availableActions = listOf("open_wifi_settings"),
        )

        assertEquals(CONTRACT_SCHEMA_VERSION, pageSpec.schemaVersion)
        assertEquals("settings_home", pageSpec.pageId)
    }

    @Test
    fun pageGraph_defaultsSchemaVersionAndKeepsTransitions() {
        val pageGraph = PageGraph(
            appPackage = "com.android.settings",
            pages = listOf(
                PageSpec(
                    pageId = "settings_home",
                    pageName = "Settings Home",
                    appPackage = "com.android.settings",
                    activityName = "com.android.settings.Settings",
                    matchRules = listOf(PageMatchRule(type = "activity_name", value = "Settings")),
                    availableActions = listOf("open_wifi_settings"),
                ),
            ),
            transitions = listOf(
                PageTransition(
                    fromPageId = "settings_home",
                    toPageId = "wifi_page",
                    triggerActionId = "open_wifi_settings",
                    triggerNodeDescription = "Tap WLAN row",
                ),
            ),
        )

        assertEquals(CONTRACT_SCHEMA_VERSION, pageGraph.schemaVersion)
        assertEquals(1, pageGraph.transitions.size)
        assertEquals("wifi_page", pageGraph.transitions.single().toPageId)
    }

    @Test
    fun modelProfile_capturesCapabilityFlags() {
        val profile = ModelProfile(
            provider = "openai",
            modelId = "gpt-5.4",
            supportsToolCalling = true,
            supportsStructuredOutput = true,
            supportsMultimodal = true,
            preferredForPlanning = true,
            preferredForSummary = false,
            preferredForPageUnderstanding = true,
            allowSensitiveData = false,
        )

        assertTrue(profile.supportsMultimodal)
        assertTrue(profile.preferredForPageUnderstanding)
        assertEquals("openai", profile.provider)
    }
}
