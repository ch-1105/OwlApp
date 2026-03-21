package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.CONTRACT_SCHEMA_VERSION
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.contracts.PageTransition
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.explorer.totalNodeCount
import com.phoneclaw.app.gateway.ports.ClickableElementSuggestion
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.model.StructuredJsonContentParser
import com.phoneclaw.app.skills.SkillActionBinding
import com.phoneclaw.app.skills.validateSkillPackage
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

private const val TASK_TYPE_SKILL_GENERATION = "skill_generation"
private const val MAX_MODEL_NODES_PER_PAGE = 24
private const val DEFAULT_SKILL_VERSION = "0.1.0"
private const val DEFAULT_SKILL_TYPE = "app"
private const val DEFAULT_OWNER = "learner"

private val skillDraftJson = Json {
    ignoreUnknownKeys = true
}

class AiSkillLearner(
    private val modelPort: ModelPort,
    private val allowCloud: Boolean,
    private val preferredProvider: String,
) : SkillLearner {
    override suspend fun generateSkillDraft(
        appPackage: String,
        appName: String,
        pages: List<PageLearningInput>,
    ): LearnedSkillDraft {
        require(pages.isNotEmpty()) {
            "At least one explored page is required to generate a skill draft."
        }

        val fallback = buildFallbackDraft(appPackage, appName, pages)
        val request = buildRequest(appPackage, appName, pages)
        val response = modelPort.infer(request)

        if (response.error != null) {
            return fallback
        }

        val jsonText = StructuredJsonContentParser.parseJsonTextOrNull(response.outputText)
            ?: return fallback

        return parseDraft(
            jsonText = jsonText,
            appPackage = appPackage,
            appName = appName,
            pages = pages,
            fallback = fallback,
        ) ?: fallback
    }

    private fun buildRequest(
        appPackage: String,
        appName: String,
        pages: List<PageLearningInput>,
    ): ModelRequest {
        return ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = "skill-generation-${UUID.randomUUID()}",
            taskType = TASK_TYPE_SKILL_GENERATION,
            inputMessages = buildList {
                add("App package: $appPackage")
                add("App name: $appName")
                add("Explored page count: ${pages.size}")
                pages.forEachIndexed { index, page ->
                    add("Page ${index + 1}:\n${page.toModelInput()}")
                }
            },
            allowCloud = allowCloud,
            preferredProvider = preferredProvider,
        )
    }
}

private fun parseDraft(
    jsonText: String,
    appPackage: String,
    appName: String,
    pages: List<PageLearningInput>,
    fallback: LearnedSkillDraft,
): LearnedSkillDraft? {
    val root = runCatching {
        skillDraftJson.parseToJsonElement(jsonText).jsonObject
    }.getOrNull() ?: return null

    val manifest = root.readManifest(
        appPackage = appPackage,
        appName = appName,
        fallback = fallback.manifest,
    )
    val pageGraph = root.readPageGraph(
        appPackage = appPackage,
        fallback = fallback.pageGraph,
    )
    val bindings = root.readBindings(
        actionIds = manifest.actions.map { it.actionId },
        fallback = fallback.bindings,
    )
    val evidence = buildEvidence(pages)

    return runCatching {
        validateSkillPackage(
            manifest = manifest,
            bindings = bindings,
            source = "model:${manifest.skillId}",
        )

        val draft = LearnedSkillDraft(
            manifest = manifest,
            pageGraph = pageGraph,
            bindings = bindings,
            evidence = evidence,
        )

        require(draft.isConsistent()) {
            "Generated skill draft is internally inconsistent."
        }
        require(draft.matchesObservedTransitions(pages)) {
            "Generated skill draft does not match observed exploration transitions."
        }

        draft
    }.getOrNull()
}

private fun LearnedSkillDraft.isConsistent(): Boolean {
    val pageIds = pageGraph.pages.map { it.pageId }.toSet()
    val actionIds = manifest.actions.map { it.actionId }.toSet()

    if (evidence.any { it.pageId !in pageIds }) {
        return false
    }
    if (pageGraph.pages.any { page -> page.availableActions.any { it !in actionIds } }) {
        return false
    }
    if (
        pageGraph.transitions.any { transition ->
            transition.fromPageId !in pageIds ||
                transition.toPageId !in pageIds ||
                transition.triggerActionId !in actionIds
        }
    ) {
        return false
    }

    return true
}

private fun LearnedSkillDraft.matchesObservedTransitions(pages: List<PageLearningInput>): Boolean {
    val observedTransitions = pages.mapNotNull { page ->
        page.arrivedBy?.toObservedTransitionKey()
    }.toSet()

    if (observedTransitions.isEmpty()) {
        return true
    }

    val draftTransitions = pageGraph.transitions.map { transition ->
        transition.toObservedTransitionKey()
    }.toSet()

    return draftTransitions == observedTransitions
}

private fun buildFallbackDraft(
    appPackage: String,
    appName: String,
    pages: List<PageLearningInput>,
): LearnedSkillDraft {
    val usedActionIds = mutableSetOf<String>()
    val generatedPages = pages.map { page ->
        page.toGeneratedPage(appName = appName, usedActionIds = usedActionIds)
    }
    val pageSpecs = mergePageSpecs(generatedPages.map { it.pageSpec })
    val transitions = buildFallbackTransitions(
        pages = pages,
        generatedPages = generatedPages,
    )
    val actions = generatedPages.flatMap { it.actions }
    val manifest = SkillManifest(
        schemaVersion = CONTRACT_SCHEMA_VERSION,
        skillId = buildSkillId(appPackage),
        skillVersion = DEFAULT_SKILL_VERSION,
        skillType = DEFAULT_SKILL_TYPE,
        displayName = buildDisplayName(appName, appPackage),
        owner = DEFAULT_OWNER,
        platform = "android",
        appPackage = appPackage,
        defaultRiskLevel = RiskLevel.GUARDED,
        enabled = false,
        actions = actions,
    )
    val bindings = actions.map { action ->
        SkillActionBinding(
            actionId = action.actionId,
            intentAction = "",
        )
    }

    validateSkillPackage(
        manifest = manifest,
        bindings = bindings,
        source = "fallback:${manifest.skillId}",
    )

    return LearnedSkillDraft(
        manifest = manifest,
        pageGraph = PageGraph(
            schemaVersion = CONTRACT_SCHEMA_VERSION,
            appPackage = appPackage,
            pages = pageSpecs,
            transitions = transitions,
        ),
        bindings = bindings,
        evidence = buildEvidence(pages),
    )
}

private fun PageLearningInput.toGeneratedPage(
    appName: String,
    usedActionIds: MutableSet<String>,
): GeneratedPage {
    val basePage = analysis.suggestedPageSpec
    val actions = buildGeneratedActions(
        appName = appName,
        pageName = basePage.pageName,
        pageId = basePage.pageId,
        clickableElements = analysis.clickableElements,
        fallbackActionIds = basePage.availableActions,
        usedActionIds = usedActionIds,
    )

    return GeneratedPage(
        pageSpec = basePage.copy(
            availableActions = actions.map { it.actionId },
        ),
        actions = actions,
    )
}

private fun buildGeneratedActions(
    appName: String,
    pageName: String,
    pageId: String,
    clickableElements: List<com.phoneclaw.app.gateway.ports.ClickableElementSuggestion>,
    fallbackActionIds: List<String>,
    usedActionIds: MutableSet<String>,
): List<SkillActionManifest> {
    if (clickableElements.isNotEmpty()) {
        return clickableElements.map { suggestion ->
            suggestion.toActionManifest(
                appName = appName,
                pageName = pageName,
                pageId = pageId,
                usedActionIds = usedActionIds,
            )
        }
    }

    return fallbackActionIds.map { fallbackActionId ->
        createFallbackActionManifest(
            rawActionId = fallbackActionId,
            appName = appName,
            pageName = pageName,
            pageId = pageId,
            usedActionIds = usedActionIds,
        )
    }
}

private fun com.phoneclaw.app.gateway.ports.ClickableElementSuggestion.toActionManifest(
    appName: String,
    pageName: String,
    pageId: String,
    usedActionIds: MutableSet<String>,
): SkillActionManifest {
    val actionId = usedActionIds.makeUnique(suggestedActionName.toActionId())
    val displayName = suggestedDescription.ifBlank { actionId.toDisplayName() }
    val description = "On $pageName in $appName, $suggestedDescription."
    val keywords = buildKeywords(
        appName,
        pageName,
        text,
        contentDescription,
        actionId,
        pageId,
    )

    return SkillActionManifest(
        actionId = actionId,
        displayName = displayName,
        description = description,
        executorType = "accessibility",
        riskLevel = RiskLevel.GUARDED,
        requiresConfirmation = true,
        expectedOutcome = "The app reacts to $displayName from $pageName.",
        enabled = true,
        exampleUtterances = listOf(displayName),
        matchKeywords = keywords,
    )
}

private fun createFallbackActionManifest(
    rawActionId: String,
    appName: String,
    pageName: String,
    pageId: String,
    usedActionIds: MutableSet<String>,
): SkillActionManifest {
    val actionId = usedActionIds.makeUnique(rawActionId.toActionId())
    val displayName = actionId.toDisplayName()

    return SkillActionManifest(
        actionId = actionId,
        displayName = displayName,
        description = "Perform $displayName on $pageName in $appName.",
        executorType = "accessibility",
        riskLevel = RiskLevel.GUARDED,
        requiresConfirmation = true,
        expectedOutcome = "$pageName changes after $displayName.",
        enabled = true,
        exampleUtterances = listOf(displayName),
        matchKeywords = buildKeywords(appName, pageName, actionId, pageId),
    )
}

private fun mergePageSpecs(pages: List<PageSpec>): List<PageSpec> {
    val merged = linkedMapOf<String, PageSpec>()

    pages.forEach { page ->
        val existing = merged[page.pageId]
        if (existing == null) {
            merged[page.pageId] = page
            return@forEach
        }

        merged[page.pageId] = existing.copy(
            matchRules = (existing.matchRules + page.matchRules)
                .distinctBy { rule -> "${rule.type}:${rule.value}" },
            availableActions = (existing.availableActions + page.availableActions).distinct(),
            evidenceFields = existing.evidenceFields + page.evidenceFields,
        )
    }

    return merged.values.toList()
}

private fun buildFallbackTransitions(
    pages: List<PageLearningInput>,
    generatedPages: List<GeneratedPage>,
): List<PageTransition> {
    val sourceInputById = pages.associateBy { it.analysis.suggestedPageSpec.pageId }
    val sourceGeneratedById = generatedPages.associateBy { it.pageSpec.pageId }

    return pages.mapNotNull { page ->
        val transition = page.arrivedBy ?: return@mapNotNull null
        val sourceInput = sourceInputById[transition.fromPageId] ?: return@mapNotNull null
        val sourceGenerated = sourceGeneratedById[transition.fromPageId] ?: return@mapNotNull null
        val triggerActionId = resolveTriggerActionId(
            sourceInput = sourceInput,
            sourceGenerated = sourceGenerated,
            transition = transition,
        ) ?: return@mapNotNull null

        PageTransition(
            fromPageId = transition.fromPageId,
            toPageId = transition.toPageId,
            triggerActionId = triggerActionId,
            triggerNodeDescription = transition.triggerNodeDescription,
        )
    }.distinctBy { transition ->
        "${transition.fromPageId}:${transition.toPageId}:${transition.triggerActionId}:${transition.triggerNodeDescription}"
    }
}

private fun resolveTriggerActionId(
    sourceInput: PageLearningInput,
    sourceGenerated: GeneratedPage,
    transition: ExplorationTransition,
): String? {
    val generatedActionIds = sourceGenerated.pageSpec.availableActions
    if (generatedActionIds.size == 1) {
        return generatedActionIds.single()
    }

    val normalizedTriggerValues = transition.toMatchValues()
    if (normalizedTriggerValues.isEmpty()) {
        return generatedActionIds.singleOrNull()
    }

    val matchedActionIds = sourceInput.analysis.clickableElements
        .zip(sourceGenerated.actions)
        .filter { (suggestion, _) -> suggestion.matchesTransition(normalizedTriggerValues) }
        .map { (_, action) -> action.actionId }
        .distinct()

    if (matchedActionIds.size == 1) {
        return matchedActionIds.single()
    }

    return generatedActionIds.singleOrNull()
}

private fun buildEvidence(pages: List<PageLearningInput>): List<LearningEvidence> {
    return pages.map { page ->
        LearningEvidence(
            pageId = page.analysis.suggestedPageSpec.pageId,
            snapshotJson = page.pageTree.toSnapshotJson(),
            screenshotPath = page.screenshotPath,
            screenshotBytes = page.screenshotBytes,
            arrivedBy = page.arrivedBy,
            capturedAt = page.capturedAt,
        )
    }
}

private fun ExplorationTransition?.toPromptLine(): String {
    if (this == null) {
        return "initial capture"
    }

    return "$fromPageId -> $toPageId via $triggerNodeDescription ($triggerNodeId)"
}

private fun ExplorationTransition.toMatchValues(): Set<String> {
    return listOf(triggerNodeId, triggerNodeDescription)
        .mapNotNull { value -> value.normalizeMatchValue() }
        .toSet()
}

private fun ExplorationTransition.toObservedTransitionKey(): ObservedTransitionKey {
    return ObservedTransitionKey(
        fromPageId = fromPageId,
        toPageId = toPageId,
        triggerNodeDescription = triggerNodeDescription.normalizeMatchValue().orEmpty(),
    )
}

private fun PageTransition.toObservedTransitionKey(): ObservedTransitionKey {
    return ObservedTransitionKey(
        fromPageId = fromPageId,
        toPageId = toPageId,
        triggerNodeDescription = triggerNodeDescription.normalizeMatchValue().orEmpty(),
    )
}

private fun ClickableElementSuggestion.matchesTransition(triggerValues: Set<String>): Boolean {
    val candidateValues = listOf(
        resourceId,
        text,
        contentDescription,
        suggestedActionName,
        suggestedDescription,
    ).mapNotNull { value -> value.normalizeMatchValue() }

    if (candidateValues.isEmpty()) {
        return false
    }

    return candidateValues.any { candidate ->
        triggerValues.any { trigger ->
            candidate == trigger ||
                candidate.contains(trigger) ||
                trigger.contains(candidate)
        }
    }
}

private fun String?.normalizeMatchValue(): String? {
    val normalized = this?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) {
        return null
    }

    return normalized
}

private fun PageLearningInput.toModelInput(): String {
    val pageSpec = analysis.suggestedPageSpec
    val clickableLines = analysis.clickableElements.map { element ->
        val label = element.text ?: element.contentDescription ?: element.resourceId ?: "unlabeled"
        "- ${element.suggestedActionName}: $label"
    }

    return buildString {
        appendLine("Page id: ${pageSpec.pageId}")
        appendLine("Page name: ${pageSpec.pageName}")
        appendLine("Activity: ${pageSpec.activityName ?: "unknown"}")
        appendLine("Match rules: ${pageSpec.matchRules.joinToString { "${it.type}=${it.value}" }}")
        appendLine("Available actions: ${pageSpec.availableActions.joinToString()}")
        appendLine("Clickable elements:")
        if (clickableLines.isEmpty()) {
            appendLine("- none")
        } else {
            clickableLines.forEach(::appendLine)
        }
        appendLine("Navigation hints: ${analysis.navigationHints.joinToString(" | ")}")
        appendLine("Arrived by: ${arrivedBy.toPromptLine()}")
        appendLine("Screenshot attached: ${screenshotBytes != null || !screenshotPath.isNullOrBlank()}")
        appendLine("Snapshot summary:")
        append(pageTree.toCompactSummary())
    }.trim()
}

private fun PageTreeSnapshot.toCompactSummary(): String {
    val nodes = flattenNodes().take(MAX_MODEL_NODES_PER_PAGE)

    return buildString {
        appendLine("Package: $packageName")
        appendLine("Activity: ${activityName ?: "unknown"}")
        appendLine("Node count: ${totalNodeCount()}")
        nodes.forEachIndexed { index, node ->
            appendLine("${index + 1}. ${node.toPromptLine()}")
        }
        if (totalNodeCount() > nodes.size) {
            appendLine("... truncated ${totalNodeCount() - nodes.size} more nodes")
        }
    }.trim()
}

private fun JsonObject.readManifest(
    appPackage: String,
    appName: String,
    fallback: SkillManifest,
): SkillManifest {
    val source = objectOrNull("manifest") ?: return fallback
    val defaultRiskLevel = source.riskLevelOrNull("default_risk_level") ?: fallback.defaultRiskLevel
    val actions = source.arrayOrEmpty("actions")
        .mapNotNull { element ->
            element.asObjectOrNull()?.toSkillActionManifestOrNull(
                defaultRiskLevel = defaultRiskLevel,
                appName = appName,
            )
        }
        .ifEmpty { fallback.actions }

    return SkillManifest(
        schemaVersion = source.stringOrNull("schema_version") ?: fallback.schemaVersion,
        skillId = source.stringOrNull("skill_id") ?: fallback.skillId,
        skillVersion = source.stringOrNull("skill_version") ?: fallback.skillVersion,
        skillType = source.stringOrNull("skill_type") ?: fallback.skillType,
        displayName = source.stringOrNull("display_name") ?: fallback.displayName,
        owner = source.stringOrNull("owner") ?: fallback.owner,
        platform = source.stringOrNull("platform") ?: fallback.platform,
        appPackage = source.stringOrNull("app_package") ?: appPackage,
        defaultRiskLevel = defaultRiskLevel,
        enabled = source.booleanOrNull("enabled") ?: fallback.enabled,
        actions = actions,
    )
}

private fun JsonObject.readPageGraph(
    appPackage: String,
    fallback: PageGraph,
): PageGraph {
    val source = objectOrNull("page_graph") ?: return fallback
    val fallbackPages = fallback.pages.associateBy { it.pageId }
    val pages = source.arrayOrEmpty("pages")
        .mapNotNull { element ->
            element.asObjectOrNull()?.toPageSpecOrNull(
                appPackage = appPackage,
                fallbackPages = fallbackPages,
            )
        }
        .ifEmpty { fallback.pages }
    val transitions = source.arrayOrEmpty("transitions")
        .mapNotNull { element -> element.asObjectOrNull()?.toPageTransitionOrNull() }

    return PageGraph(
        schemaVersion = source.stringOrNull("schema_version") ?: fallback.schemaVersion,
        appPackage = source.stringOrNull("app_package") ?: appPackage,
        pages = pages,
        transitions = transitions,
    )
}

private fun JsonObject.readBindings(
    actionIds: List<String>,
    fallback: List<SkillActionBinding>,
): List<SkillActionBinding> {
    val parsedBindings = arrayOrEmpty("action_bindings")
        .mapNotNull { element -> element.asObjectOrNull()?.toSkillActionBindingOrNull() }
    val sourceBindings = if (parsedBindings.isEmpty()) fallback else parsedBindings
    val bindingsById = sourceBindings.associateBy { it.actionId }.toMutableMap()

    actionIds.forEach { actionId ->
        bindingsById.putIfAbsent(actionId, SkillActionBinding(actionId = actionId, intentAction = ""))
    }

    return actionIds.map { actionId ->
        bindingsById.getValue(actionId)
    }
}

private fun JsonObject.toSkillActionManifestOrNull(
    defaultRiskLevel: RiskLevel,
    appName: String,
): SkillActionManifest? {
    val actionId = stringOrNull("action_id") ?: return null
    val displayName = stringOrNull("display_name") ?: actionId.toDisplayName()

    return SkillActionManifest(
        actionId = actionId.toActionId(),
        displayName = displayName,
        description = stringOrNull("description") ?: "Perform $displayName in $appName.",
        executorType = stringOrNull("executor_type") ?: "accessibility",
        riskLevel = riskLevelOrNull("risk_level") ?: defaultRiskLevel,
        requiresConfirmation = booleanOrNull("requires_confirmation") ?: true,
        expectedOutcome = stringOrNull("expected_outcome") ?: "The app reacts to $displayName.",
        enabled = booleanOrNull("enabled") ?: true,
        exampleUtterances = stringList("example_utterances").ifEmpty { listOf(displayName) },
        matchKeywords = stringList("match_keywords"),
    )
}

private fun JsonObject.toPageSpecOrNull(
    appPackage: String,
    fallbackPages: Map<String, PageSpec>,
): PageSpec? {
    val pageId = stringOrNull("page_id") ?: return null
    val fallback = fallbackPages[pageId]

    return PageSpec(
        schemaVersion = stringOrNull("schema_version") ?: fallback?.schemaVersion ?: CONTRACT_SCHEMA_VERSION,
        pageId = pageId,
        pageName = stringOrNull("page_name") ?: fallback?.pageName ?: pageId.toDisplayName(),
        appPackage = stringOrNull("app_package") ?: fallback?.appPackage ?: appPackage,
        activityName = stringOrNull("activity_name") ?: fallback?.activityName,
        matchRules = arrayOrEmpty("match_rules")
            .mapNotNull { element -> element.asObjectOrNull()?.toPageMatchRuleOrNull() }
            .ifEmpty { fallback?.matchRules ?: emptyList() },
        availableActions = stringList("available_actions")
            .ifEmpty { fallback?.availableActions ?: emptyList() },
        evidenceFields = objectOrNull("evidence_fields")?.toStringMap()
            ?.takeIf { it.isNotEmpty() }
            ?: fallback?.evidenceFields.orEmpty(),
    )
}

private fun JsonObject.toPageTransitionOrNull(): PageTransition? {
    val fromPageId = stringOrNull("from_page_id") ?: return null
    val toPageId = stringOrNull("to_page_id") ?: return null
    val triggerActionId = stringOrNull("trigger_action_id") ?: return null
    val triggerNodeDescription = stringOrNull("trigger_node_description") ?: return null

    return PageTransition(
        fromPageId = fromPageId,
        toPageId = toPageId,
        triggerActionId = triggerActionId,
        triggerNodeDescription = triggerNodeDescription,
    )
}

private fun JsonObject.toPageMatchRuleOrNull(): PageMatchRule? {
    val type = stringOrNull("type") ?: return null
    val value = stringOrNull("value") ?: return null
    return PageMatchRule(type = type, value = value)
}

private fun JsonObject.toSkillActionBindingOrNull(): SkillActionBinding? {
    val actionId = stringOrNull("action_id") ?: return null
    return SkillActionBinding(
        actionId = actionId,
        intentAction = stringOrNull("intent_action").orEmpty(),
    )
}

private fun PageTreeSnapshot.toSnapshotJson(): String {
    return buildString {
        append('{')
        append("\"package_name\":")
        append(packageName.toJsonString())
        append(',')
        append("\"activity_name\":")
        append(activityName.toJsonString())
        append(',')
        append("\"timestamp\":")
        append(timestamp)
        append(',')
        append("\"nodes\":[")
        nodes.forEachIndexed { index, node ->
            if (index > 0) {
                append(',')
            }
            append(node.toJsonText())
        }
        append("]}")
    }
}

private fun AccessibilityNodeSnapshot.toJsonText(): String {
    return buildString {
        append('{')
        append("\"node_id\":")
        append(nodeId.toJsonString())
        append(',')
        append("\"class_name\":")
        append(className.toJsonString())
        append(',')
        append("\"text\":")
        append(text.toJsonString())
        append(',')
        append("\"content_description\":")
        append(contentDescription.toJsonString())
        append(',')
        append("\"resource_id\":")
        append(resourceId.toJsonString())
        append(',')
        append("\"is_clickable\":")
        append(isClickable)
        append(',')
        append("\"is_scrollable\":")
        append(isScrollable)
        append(',')
        append("\"is_editable\":")
        append(isEditable)
        append(',')
        append("\"bounds\":")
        append(bounds.toJsonString())
        append(',')
        append("\"children\":[")
        children.forEachIndexed { index, child ->
            if (index > 0) {
                append(',')
            }
            append(child.toJsonText())
        }
        append("]}")
    }
}

private fun String?.toJsonString(): String {
    if (this == null) {
        return "null"
    }

    val escaped = buildString {
        this@toJsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    return "\"$escaped\""
}

private fun buildSkillId(appPackage: String): String {
    return "learned.${appPackage.replace('-', '_')}"
}

private fun buildDisplayName(appName: String, appPackage: String): String {
    if (appName.isNotBlank()) {
        return "$appName Learned Skill"
    }

    return "${appPackage.substringAfterLast('.').toDisplayName()} Learned Skill"
}

private fun buildKeywords(vararg values: String?): List<String> {
    val keywordPattern = Regex("[A-Za-z0-9]+|\\p{IsHan}+")

    return values.asList()
        .filterNotNull()
        .flatMap { value ->
            keywordPattern.findAll(value.lowercase())
                .map { it.value }
                .toList()
        }
        .filter { it.isNotBlank() }
        .distinct()
        .take(10)
}

private fun String.toActionId(): String {
    val normalized = lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    if (normalized.isNotBlank()) {
        return normalized
    }

    return "generated_action"
}

private fun String.toDisplayName(): String {
    val normalized = replace('_', ' ')
        .replace('-', ' ')
        .trim()
    if (normalized.isBlank()) {
        return "Generated Action"
    }

    return normalized.split(Regex("\\s+"))
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            }
        }
}

private fun MutableSet<String>.makeUnique(baseName: String): String {
    if (add(baseName)) {
        return baseName
    }

    var suffix = 2
    while (true) {
        val candidate = "${baseName}_$suffix"
        if (add(candidate)) {
            return candidate
        }
        suffix += 1
    }
}

private fun PageTreeSnapshot.flattenNodes(): List<AccessibilityNodeSnapshot> {
    val result = mutableListOf<AccessibilityNodeSnapshot>()
    nodes.forEach { node ->
        collectNodes(node, result)
    }
    return result
}

private fun collectNodes(
    node: AccessibilityNodeSnapshot,
    result: MutableList<AccessibilityNodeSnapshot>,
) {
    result += node
    node.children.forEach { child ->
        collectNodes(child, result)
    }
}

private fun AccessibilityNodeSnapshot.toPromptLine(): String {
    val parts = buildList {
        add("id=$nodeId")
        className?.takeIf { it.isNotBlank() }?.let { add("class=${it.substringAfterLast('.')}" ) }
        text?.takeIf { it.isNotBlank() }?.let { add("text=${it.cleanText()}" ) }
        contentDescription?.takeIf { it.isNotBlank() }?.let { add("desc=${it.cleanText()}" ) }
        resourceId?.takeIf { it.isNotBlank() }?.let { add("resource=$it") }
        add("clickable=$isClickable")
        add("scrollable=$isScrollable")
        add("editable=$isEditable")
    }

    return parts.joinToString(" | ")
}

private fun String.cleanText(): String {
    return trim()
        .replace(Regex("\\s+"), " ")
        .take(80)
}

private fun JsonObject.stringOrNull(key: String): String? {
    return runCatching {
        this[key]?.jsonPrimitive?.contentOrNull?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun JsonObject.booleanOrNull(key: String): Boolean? {
    return runCatching {
        this[key]?.jsonPrimitive?.booleanOrNull
    }.getOrNull()
}

private fun JsonObject.riskLevelOrNull(key: String): RiskLevel? {
    val rawValue = stringOrNull(key) ?: return null
    return RiskLevel.entries.firstOrNull { it.name == rawValue.uppercase() }
}

private fun JsonObject.stringList(key: String): List<String> {
    return arrayOrEmpty(key)
        .mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull?.trim() }.getOrNull()
        }
        .filter { it.isNotBlank() }
}

private fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> {
    return runCatching {
        this[key]?.jsonArray?.toList()
    }.getOrNull().orEmpty()
}

private fun JsonObject.objectOrNull(key: String): JsonObject? {
    return this[key].asObjectOrNull()
}

private fun JsonElement?.asObjectOrNull(): JsonObject? {
    return runCatching { this?.jsonObject }.getOrNull()
}

private fun JsonObject.toStringMap(): Map<String, String> {
    return entries.mapNotNull { (key, value) ->
        val text = runCatching {
            (value as? JsonPrimitive)?.contentOrNull?.trim()
        }.getOrNull() ?: return@mapNotNull null
        key to text
    }.toMap()
}

private data class GeneratedPage(
    val pageSpec: PageSpec,
    val actions: List<SkillActionManifest>,
)

private data class ObservedTransitionKey(
    val fromPageId: String,
    val toPageId: String,
    val triggerNodeDescription: String,
)

