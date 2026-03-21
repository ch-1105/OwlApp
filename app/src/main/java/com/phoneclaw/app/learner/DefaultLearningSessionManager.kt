package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.AppExplorer
import com.phoneclaw.app.explorer.ExplorationResult
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisPort
import java.util.UUID

class DefaultLearningSessionManager(
    private val appExplorer: AppExplorer,
    private val pageAnalysisPort: PageAnalysisPort,
    private val skillLearner: SkillLearner,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : LearningSessionManager {
    private val sessions = linkedMapOf<String, LearningSessionState>()

    override suspend fun startLearning(appPackage: String, appName: String): String {
        val sessionId = sessionIdFactory()
        sessions[sessionId] = LearningSessionState(
            appPackage = appPackage,
            appName = appName,
            exploredPages = emptyList(),
            transitions = emptyList(),
            status = LearningStatus.EXPLORING,
        )
        return sessionId
    }

    override suspend fun captureAndAnalyze(sessionId: String): ExploredPage {
        val session = moveToAnalyzing(sessionId, operationName = "采集页面")
        val exploration = appExplorer.captureCurrentPage()
            ?: return failAndThrow(sessionId, "当前没有可采集的前台页面。")

        return analyzeExploration(
            sessionId = sessionId,
            session = session,
            exploration = exploration,
        )
    }

    override suspend fun tapAndCapture(
        sessionId: String,
        nodeId: String,
        nodeDescription: String?,
    ): ExploredPage {
        val session = moveToAnalyzing(sessionId, operationName = "点击学习")
        val sourcePage = session.exploredPages.lastOrNull()
            ?: return rejectAndThrow(
                sessionId = sessionId,
                session = session.copy(status = LearningStatus.EXPLORING),
                message = "请先采集当前页面，再执行点击学习。",
            )

        val currentPage = appExplorer.captureCurrentPage()
            ?: return failAndThrow(sessionId, "点击学习前没有可用的前台页面。")
        if (!currentPage.pageTree.matchesLearningSource(sourcePage.pageTree)) {
            return rejectAndThrow(
                sessionId = sessionId,
                session = session.copy(status = LearningStatus.EXPLORING),
                message = "当前前台页面已经变化，请先重新采集当前页面，再继续点击学习。",
            )
        }

        val exploration = appExplorer.performClick(nodeId)
            ?: return failAndThrow(sessionId, "点击节点 `$nodeId` 后没有采集到新页面。")

        val pendingTransition = PendingTransition(
            fromPageId = sourcePage.analysis.suggestedPageSpec.pageId,
            triggerNodeId = nodeId,
            triggerNodeDescription = nodeDescription?.takeIf { it.isNotBlank() } ?: nodeId,
        )

        return analyzeExploration(
            sessionId = sessionId,
            session = session,
            exploration = exploration,
            pendingTransition = pendingTransition,
        )
    }

    override suspend fun finishExploration(sessionId: String): LearnedSkillDraft {
        val session = moveToGenerating(sessionId)
        if (session.exploredPages.isEmpty()) {
            return rejectAndThrow(
                sessionId = sessionId,
                session = session.copy(status = LearningStatus.EXPLORING),
                message = "请先至少采集一个页面，再生成 Skill 草稿。",
            )
        }

        return runCatching {
            val draft = skillLearner.generateSkillDraft(
                appPackage = session.appPackage,
                appName = session.appName,
                pages = session.exploredPages.map { page ->
                    PageLearningInput(
                        pageTree = page.pageTree,
                        analysis = page.analysis,
                        screenshotPath = page.screenshotPath,
                        screenshotBytes = page.screenshot,
                        arrivedBy = page.arrivedBy,
                        capturedAt = page.capturedAt,
                    )
                },
            )

            sessions[sessionId] = session.copy(
                status = LearningStatus.REVIEW_PENDING,
                draft = draft,
                errorMessage = null,
            )
            draft
        }.getOrElse { error ->
            failAndThrow(sessionId, error.message ?: "生成 Skill 草稿失败。")
        }
    }

    override fun getSessionState(sessionId: String): LearningSessionState? {
        return sessions[sessionId]
    }

    override fun discardSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    private suspend fun analyzeExploration(
        sessionId: String,
        session: LearningSessionState,
        exploration: ExplorationResult,
        pendingTransition: PendingTransition? = null,
    ): ExploredPage {
        if (exploration.packageName != session.appPackage) {
            return failAndThrow(
                sessionId,
                "当前前台应用是 ${exploration.packageName}，不是学习会话目标 ${session.appPackage}。",
            )
        }

        return runCatching {
            val analysis = pageAnalysisPort.analyzePage(
                appPackage = exploration.packageName,
                pageTree = exploration.pageTree,
                screenshot = exploration.screenshot,
            )
            val transition = pendingTransition?.toTransition(analysis.suggestedPageSpec.pageId)
            val exploredPage = ExploredPage(
                pageTree = exploration.pageTree,
                analysis = analysis,
                capturedAt = exploration.capturedAt,
                screenshot = exploration.screenshot,
                arrivedBy = transition,
            )

            sessions[sessionId] = session.copy(
                exploredPages = session.exploredPages + exploredPage,
                transitions = session.transitions + listOfNotNull(transition),
                status = LearningStatus.EXPLORING,
                draft = null,
                errorMessage = null,
            )

            exploredPage
        }.getOrElse { error ->
            failAndThrow(sessionId, error.message ?: "页面分析失败。")
        }
    }

    private fun moveToAnalyzing(
        sessionId: String,
        operationName: String,
    ): LearningSessionState {
        val session = requireExploringSession(sessionId, operationName)
        val analyzingSession = session.copy(
            status = LearningStatus.ANALYZING,
            draft = null,
            errorMessage = null,
        )
        sessions[sessionId] = analyzingSession
        return analyzingSession
    }

    private fun moveToGenerating(sessionId: String): LearningSessionState {
        val session = requireExploringSession(sessionId, operationName = "生成 Skill 草稿")
        val generatingSession = session.copy(
            status = LearningStatus.GENERATING,
            draft = null,
            errorMessage = null,
        )
        sessions[sessionId] = generatingSession
        return generatingSession
    }

    private fun requireExploringSession(
        sessionId: String,
        operationName: String,
    ): LearningSessionState {
        val session = requireSession(sessionId)
        if (session.status == LearningStatus.EXPLORING) {
            return session
        }

        val message = when (session.status) {
            LearningStatus.ANALYZING -> "当前学习会话正在分析页面，请稍后再试。"
            LearningStatus.GENERATING -> "当前学习会话正在生成 Skill 草稿，请稍后再试。"
            LearningStatus.REVIEW_PENDING -> "当前学习会话已经生成草稿，正在等待审核，不能继续$operationName。"
            LearningStatus.COMPLETED -> "当前学习会话已经完成，不能继续$operationName。"
            LearningStatus.FAILED -> "当前学习会话已经失败，请重新开始新的学习会话后再$operationName。"
            LearningStatus.EXPLORING -> error("Unexpected exploring status branch.")
        }

        throw IllegalStateException(message)
    }

    private fun requireSession(sessionId: String): LearningSessionState {
        return requireNotNull(sessions[sessionId]) {
            "Learning session $sessionId was not found."
        }
    }

    private fun rejectAndThrow(
        sessionId: String,
        session: LearningSessionState,
        message: String,
    ): Nothing {
        sessions[sessionId] = session.copy(
            draft = null,
            errorMessage = message,
        )
        throw IllegalStateException(message)
    }

    private fun failAndThrow(
        sessionId: String,
        message: String,
    ): Nothing {
        val session = requireSession(sessionId)
        sessions[sessionId] = session.copy(
            status = LearningStatus.FAILED,
            draft = null,
            errorMessage = message,
        )
        throw IllegalStateException(message)
    }
}

private fun PageTreeSnapshot.matchesLearningSource(other: PageTreeSnapshot): Boolean {
    if (packageName != other.packageName) {
        return false
    }
    if (activityName != other.activityName) {
        return false
    }
    return learningFingerprint() == other.learningFingerprint()
}

private fun PageTreeSnapshot.learningFingerprint(): List<String> {
    return nodes.flatMap { node -> node.learningFingerprint() }
        .take(24)
}

private fun AccessibilityNodeSnapshot.learningFingerprint(): List<String> {
    val current = listOf(
        listOf(
            className.orEmpty(),
            text.orEmpty(),
            contentDescription.orEmpty(),
            resourceId.orEmpty(),
            isClickable.toString(),
            children.size.toString(),
        ).joinToString("|"),
    )
    return current + children.flatMap { child -> child.learningFingerprint() }
}

private data class PendingTransition(
    val fromPageId: String,
    val triggerNodeId: String,
    val triggerNodeDescription: String,
) {
    fun toTransition(toPageId: String): ExplorationTransition {
        return ExplorationTransition(
            fromPageId = fromPageId,
            toPageId = toPageId,
            triggerNodeId = triggerNodeId,
            triggerNodeDescription = triggerNodeDescription,
        )
    }
}



