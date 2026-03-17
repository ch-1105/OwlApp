package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest

private const val SCHEMA_VERSION = "v1alpha1"
private const val SETTINGS_PACKAGE = "com.android.settings"
private const val INTENT_SETTINGS = "android.settings.SETTINGS"
private const val INTENT_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
private const val INTENT_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"

data class RegisteredSkillAction(
    val skill: SkillManifest,
    val action: SkillActionManifest,
    val intentAction: String,
) {
    val actionId: String
        get() = action.actionId

    fun toPlannedActionPayload(): PlannedActionPayload {
        return PlannedActionPayload(
            actionId = action.actionId,
            skillId = skill.skillId,
            intentSummary = action.description,
            riskLevel = action.riskLevel,
            requiresConfirmation = action.requiresConfirmation,
            executorType = action.executorType,
            expectedOutcome = action.expectedOutcome,
        )
    }
}

interface SkillRegistry {
    fun allSkills(): List<SkillManifest>
    fun allActions(): List<RegisteredSkillAction>
    fun findAction(actionId: String): RegisteredSkillAction?
    fun matchUserMessage(userMessage: String): RegisteredSkillAction?
}

class StaticSkillRegistry : SkillRegistry {
    private val registeredActions = listOf(
        registeredSystemAction(
            skillId = "system.wifi_settings",
            skillDisplayName = "Wi-Fi Settings",
            action = SkillActionManifest(
                actionId = "open_wifi_settings",
                displayName = "Open Wi-Fi Settings",
                description = "Open Android Wi-Fi settings",
                executorType = "intent",
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                expectedOutcome = "Android Wi-Fi settings becomes foreground",
                exampleUtterances = listOf(
                    "open wifi settings",
                    "open wi-fi settings",
                    "open wlan settings",
                    "打开wifi设置",
                    "打开无线网络设置",
                    "带我去wlan设置",
                ),
                matchKeywords = listOf(
                    "wifi settings",
                    "wi-fi settings",
                    "wifi",
                    "wi-fi",
                    "wlan",
                    "无线网络",
                    "无线网",
                ),
            ),
            intentAction = INTENT_WIFI_SETTINGS,
        ),
        registeredSystemAction(
            skillId = "system.bluetooth_settings",
            skillDisplayName = "Bluetooth Settings",
            action = SkillActionManifest(
                actionId = "open_bluetooth_settings",
                displayName = "Open Bluetooth Settings",
                description = "Open Android Bluetooth settings",
                executorType = "intent",
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                expectedOutcome = "Android Bluetooth settings becomes foreground",
                exampleUtterances = listOf(
                    "open bluetooth settings",
                    "take me to bluetooth settings",
                    "打开蓝牙设置",
                    "带我去蓝牙设置",
                ),
                matchKeywords = listOf(
                    "bluetooth settings",
                    "bluetooth",
                    "蓝牙设置",
                    "蓝牙",
                ),
            ),
            intentAction = INTENT_BLUETOOTH_SETTINGS,
        ),
        registeredSystemAction(
            skillId = "system.settings",
            skillDisplayName = "System Settings",
            action = SkillActionManifest(
                actionId = "open_system_settings",
                displayName = "Open System Settings",
                description = "Open Android system settings",
                executorType = "intent",
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                expectedOutcome = "Android settings activity is foregrounded",
                exampleUtterances = listOf(
                    "open system settings",
                    "take me to settings",
                    "打开系统设置",
                    "带我去设置页",
                    "我想进系统设置",
                ),
                matchKeywords = listOf(
                    "system settings",
                    "system setting",
                    "settings",
                    "设置",
                ),
            ),
            intentAction = INTENT_SETTINGS,
        ),
    )

    override fun allSkills(): List<SkillManifest> = registeredActions.map { it.skill }

    override fun allActions(): List<RegisteredSkillAction> = registeredActions

    override fun findAction(actionId: String): RegisteredSkillAction? {
        return registeredActions.firstOrNull { it.action.actionId == actionId }
    }

    override fun matchUserMessage(userMessage: String): RegisteredSkillAction? {
        val normalized = userMessage.normalizeForMatch()
        return registeredActions
            .map { action -> action to action.matchScore(normalized) }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }
}

private fun registeredSystemAction(
    skillId: String,
    skillDisplayName: String,
    action: SkillActionManifest,
    intentAction: String,
): RegisteredSkillAction {
    val manifest = SkillManifest(
        schemaVersion = SCHEMA_VERSION,
        skillId = skillId,
        skillVersion = "0.1.0",
        skillType = "system",
        displayName = skillDisplayName,
        owner = "core",
        platform = "android",
        appPackage = SETTINGS_PACKAGE,
        defaultRiskLevel = action.riskLevel,
        enabled = true,
        actions = listOf(action),
    )

    return RegisteredSkillAction(
        skill = manifest,
        action = action,
        intentAction = intentAction,
    )
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
