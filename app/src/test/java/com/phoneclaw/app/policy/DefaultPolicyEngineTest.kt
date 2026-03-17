package com.phoneclaw.app.policy

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.skills.StaticSkillRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPolicyEngineTest {
    private val registry = StaticSkillRegistry()
    private val policyEngine = DefaultPolicyEngine(registry)

    @Test
    fun allowsRegisteredSafeAction() {
        val decision = policyEngine.review(
            ActionSpec(
                actionId = "open_wifi_settings",
                skillId = "system.wifi_settings",
                taskId = "task-1",
                intentSummary = "Open Android Wi-Fi settings",
                params = emptyMap(),
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                executorType = "intent",
                expectedOutcome = "Android Wi-Fi settings becomes foreground",
            ),
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun rejectsUnregisteredAction() {
        val decision = policyEngine.review(
            ActionSpec(
                actionId = "open_notification_settings",
                skillId = "system.notification_settings",
                taskId = "task-2",
                intentSummary = "Open Android notification settings",
                params = emptyMap(),
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                executorType = "intent",
                expectedOutcome = "Android notification settings becomes foreground",
            ),
        )

        assertFalse(decision.allowed)
    }
}
