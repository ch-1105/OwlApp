package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.CONTRACT_SCHEMA_VERSION
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticSkillRegistryTest {
    private val registry = bundledSkillRegistryForTests()

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
    fun matchesNotificationSettingsPrompt() {
        val action = registry.matchUserMessage("打开通知设置")

        assertNotNull(action)
        assertEquals("open_notification_settings", action?.actionId)
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

    @Test
    fun excludesDisabledActionsFromDiscoveryButKeepsLookupForPolicy() {
        val disabledAction = testRegisteredAction(
            skillId = "test.disabled_skill",
            actionId = "disabled_action",
            skillEnabled = false,
            actionEnabled = false,
        )
        val registry = StaticSkillRegistry(registeredActions = listOf(disabledAction))

        assertTrue(registry.allSkills().isEmpty())
        assertTrue(registry.allActions().isEmpty())
        assertNull(registry.matchUserMessage("run test action"))
        assertNotNull(registry.findAction("disabled_action"))
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val error = runCatching {
            StaticSkillRegistry(
                registeredActions = listOf(
                    testRegisteredAction(
                        skillId = "test.unsupported_schema",
                        actionId = "test_action",
                        schemaVersion = "v2",
                    ),
                ),
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message?.contains("unsupported schema version") == true)
    }

    @Test
    fun rejectsDuplicateActionIds() {
        val error = runCatching {
            StaticSkillRegistry(
                registeredActions = listOf(
                    testRegisteredAction(
                        skillId = "test.duplicate_one",
                        actionId = "duplicate_action",
                    ),
                    testRegisteredAction(
                        skillId = "test.duplicate_two",
                        actionId = "duplicate_action",
                    ),
                ),
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message?.contains("Duplicate action ids") == true)
    }

    private fun testRegisteredAction(
        skillId: String,
        actionId: String,
        schemaVersion: String = CONTRACT_SCHEMA_VERSION,
        skillEnabled: Boolean = true,
        actionEnabled: Boolean = true,
    ): RegisteredSkillAction {
        val action = SkillActionManifest(
            actionId = actionId,
            displayName = "Test Action",
            description = "Run a test action",
            executorType = "intent",
            riskLevel = RiskLevel.SAFE,
            requiresConfirmation = false,
            expectedOutcome = "The test action finishes successfully",
            enabled = actionEnabled,
            exampleUtterances = listOf("run test action"),
            matchKeywords = listOf("test"),
        )
        val skill = SkillManifest(
            schemaVersion = schemaVersion,
            skillId = skillId,
            skillVersion = "0.1.0",
            skillType = "system",
            displayName = "Test Skill",
            owner = "test",
            platform = "android",
            appPackage = "com.example.test",
            defaultRiskLevel = RiskLevel.SAFE,
            enabled = skillEnabled,
            actions = listOf(action),
        )

        return RegisteredSkillAction(
            skill = skill,
            action = action,
            intentAction = "android.settings.SETTINGS",
        )
    }
}
