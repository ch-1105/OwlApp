package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.web.containsWebFetchIntent
import com.phoneclaw.app.web.extractFirstWebTarget

private const val SCHEMA_VERSION = "v1alpha1"
private const val SETTINGS_PACKAGE = "com.android.settings"
private const val INTENT_SETTINGS = "android.settings.SETTINGS"
private const val INTENT_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
private const val INTENT_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
private const val INTENT_NOTIFICATION_SETTINGS = "android.settings.NOTIFICATION_SETTINGS"
private const val INTENT_VIEW = "android.intent.action.VIEW"

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
            skillId = "system.notification_settings",
            skillDisplayName = "Notification Settings",
            action = SkillActionManifest(
                actionId = "open_notification_settings",
                displayName = "Open Notification Settings",
                description = "Open Android notification settings",
                executorType = "intent",
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                expectedOutcome = "Android notification settings becomes foreground",
                exampleUtterances = listOf(
                    "open notification settings",
                    "take me to notification settings",
                    "打开通知设置",
                    "带我去通知设置",
                ),
                matchKeywords = listOf(
                    "notification settings",
                    "notification",
                    "通知设置",
                    "通知",
                ),
            ),
            intentAction = INTENT_NOTIFICATION_SETTINGS,
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
        registeredBrowserAction(
            action = SkillActionManifest(
                actionId = ACTION_OPEN_WEB_URL,
                displayName = "Open Web URL",
                description = "Open a web page in the default browser",
                executorType = "browser_intent",
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                expectedOutcome = "The target URL becomes foreground in the default browser",
                exampleUtterances = listOf(
                    "open https://openai.com",
                    "在浏览器打开 https://example.com",
                    "打开网页 https://news.ycombinator.com",
                ),
                matchKeywords = listOf(
                    "openwebsite",
                    "openwebpage",
                    "openurl",
                    "打开网页",
                    "打开网站",
                    "浏览器打开",
                ),
            ),
            intentAction = INTENT_VIEW,
        ),
        registeredBrowserAction(
            action = SkillActionManifest(
                actionId = ACTION_FETCH_WEB_PAGE_CONTENT,
                displayName = "Fetch Web Page Content",
                description = "Fetch the readable text content of a web page",
                executorType = "web_fetch",
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                expectedOutcome = "PhoneClaw returns the page title and readable text content",
                exampleUtterances = listOf(
                    "fetch https://example.com content",
                    "读取 https://openai.com 的网页内容",
                    "帮我获取 https://openai.com 的内容",
                ),
                matchKeywords = listOf(
                    "fetchwebpage",
                    "readwebpage",
                    "webpagecontent",
                    "网页内容",
                    "读取网页",
                    "获取网页内容",
                    "抓取网页",
                    "网页正文",
                ),
            ),
            intentAction = "",
        ),
    )

    override fun allSkills(): List<SkillManifest> = registeredActions.map { it.skill }.distinctBy { it.skillId }

    override fun allActions(): List<RegisteredSkillAction> = registeredActions

    override fun findAction(actionId: String): RegisteredSkillAction? {
        return registeredActions.firstOrNull { it.action.actionId == actionId }
    }

    override fun matchUserMessage(userMessage: String): RegisteredSkillAction? {
        val normalized = userMessage.normalizeForMatch()
        val bestTextMatch = registeredActions
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
                findAction(ACTION_FETCH_WEB_PAGE_CONTENT)
            } else {
                findAction(ACTION_OPEN_WEB_URL)
            }
        }

        return null
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

private fun registeredBrowserAction(
    action: SkillActionManifest,
    intentAction: String,
): RegisteredSkillAction {
    val manifest = SkillManifest(
        schemaVersion = SCHEMA_VERSION,
        skillId = "browser.web",
        skillVersion = "0.1.0",
        skillType = "browser",
        displayName = "Browser Tools",
        owner = "core",
        platform = "android",
        appPackage = null,
        defaultRiskLevel = RiskLevel.SAFE,
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


