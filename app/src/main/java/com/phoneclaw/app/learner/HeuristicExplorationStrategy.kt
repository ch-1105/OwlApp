package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.flattenNodes

class HeuristicExplorationStrategy : ExplorationStrategy {
    override suspend fun selectCandidates(
        context: ExplorationContext,
        safeCandidates: List<ClickCandidate>,
    ): List<ClickCandidate> {
        if (safeCandidates.size <= 1) return safeCandidates

        val nodeMap = context.currentPage.flattenNodes().associateBy { it.nodeId }

        return safeCandidates.sortedByDescending { candidate ->
            val node = nodeMap[candidate.nodeId]
            if (node != null) candidateScore(node) else 0
        }
    }
}

internal fun candidateScore(node: AccessibilityNodeSnapshot): Int {
    val resourceId = node.resourceId?.lowercase().orEmpty()
    return when {
        resourceId.contains("tab") || resourceId.contains("navigation") -> 4
        resourceId.contains("menu") || resourceId.contains("list_item") -> 3
        node.text != null && node.contentDescription != null -> 2
        node.text != null || node.contentDescription != null -> 1
        else -> 0
    }
}
