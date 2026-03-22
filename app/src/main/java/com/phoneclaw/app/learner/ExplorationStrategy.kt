package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisResult

data class ClickCandidate(
    val nodeId: String,
    val description: String,
)

data class ExplorationContext(
    val currentPage: PageTreeSnapshot,
    val currentAnalysis: PageAnalysisResult,
    val visitedPageIds: Set<String>,
    val visitedPageNames: List<String>,
    val transitionsRecorded: Int,
    val stepsRemaining: Int,
    val depthRemaining: Int,
)

interface ExplorationStrategy {
    suspend fun selectCandidates(
        context: ExplorationContext,
        safeCandidates: List<ClickCandidate>,
    ): List<ClickCandidate>
}
