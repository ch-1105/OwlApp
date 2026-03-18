package com.phoneclaw.app.policy

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.gateway.ports.PolicyDecision
import com.phoneclaw.app.gateway.ports.PolicyPort
import com.phoneclaw.app.gateway.ports.SkillRegistryPort
import com.phoneclaw.app.web.normalizeWebUrl

class DefaultPolicyEngine(
    private val skillRegistry: SkillRegistryPort,
) : PolicyPort {
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

        if (actionSpec.actionId in urlRequiredActions) {
            val url = actionSpec.params["url"]
            if (url.isNullOrBlank()) {
                return PolicyDecision(
                    allowed = false,
                    requiresConfirmation = false,
                    reason = "Browser actions require a non-empty url parameter.",
                )
            }

            if (normalizeWebUrl(url) == null) {
                return PolicyDecision(
                    allowed = false,
                    requiresConfirmation = false,
                    reason = "Browser actions only support valid http or https URLs.",
                )
            }
        }

        return PolicyDecision(
            allowed = true,
            requiresConfirmation = registeredAction.action.requiresConfirmation || actionSpec.requiresConfirmation,
        )
    }
}

private val urlRequiredActions = setOf(
    "open_web_url",
    "fetch_web_page_content",
)

