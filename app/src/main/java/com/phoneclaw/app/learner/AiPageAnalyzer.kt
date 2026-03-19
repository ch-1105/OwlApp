package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.explorer.totalNodeCount
import com.phoneclaw.app.gateway.ports.ClickableElementSuggestion
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.gateway.ports.PageAnalysisPort
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import com.phoneclaw.app.model.StructuredJsonContentParser
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TASK_TYPE_PAGE_ANALYSIS = "page_analysis"
private const val MAX_SERIALIZED_NODES = 60
private const val MAX_CLICKABLE_SUGGESTIONS = 8

private val pageAnalysisJson = Json {
    ignoreUnknownKeys = true
}

class AiPageAnalyzer(
    private val modelPort: ModelPort,
    private val allowCloud: Boolean,
    private val preferredProvider: String,
) : PageAnalysisPort {
    override suspend fun analyzePage(
        appPackage: String,
        pageTree: PageTreeSnapshot,
        screenshot: ByteArray?,
    ): PageAnalysisResult {
        val fallback = buildFallbackAnalysis(appPackage, pageTree)
        val request = buildRequest(appPackage, pageTree, screenshot)
        val response = modelPort.infer(request)

        if (response.error != null) {
            return fallback
        }

        val jsonText = StructuredJsonContentParser.parseJsonTextOrNull(response.outputText)
            ?: return fallback

        return parseAnalysis(jsonText, appPackage, pageTree, fallback) ?: fallback
    }

    private fun buildRequest(
        appPackage: String,
        pageTree: PageTreeSnapshot,
        screenshot: ByteArray?,
    ): ModelRequest {
        return ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = "page-analysis-${pageTree.timestamp}",
            taskType = TASK_TYPE_PAGE_ANALYSIS,
            inputMessages = buildList {
                add("App package: $appPackage")
                add("Page tree snapshot:\n${pageTree.toModelInput()}")
                if (screenshot != null) {
                    add("Screenshot metadata: present (${screenshot.size} bytes)")
                }
            },
            allowCloud = allowCloud,
            preferredProvider = preferredProvider,
        )
    }
}

private fun parseAnalysis(
    jsonText: String,
    appPackage: String,
    pageTree: PageTreeSnapshot,
    fallback: PageAnalysisResult,
): PageAnalysisResult? {
    val root = runCatching {
        pageAnalysisJson.parseToJsonElement(jsonText).jsonObject
    }.getOrNull() ?: return null

    val clickableElements = root.readClickableElements().ifEmpty {
        fallback.clickableElements
    }
    val suggestedPageSpec = root.readSuggestedPageSpec(
        appPackage = appPackage,
        pageTree = pageTree,
        fallback = fallback.suggestedPageSpec,
        clickableElements = clickableElements,
    )
    val navigationHints = root.readNavigationHints().ifEmpty {
        fallback.navigationHints
    }

    return PageAnalysisResult(
        suggestedPageSpec = suggestedPageSpec,
        clickableElements = clickableElements,
        navigationHints = navigationHints,
    )
}

private fun buildFallbackAnalysis(
    appPackage: String,
    pageTree: PageTreeSnapshot,
): PageAnalysisResult {
    val clickableElements = pageTree.buildClickableSuggestions()
    val availableActions = clickableElements.map { it.suggestedActionName }.distinct()
    val pageName = pageTree.bestPageName()
    val pageId = pageTree.bestPageId()
    val matchRules = buildList {
        add(PageMatchRule(type = "package_name", value = pageTree.packageName.ifBlank { appPackage }))
        pageTree.activityName?.takeIf { it.isNotBlank() }?.let { activityName ->
            add(PageMatchRule(type = "activity_name", value = activityName))
        }
        pageTree.bestVisibleLabel()?.let { visibleLabel ->
            add(PageMatchRule(type = "text_contains", value = visibleLabel))
        }
    }

    return PageAnalysisResult(
        suggestedPageSpec = PageSpec(
            pageId = pageId,
            pageName = pageName,
            appPackage = pageTree.packageName.ifBlank { appPackage },
            activityName = pageTree.activityName,
            matchRules = matchRules,
            availableActions = availableActions,
            evidenceFields = mapOf(
                "captured_at" to pageTree.timestamp.toString(),
                "node_count" to pageTree.totalNodeCount().toString(),
            ),
        ),
        clickableElements = clickableElements,
        navigationHints = buildNavigationHints(pageTree, clickableElements),
    )
}

private fun JsonObject.readSuggestedPageSpec(
    appPackage: String,
    pageTree: PageTreeSnapshot,
    fallback: PageSpec,
    clickableElements: List<ClickableElementSuggestion>,
): PageSpec {
    val source = objectOrNull("suggested_page_spec") ?: return fallback.copy(
        availableActions = clickableElements.map { it.suggestedActionName }.distinct().ifEmpty { fallback.availableActions },
    )

    val availableActions = source.stringList("available_actions")
        .ifEmpty { clickableElements.map { it.suggestedActionName }.distinct() }
        .ifEmpty { fallback.availableActions }
    val matchRules = source.arrayOrEmpty("match_rules")
        .mapNotNull { element -> element.asObjectOrNull()?.toPageMatchRuleOrNull() }
        .ifEmpty { fallback.matchRules }
    val evidenceFields = source.objectOrNull("evidence_fields")?.toStringMap().orEmpty()
        .ifEmpty { fallback.evidenceFields }

    return PageSpec(
        pageId = source.stringOrNull("page_id") ?: fallback.pageId,
        pageName = source.stringOrNull("page_name") ?: fallback.pageName,
        appPackage = pageTree.packageName.ifBlank { appPackage },
        activityName = source.stringOrNull("activity_name") ?: pageTree.activityName,
        matchRules = matchRules,
        availableActions = availableActions,
        evidenceFields = evidenceFields,
    )
}

private fun JsonObject.readClickableElements(): List<ClickableElementSuggestion> {
    return arrayOrEmpty("clickable_elements")
        .mapNotNull { element -> element.asObjectOrNull()?.toClickableElementSuggestionOrNull() }
}

private fun JsonObject.readNavigationHints(): List<String> {
    return stringList("navigation_hints")
}

private fun JsonObject.toClickableElementSuggestionOrNull(): ClickableElementSuggestion? {
    val suggestedActionName = stringOrNull("suggested_action_name") ?: return null
    val suggestedDescription = stringOrNull("suggested_description") ?: return null

    return ClickableElementSuggestion(
        resourceId = stringOrNull("resource_id"),
        text = stringOrNull("text"),
        contentDescription = stringOrNull("content_description"),
        suggestedActionName = suggestedActionName,
        suggestedDescription = suggestedDescription,
    )
}

private fun JsonObject.toPageMatchRuleOrNull(): PageMatchRule? {
    val type = stringOrNull("type") ?: return null
    val value = stringOrNull("value") ?: return null
    return PageMatchRule(type = type, value = value)
}

private fun PageTreeSnapshot.toModelInput(): String {
    val flattenedNodes = flattenNodes().take(MAX_SERIALIZED_NODES)

    return buildString {
        appendLine("Package: $packageName")
        appendLine("Activity: ${activityName ?: "unknown"}")
        appendLine("Captured at: $timestamp")
        appendLine("Node count: ${totalNodeCount()}")
        appendLine("Visible nodes:")

        flattenedNodes.forEachIndexed { index, item ->
            appendLine("${index + 1}. ${item.toPromptLine()}")
        }

        if (totalNodeCount() > flattenedNodes.size) {
            appendLine("... truncated ${totalNodeCount() - flattenedNodes.size} additional nodes")
        }
    }.trim()
}

private fun PageTreeSnapshot.buildClickableSuggestions(): List<ClickableElementSuggestion> {
    val usedNames = mutableSetOf<String>()

    return flattenNodes()
        .map { it.node }
        .filter { it.isClickable }
        .take(MAX_CLICKABLE_SUGGESTIONS)
        .mapIndexed { index, node ->
            node.toClickableSuggestion(index, usedNames)
        }
}

private fun PageTreeSnapshot.bestPageId(): String {
    val activitySegment = activityName?.substringAfterLast('.')?.toActionSlug()
    if (!activitySegment.isNullOrBlank()) {
        return activitySegment
    }

    val resourceSegment = flattenNodes()
        .mapNotNull { item -> item.node.resourceId?.substringAfterLast('/')?.toActionSlug() }
        .firstOrNull { it.isNotBlank() }
    if (!resourceSegment.isNullOrBlank()) {
        return resourceSegment
    }

    return "page_${timestamp}"
}

private fun PageTreeSnapshot.bestPageName(): String {
    bestVisibleLabel()?.let { return it }
    activityName?.substringAfterLast('.')?.takeIf { it.isNotBlank() }?.let { return it }
    return packageName.substringAfterLast('.')
}

private fun PageTreeSnapshot.bestVisibleLabel(): String? {
    return flattenNodes()
        .mapNotNull { item -> item.node.visibleLabel() }
        .firstOrNull()
}

private fun AccessibilityNodeSnapshot.visibleLabel(): String? {
    text?.cleanText()?.takeIf { it.isNotBlank() }?.let { return it }
    contentDescription?.cleanText()?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private fun buildNavigationHints(
    pageTree: PageTreeSnapshot,
    clickableElements: List<ClickableElementSuggestion>,
): List<String> {
    val hints = mutableListOf<String>()

    if (clickableElements.isNotEmpty()) {
        hints += "Start with the suggested clickable elements before exploring deeper flows."
    }
    if (pageTree.flattenNodes().any { it.node.isScrollable }) {
        hints += "This page contains scrollable content. Capture another snapshot after scrolling."
    }
    if (pageTree.flattenNodes().any { it.node.isEditable }) {
        hints += "This page contains editable fields. Record expected input formats before creating actions."
    }
    if (hints.isNotEmpty()) {
        return hints
    }

    return listOf("No obvious interactive element was found in the current snapshot.")
}

private fun AccessibilityNodeSnapshot.toClickableSuggestion(
    index: Int,
    usedNames: MutableSet<String>,
): ClickableElementSuggestion {
    val label = bestLabel()
    val baseName = label?.toActionSlug()
        ?.takeIf { it.isNotBlank() }
        ?.let { "tap_$it" }
        ?: resourceId?.substringAfterLast('/')
            ?.toActionSlug()
            ?.takeIf { it.isNotBlank() }
            ?.let { "tap_$it" }
        ?: "tap_item_${index + 1}"
    val suggestedActionName = usedNames.makeUnique(baseName)
    val suggestedDescription = "Tap ${label ?: fallbackNodeLabel()}"

    return ClickableElementSuggestion(
        resourceId = resourceId,
        text = text?.cleanText(),
        contentDescription = contentDescription?.cleanText(),
        suggestedActionName = suggestedActionName,
        suggestedDescription = suggestedDescription,
    )
}

private fun AccessibilityNodeSnapshot.bestLabel(): String? {
    text?.cleanText()?.takeIf { it.isNotBlank() }?.let { return it }
    contentDescription?.cleanText()?.takeIf { it.isNotBlank() }?.let { return it }
    resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private fun AccessibilityNodeSnapshot.fallbackNodeLabel(): String {
    className?.substringAfterLast('.')?.takeIf { it.isNotBlank() }?.let { return it }
    return "element"
}

private fun PageTreeSnapshot.flattenNodes(): List<FlattenedNode> {
    val result = mutableListOf<FlattenedNode>()
    nodes.forEach { node ->
        collectNode(node = node, depth = 0, result = result)
    }
    return result
}

private fun collectNode(
    node: AccessibilityNodeSnapshot,
    depth: Int,
    result: MutableList<FlattenedNode>,
) {
    result += FlattenedNode(node = node, depth = depth)
    node.children.forEach { child ->
        collectNode(node = child, depth = depth + 1, result = result)
    }
}

private fun FlattenedNode.toPromptLine(): String {
    val flags = buildList {
        if (node.isClickable) add("clickable")
        if (node.isScrollable) add("scrollable")
        if (node.isEditable) add("editable")
    }.ifEmpty { listOf("static") }

    val indent = "  ".repeat(depth)
    val parts = buildList {
        add("id=${node.nodeId}")
        node.className?.takeIf { it.isNotBlank() }?.let { add("class=${it.substringAfterLast('.')}" ) }
        node.text?.cleanText()?.takeIf { it.isNotBlank() }?.let { add("text=$it") }
        node.contentDescription?.cleanText()?.takeIf { it.isNotBlank() }?.let { add("desc=$it") }
        node.resourceId?.takeIf { it.isNotBlank() }?.let { add("resource=$it") }
        add("flags=${flags.joinToString(",")}")
        add("bounds=${node.bounds}")
    }

    return indent + parts.joinToString(" | ")
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

private fun String.toActionSlug(): String {
    val cleaned = lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    return cleaned.take(40)
}

private fun String.cleanText(): String {
    return trim()
        .replace(Regex("\\s+"), " ")
        .take(80)
}

private fun JsonObject.stringOrNull(key: String): String? {
    return runCatching {
        this[key]?.jsonPrimitive?.contentOrNull?.cleanText()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun JsonObject.stringList(key: String): List<String> {
    return arrayOrEmpty(key)
        .mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull?.cleanText() }.getOrNull()
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
        val text = runCatching { value.jsonPrimitive.contentOrNull?.cleanText() }.getOrNull()
            ?: return@mapNotNull null
        key to text
    }.toMap()
}

private data class FlattenedNode(
    val node: AccessibilityNodeSnapshot,
    val depth: Int,
)

