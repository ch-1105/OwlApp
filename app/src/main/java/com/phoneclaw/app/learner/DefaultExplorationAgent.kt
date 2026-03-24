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
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_LAUNCH_INITIAL_WAIT_MS = 2000L
private const val DEFAULT_LAUNCH_RETRY_DELAY_MS = 500L
private const val DEFAULT_LAUNCH_TIMEOUT_MS = 6_000L
private const val DEFAULT_PAGE_SETTLE_MS = 300L
private const val MAX_CANDIDATES_PER_PAGE = 8

class DefaultExplorationAgent(
    private val appLauncher: AppLauncher,
    private val appExplorer: AppExplorer,
    private val pageAnalysisPort: PageAnalysisPort,
    private val skillLearner: SkillLearner,
    private val strategy: ExplorationStrategy = HeuristicExplorationStrategy(),
    private val launchInitialWaitMs: Long = DEFAULT_LAUNCH_INITIAL_WAIT_MS,
    private val launchRetryDelayMs: Long = DEFAULT_LAUNCH_RETRY_DELAY_MS,
    private val launchTimeoutMs: Long = DEFAULT_LAUNCH_TIMEOUT_MS,
    private val pageSettleMs: Long = DEFAULT_PAGE_SETTLE_MS,
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

        val visited = mutableSetOf<String>()
        val visitedNames = mutableListOf<String>()
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

        val initial = awaitInitialPage(appPackage)
        visited += initial.pageId
        visitedNames += initial.pageName
        pages += initial
        pushCandidates(stack, initial, visited, visitedNames, transitions, budget, stepsUsed)

        onProgress(progressOf(1, initial.pageName, 0, stepsUsed, budget, ExplorationStatus.EXPLORING))

        while (budgetRemaining() && stack.isNotEmpty()) {
            val frame = stack.last()

            if (frame.untried.isEmpty()) {
                stack.removeLast()
                if (stack.isNotEmpty()) {
                    appExplorer.performBack()
                    stepsUsed++
                    delay(pageSettleMs)
                }
                continue
            }

            val candidate = frame.untried.removeAt(0)
            val fromPageId = frame.pageId

            appExplorer.performClick(candidate.nodeId)
            stepsUsed++

            val capture = captureAndAnalyze(appPackage)
            if (capture !is CaptureOutcome.Captured) {
                appExplorer.performBack()
                stepsUsed++
                delay(pageSettleMs)
                continue
            }

            val result = capture.page
            transitions += ExplorationTransition(
                fromPageId = fromPageId,
                toPageId = result.pageId,
                triggerNodeId = candidate.nodeId,
                triggerNodeDescription = candidate.description,
            )

            if (result.pageId in visited) {
                appExplorer.performBack()
                stepsUsed++
                delay(pageSettleMs)
                onProgress(
                    progressOf(
                        pagesDiscovered = visited.size,
                        currentPageName = result.pageName,
                        transitionsRecorded = transitions.size,
                        stepsUsed = stepsUsed,
                        budget = budget,
                        status = ExplorationStatus.EXPLORING,
                    ),
                )
                continue
            }

            visited += result.pageId
            visitedNames += result.pageName
            pages += result

            onProgress(
                progressOf(
                    pagesDiscovered = visited.size,
                    currentPageName = result.pageName,
                    transitionsRecorded = transitions.size,
                    stepsUsed = stepsUsed,
                    budget = budget,
                    status = ExplorationStatus.EXPLORING,
                ),
            )

            if (stack.size >= budget.maxDepth) {
                appExplorer.performBack()
                stepsUsed++
                delay(pageSettleMs)
                continue
            }

            pushCandidates(stack, result, visited, visitedNames, transitions, budget, stepsUsed)
            if (stack.last().untried.isEmpty()) {
                stack.removeLast()
                appExplorer.performBack()
                stepsUsed++
                delay(pageSettleMs)
            }
        }

        onProgress(
            progressOf(
                pagesDiscovered = visited.size,
                currentPageName = "",
                transitionsRecorded = transitions.size,
                stepsUsed = stepsUsed,
                budget = budget,
                status = ExplorationStatus.GENERATING,
            ),
        )

        val draft = generateDraft(appPackage, appName, pages, transitions)

        onProgress(
            progressOf(
                pagesDiscovered = visited.size,
                currentPageName = "",
                transitionsRecorded = transitions.size,
                stepsUsed = stepsUsed,
                budget = budget,
                status = ExplorationStatus.COMPLETED,
            ),
        )

        return ExplorationOutcome(
            appPackage = appPackage,
            appName = appName,
            pagesDiscovered = pages.size,
            transitionsRecorded = transitions.size,
            drafts = listOfNotNull(draft),
        )
    }

    private suspend fun awaitInitialPage(appPackage: String): DiscoveredPage {
        if (launchInitialWaitMs > 0) {
            delay(launchInitialWaitMs)
        }

        var lastObservedPackage: String? = null
        val initialPage = withTimeoutOrNull(launchTimeoutMs) {
            while (coroutineContext.isActive) {
                when (val capture = captureAndAnalyze(appPackage)) {
                    is CaptureOutcome.Captured -> return@withTimeoutOrNull capture.page
                    is CaptureOutcome.WrongPackage -> lastObservedPackage = capture.packageName
                    CaptureOutcome.MissingSnapshot -> Unit
                }

                delay(launchRetryDelayMs)
            }

            null
        }

        return initialPage ?: throw initialCaptureFailure(appPackage, lastObservedPackage)
    }

    private suspend fun captureAndAnalyze(appPackage: String): CaptureOutcome {
        val exploration = appExplorer.captureCurrentPage() ?: return CaptureOutcome.MissingSnapshot
        if (exploration.packageName != appPackage) {
            return CaptureOutcome.WrongPackage(exploration.packageName)
        }

        val analysis = pageAnalysisPort.analyzePage(
            appPackage = appPackage,
            pageTree = exploration.pageTree,
            screenshot = exploration.screenshot,
        )

        return CaptureOutcome.Captured(
            DiscoveredPage(
                pageId = analysis.suggestedPageSpec.pageId,
                pageName = analysis.suggestedPageSpec.pageName,
                packageName = exploration.packageName,
                pageTree = exploration.pageTree,
                analysis = analysis,
                screenshot = exploration.screenshot,
                capturedAt = exploration.capturedAt,
            ),
        )
    }

    private suspend fun pushCandidates(
        stack: ArrayDeque<ExplorationFrame>,
        page: DiscoveredPage,
        visitedIds: Set<String>,
        visitedNames: List<String>,
        transitions: List<ExplorationTransition>,
        budget: ExplorationBudget,
        stepsUsed: Int,
    ) {
        val raw = buildSafeCandidates(page.pageTree)
        if (raw.isEmpty()) return

        val context = ExplorationContext(
            currentPage = page.pageTree,
            currentAnalysis = page.analysis,
            visitedPageIds = visitedIds,
            visitedPageNames = visitedNames,
            transitionsRecorded = transitions.size,
            stepsRemaining = budget.maxSteps - stepsUsed,
            depthRemaining = budget.maxDepth - stack.size,
        )

        val ordered = runCatching {
            strategy.selectCandidates(context, raw)
        }.getOrDefault(raw)

        if (ordered.isEmpty()) return

        stack.addLast(
            ExplorationFrame(
                pageId = page.pageId,
                untried = ordered.toMutableList(),
            ),
        )
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

    private fun initialCaptureFailure(
        appPackage: String,
        lastObservedPackage: String?,
    ): IllegalStateException {
        if (!lastObservedPackage.isNullOrBlank()) {
            return IllegalStateException(
                "已启动应用 $appPackage，但无障碍当前仍看到 $lastObservedPackage。",
            )
        }

        return IllegalStateException(
            "已启动应用 $appPackage，但无障碍未采集到可用页面。",
        )
    }
}

private fun buildSafeCandidates(pageTree: PageTreeSnapshot): List<ClickCandidate> {
    return pageTree.flattenNodes()
        .filter { ExplorationSafetyFilter.isSafeToClick(it) }
        .take(MAX_CANDIDATES_PER_PAGE)
        .map { node ->
            ClickCandidate(
                nodeId = node.nodeId,
                description = node.bestDescription(),
            )
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

private sealed interface CaptureOutcome {
    data class Captured(val page: DiscoveredPage) : CaptureOutcome

    data object MissingSnapshot : CaptureOutcome

    data class WrongPackage(val packageName: String) : CaptureOutcome
}
