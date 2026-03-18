package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.CONTRACT_SCHEMA_VERSION
import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.gateway.ports.SkillRegistryPort
import com.phoneclaw.app.web.containsWebFetchIntent
import com.phoneclaw.app.web.extractFirstWebTarget

private const val ACTION_OPEN_WEB_URL = "open_web_url"
private const val ACTION_FETCH_WEB_PAGE_CONTENT = "fetch_web_page_content"

data class RegisteredSkillAction(
    val skill: SkillManifest,
    val action: SkillActionManifest,
    val intentAction: String,
) {
    val actionId: String
        get() = action.actionId

    fun toPlannedActionPayload(params: Map<String, String> = emptyMap()): PlannedActionPayload {
        return PlannedActionPayload(
            actionId = action.actionId,
            skillId = skill.skillId,
            intentSummary = action.description,
            params = params,
            riskLevel = action.riskLevel,
            requiresConfirmation = action.requiresConfirmation,
            executorType = action.executorType,
            expectedOutcome = action.expectedOutcome,
        )
    }
}

class StaticSkillRegistry(
    registeredActions: List<RegisteredSkillAction>,
) : SkillRegistryPort {
    private val registeredActions = validateRegisteredActions(registeredActions)
    private val enabledRegisteredActions = registeredActions.filter { it.skill.enabled && it.action.enabled }

    override fun allSkills(): List<SkillManifest> = enabledRegisteredActions.map { it.skill }.distinctBy { it.skillId }

    override fun allActions(): List<RegisteredSkillAction> = enabledRegisteredActions

    override fun findAction(actionId: String): RegisteredSkillAction? {
        return registeredActions.firstOrNull { it.action.actionId == actionId }
    }

    override fun matchUserMessage(userMessage: String): RegisteredSkillAction? {
        val normalized = userMessage.normalizeForMatch()
        val bestTextMatch = enabledRegisteredActions
            .map { action -> action to action.matchScore(normalized) }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first

        if (bestTextMatch != null) {
            return bestTextMatch
        }

        val webTarget = extractFirstWebTarget(userMessage)
        if (!webTarget.isNullOrBlank()) {
            return if (containsWebFetchIntent(userMessage)) {
                findEnabledAction(ACTION_FETCH_WEB_PAGE_CONTENT)
            } else {
                findEnabledAction(ACTION_OPEN_WEB_URL)
            }
        }

        return null
    }

    private fun findEnabledAction(actionId: String): RegisteredSkillAction? {
        return enabledRegisteredActions.firstOrNull { it.action.actionId == actionId }
    }
}

private fun validateRegisteredActions(registeredActions: List<RegisteredSkillAction>): List<RegisteredSkillAction> {
    val duplicateActionIds = registeredActions
        .groupBy { it.actionId }
        .filterValues { it.size > 1 }
        .keys

    require(duplicateActionIds.isEmpty()) {
        "Duplicate action ids detected in SkillRegistry: ${duplicateActionIds.sorted().joinToString(", ")}"
    }

    registeredActions.forEach { registeredAction ->
        require(registeredAction.skill.schemaVersion == CONTRACT_SCHEMA_VERSION) {
            "Skill ${registeredAction.skill.skillId} uses unsupported schema version " +
                "${registeredAction.skill.schemaVersion}. Expected $CONTRACT_SCHEMA_VERSION."
        }
        require(registeredAction.skill.actions.any { it.actionId == registeredAction.actionId }) {
            "Skill ${registeredAction.skill.skillId} is missing manifest metadata for action ${registeredAction.actionId}."
        }
    }

    return registeredActions
}

private fun RegisteredSkillAction.matchScore(normalizedMessage: String): Int {
    val phraseHits = action.exampleUtterances.count { example ->
        normalizedMessage.contains(example.normalizeForMatch())
    }
    val keywordHits = action.matchKeywords.count { keyword ->
        normalizedMessage.contains(keyword.normalizeForMatch())
    }
    val specificityBonus = if (phraseHits > 0 || keywordHits > 0) {
        (action.matchKeywords.maxOfOrNull { it.normalizeForMatch().length } ?: 0) +
            (action.exampleUtterances.maxOfOrNull { it.normalizeForMatch().length } ?: 0)
    } else {
        0
    }

    return (phraseHits * 100) + (keywordHits * 10) + specificityBonus
}

private fun String.normalizeForMatch(): String {
    return lowercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
}
