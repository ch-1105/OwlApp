package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.AppExplorer
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.explorer.flattenNodes
import com.phoneclaw.app.gateway.ports.PageAnalysisPort
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val LAUNCH_WAIT_MS = 2000L
private const val PAGE_SETTLE_MS = 300L
private const val MAX_CANDIDATES_PER_PAGE = 8

class DefaultExplorationAgent(
    private val appLauncher: AppLauncher,
    private val appExplorer: AppExplorer,
    private val pageAnalysisPort: PageAnalysisPort,
    private val skillLearner: SkillLearner,
) : ExplorationAgent {

    override suspend fun explore(
        appPackage: String,
        appName: String,
        budget: ExplorationBudget,
        onProgress: (ExplorationProgress) -> Unit,
    ): ExplorationOutcome {
        onProgress(progressOf(0, appName, 0, 0, budget, ExplorationStatus.LAUNCHING))

        if (!appLauncher.launch(appPackage)) {
            throw IllegalStateException("无法启动应用 $appPackage。")
        }
        delay(LAUNCH_WAIT_MS)

        val visited = mutableSetOf<String>()
        val pages = mutableListOf<DiscoveredPage>()
        val transitions = mutableListOf<ExplorationTransition>()
        val stack = ArrayDeque<ExplorationFrame>()
        var stepsUsed = 0
        val startTime = System.currentTimeMillis()
        val job = coroutineContext

        fun budgetRemaining(): Boolean {
            return stepsUsed < budget.maxSteps &&
                System.currentTimeMillis() - startTime < budget.maxDurationMs &&
                job.isActive
        }

        // Capture initial page
        val initial = captureAndAnalyze(appPackage) ?: return emptyOutcome(appPackage, appName)
        visited += initial.pageId
        pages += initial
        pushCandidates(stack, initial)

        onProgress(progressOf(1, initial.pageName, 0, stepsUsed, budget, ExplorationStatus.EXPLORING))

        // Main exploration loop
        while (budgetRemaining() && stack.isNotEmpty()) {
            val frame = stack.last()

            if (frame.untried.isEmpty()) {
                stack.removeLast()
                if (stack.isNotEmpty()) {
                    appExplorer.performBack()
                    stepsUsed++
                    delay(PAGE_SETTLE_MS)
                }
                continue
            }

            val candidate = frame.untried.removeAt(0)
            val fromPageId = frame.pageId

            // Click the candidate
            appExplorer.performClick(candidate.nodeId)
            stepsUsed++

            // Capture result
            val result = captureAndAnalyze(appPackage)
            if (result == null) {
                // Capture failed, try to go back
                appExplorer.performBack()
                stepsUsed++
                delay(PAGE_SETTLE_MS)
                continue
            }

            // Record transition
            transitions += ExplorationTransition(
                fromPageId = fromPageId,
                toPageId = result.pageId,
                triggerNodeId = candidate.nodeId,
                triggerNodeDescription = candidate.description,
            )

            if (result.pageId in visited) {
                // Already visited this page, go back
                appExplorer.performBack()
                stepsUsed++
                delay(PAGE_SETTLE_MS)
                onProgress(progressOf(
                    visited.size, result.pageName, transitions.size, stepsUsed, budget,
                    ExplorationStatus.EXPLORING,
                ))
                continue
            }

            // New page discovered
            visited += result.pageId
            pages += result

            onProgress(progressOf(
                visited.size, result.pageName, transitions.size, stepsUsed, budget,
                ExplorationStatus.EXPLORING,
            ))

            if (stack.size >= budget.maxDepth) {
                // Max depth reached, go back
                appExplorer.performBack()
                stepsUsed++
                delay(PAGE_SETTLE_MS)
                continue
            }

            // Push candidates for the new page
            pushCandidates(stack, result)
            if (stack.last().untried.isEmpty()) {
                // Nothing clickable here, go back
                stack.removeLast()
                appExplorer.performBack()
                stepsUsed++
                delay(PAGE_SETTLE_MS)
            }
        }

        // Generate skill draft
        onProgress(progressOf(
            visited.size, "", transitions.size, stepsUsed, budget,
            ExplorationStatus.GENERATING,
        ))

        val draft = generateDraft(appPackage, appName, pages, transitions)

        onProgress(progressOf(
            visited.size, "", transitions.size, stepsUsed, budget,
            ExplorationStatus.COMPLETED,
        ))

        return ExplorationOutcome(
            appPackage = appPackage,
            appName = appName,
            pagesDiscovered = pages.size,
            transitionsRecorded = transitions.size,
            drafts = listOfNotNull(draft),
        )
    }

    private suspend fun captureAndAnalyze(appPackage: String): DiscoveredPage? {
        val exploration = appExplorer.captureCurrentPage() ?: return null
        if (exploration.packageName != appPackage) return null

        val analysis = pageAnalysisPort.analyzePage(
            appPackage = appPackage,
            pageTree = exploration.pageTree,
            screenshot = exploration.screenshot,
        )

        return DiscoveredPage(
            pageId = analysis.suggestedPageSpec.pageId,
            pageName = analysis.suggestedPageSpec.pageName,
            packageName = exploration.packageName,
            pageTree = exploration.pageTree,
            analysis = analysis,
            screenshot = exploration.screenshot,
            capturedAt = exploration.capturedAt,
        )
    }

    private fun pushCandidates(stack: ArrayDeque<ExplorationFrame>, page: DiscoveredPage) {
        val candidates = safeCandidates(page.pageTree)
        if (candidates.isNotEmpty()) {
            stack.addLast(ExplorationFrame(
                pageId = page.pageId,
                untried = candidates.toMutableList(),
            ))
        }
    }

    private suspend fun generateDraft(
        appPackage: String,
        appName: String,
        pages: List<DiscoveredPage>,
        transitions: List<ExplorationTransition>,
    ): LearnedSkillDraft? {
        if (pages.isEmpty()) return null

        val inputs = pages.map { page ->
            val arrivedBy = transitions.firstOrNull { it.toPageId == page.pageId }
            PageLearningInput(
                pageTree = page.pageTree,
                analysis = page.analysis,
                screenshotBytes = page.screenshot,
                arrivedBy = arrivedBy,
                capturedAt = page.capturedAt,
            )
        }

        return runCatching {
            skillLearner.generateSkillDraft(
                appPackage = appPackage,
                appName = appName,
                pages = inputs,
            )
        }.getOrNull()
    }
}

private fun safeCandidates(pageTree: PageTreeSnapshot): List<ClickCandidate> {
    return pageTree.flattenNodes()
        .filter { ExplorationSafetyFilter.isSafeToClick(it) }
        .sortedWith(candidatePriority())
        .take(MAX_CANDIDATES_PER_PAGE)
        .map { node ->
            ClickCandidate(
                nodeId = node.nodeId,
                description = node.bestDescription(),
            )
        }
}

private fun candidatePriority(): Comparator<AccessibilityNodeSnapshot> {
    return compareByDescending { node ->
        val resourceId = node.resourceId?.lowercase().orEmpty()
        when {
            resourceId.contains("tab") || resourceId.contains("navigation") -> 4
            resourceId.contains("menu") || resourceId.contains("list_item") -> 3
            node.text != null && node.contentDescription != null -> 2
            node.text != null || node.contentDescription != null -> 1
            else -> 0
        }
    }
}

private fun AccessibilityNodeSnapshot.bestDescription(): String {
    text?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    contentDescription?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { return it }
    return nodeId
}

private fun progressOf(
    pagesDiscovered: Int,
    currentPageName: String,
    transitionsRecorded: Int,
    stepsUsed: Int,
    budget: ExplorationBudget,
    status: ExplorationStatus,
): ExplorationProgress {
    return ExplorationProgress(
        pagesDiscovered = pagesDiscovered,
        currentPageName = currentPageName,
        transitionsRecorded = transitionsRecorded,
        stepsUsed = stepsUsed,
        stepsTotal = budget.maxSteps,
        status = status,
    )
}

private fun emptyOutcome(appPackage: String, appName: String): ExplorationOutcome {
    return ExplorationOutcome(
        appPackage = appPackage,
        appName = appName,
        pagesDiscovered = 0,
        transitionsRecorded = 0,
        drafts = emptyList(),
    )
}

private data class DiscoveredPage(
    val pageId: String,
    val pageName: String,
    val packageName: String,
    val pageTree: PageTreeSnapshot,
    val analysis: PageAnalysisResult,
    val screenshot: ByteArray?,
    val capturedAt: Long,
)

private data class ExplorationFrame(
    val pageId: String,
    val untried: MutableList<ClickCandidate>,
)

private data class ClickCandidate(
    val nodeId: String,
    val description: String,
)
