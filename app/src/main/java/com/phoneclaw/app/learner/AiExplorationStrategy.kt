package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.model.StructuredJsonContentParser
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val TASK_TYPE_EXPLORATION = "exploration_strategy"

private val explorationJson = Json { ignoreUnknownKeys = true }

class AiExplorationStrategy(
    private val modelPort: ModelPort,
    private val allowCloud: Boolean,
    private val preferredProvider: String,
) : ExplorationStrategy {

    override suspend fun selectCandidates(
        context: ExplorationContext,
        safeCandidates: List<ClickCandidate>,
    ): List<ClickCandidate> {
        if (safeCandidates.size <= 1) return safeCandidates

        val request = buildRequest(context, safeCandidates)
        val response = runCatching { modelPort.infer(request) }.getOrNull()
            ?: return safeCandidates

        if (response.error != null) return safeCandidates

        val jsonText = StructuredJsonContentParser.parseJsonTextOrNull(response.outputText)
            ?: return safeCandidates

        val indices = parseOrderedIndices(jsonText, safeCandidates.size)
        if (indices.isEmpty()) return safeCandidates

        return reorder(safeCandidates, indices)
    }

    private fun buildRequest(
        context: ExplorationContext,
        candidates: List<ClickCandidate>,
    ): ModelRequest {
        val prompt = buildPrompt(context, candidates)

        return ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = "exploration-${System.currentTimeMillis()}",
            taskType = TASK_TYPE_EXPLORATION,
            inputMessages = listOf(prompt),
            allowCloud = allowCloud,
            preferredProvider = preferredProvider,
        )
    }
}

internal fun buildPrompt(
    context: ExplorationContext,
    candidates: List<ClickCandidate>,
): String {
    return buildString {
        appendLine("You are exploring an Android app to learn its navigation structure.")
        appendLine()
        appendLine("Current page:")
        appendLine("  Package: ${context.currentPage.packageName}")
        appendLine("  Activity: ${context.currentPage.activityName ?: "unknown"}")
        appendLine("  Page name: ${context.currentAnalysis.suggestedPageSpec.pageName}")
        appendLine()
        if (context.visitedPageNames.isNotEmpty()) {
            appendLine("Already visited pages: ${context.visitedPageNames.joinToString(", ")}")
            appendLine()
        }
        appendLine("Steps remaining: ${context.stepsRemaining}")
        appendLine("Depth remaining: ${context.depthRemaining}")
        appendLine()
        appendLine("Clickable elements on this page:")
        candidates.forEachIndexed { index, candidate ->
            appendLine("  ${index + 1}. [${candidate.nodeId}] ${candidate.description}")
        }
        appendLine()
        appendLine("Return the element indices (1-based) in the order you recommend clicking.")
        appendLine("Prioritize elements that:")
        appendLine("- Lead to new, unexplored sections of the app")
        appendLine("- Are navigation elements (tabs, menus, sidebar items)")
        appendLine("- Have descriptive labels suggesting important app features")
        appendLine("Deprioritize elements that seem to lead to already-visited pages.")
        appendLine()
        appendLine("Response format (JSON only): {\"indices\": [2, 1, 5, 3]}")
    }.trim()
}

internal fun parseOrderedIndices(jsonText: String, candidateCount: Int): List<Int> {
    val root = runCatching {
        explorationJson.parseToJsonElement(jsonText).jsonObject
    }.getOrNull() ?: return emptyList()

    return root.arrayOrEmpty("indices")
        .mapNotNull { element ->
            runCatching { element.intOrNull() }.getOrNull()
        }
        .filter { it in 1..candidateCount }
        .map { it - 1 }
        .distinct()
}

internal fun reorder(
    candidates: List<ClickCandidate>,
    zeroBasedIndices: List<Int>,
): List<ClickCandidate> {
    val reordered = mutableListOf<ClickCandidate>()
    val used = mutableSetOf<Int>()

    for (index in zeroBasedIndices) {
        if (index in candidates.indices && index !in used) {
            reordered += candidates[index]
            used += index
        }
    }

    // Append remaining candidates not selected by the model
    for (i in candidates.indices) {
        if (i !in used) {
            reordered += candidates[i]
        }
    }

    return reordered
}
