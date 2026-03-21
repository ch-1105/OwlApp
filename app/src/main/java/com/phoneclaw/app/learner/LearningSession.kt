package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisResult

data class LearningSessionState(
    val appPackage: String,
    val appName: String,
    val exploredPages: List<ExploredPage>,
    val transitions: List<ExplorationTransition>,
    val status: LearningStatus,
    val draft: LearnedSkillDraft? = null,
    val errorMessage: String? = null,
)

data class ExploredPage(
    val pageTree: PageTreeSnapshot,
    val analysis: PageAnalysisResult,
    val capturedAt: Long,
    val screenshot: ByteArray? = null,
    val screenshotPath: String? = null,
    val arrivedBy: ExplorationTransition? = null,
)

data class ExplorationTransition(
    val fromPageId: String,
    val toPageId: String,
    val triggerNodeId: String,
    val triggerNodeDescription: String,
)

enum class LearningStatus {
    EXPLORING,
    ANALYZING,
    GENERATING,
    REVIEW_PENDING,
    COMPLETED,
    FAILED,
}

interface LearningSessionManager {
    suspend fun startLearning(appPackage: String, appName: String): String

    suspend fun captureAndAnalyze(sessionId: String): ExploredPage

    suspend fun tapAndCapture(
        sessionId: String,
        nodeId: String,
        nodeDescription: String? = null,
    ): ExploredPage

    suspend fun finishExploration(sessionId: String): LearnedSkillDraft

    fun getSessionState(sessionId: String): LearningSessionState?
}
