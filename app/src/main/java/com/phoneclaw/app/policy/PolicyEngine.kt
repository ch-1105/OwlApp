package com.phoneclaw.app.policy

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.skills.SkillRegistry

data class PolicyDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String? = null,
)

interface PolicyEngine {
    fun review(actionSpec: ActionSpec): PolicyDecision
}

class DefaultPolicyEngine(
    private val skillRegistry: SkillRegistry,
) : PolicyEngine {
    override fun review(actionSpec: ActionSpec): PolicyDecision {
        val registeredAction = skillRegistry.findAction(actionSpec.actionId)
            ?: return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Action is not registered in the current milestone.",
            )

        if (!registeredAction.skill.enabled || !registeredAction.action.enabled) {
            return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Requested skill is currently disabled.",
            )
        }

        if (actionSpec.skillId != registeredAction.skill.skillId) {
            return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Action and skill identifiers do not match the registered manifest.",
            )
        }

        if (actionSpec.executorType != registeredAction.action.executorType) {
            return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Planner returned an executor type that is not registered for this action.",
            )
        }

        if (actionSpec.riskLevel != registeredAction.action.riskLevel) {
            return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Planner returned a risk level that does not match the registered manifest.",
            )
        }

        if (registeredAction.action.riskLevel != RiskLevel.SAFE) {
            return PolicyDecision(
                allowed = false,
                requiresConfirmation = false,
                reason = "Only safe actions are enabled in the current milestone.",
            )
        }

        return PolicyDecision(
            allowed = true,
            requiresConfirmation = registeredAction.action.requiresConfirmation || actionSpec.requiresConfirmation,
        )
    }
}
