package com.phoneclaw.app.policy

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.RiskLevel

data class PolicyDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String? = null,
)

interface PolicyEngine {
    fun review(actionSpec: ActionSpec): PolicyDecision
}

class DefaultPolicyEngine : PolicyEngine {
    override fun review(actionSpec: ActionSpec): PolicyDecision {
        if (actionSpec.actionId != "open_system_settings") {
            return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Action is not registered in the current milestone.",
            )
        }

        if (actionSpec.riskLevel != RiskLevel.SAFE) {
            return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Only safe actions are enabled in the current milestone.",
            )
        }

        return PolicyDecision(
            allowed = true,
            requiresConfirmation = false,
        )
    }
}

