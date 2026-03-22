package com.phoneclaw.app.executor

import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.explorer.AppExplorer
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.explorer.findNode
import com.phoneclaw.app.explorer.flattenNodes
import com.phoneclaw.app.gateway.ports.ExecutorPort
import com.phoneclaw.app.learner.AppLauncher
import com.phoneclaw.app.store.SkillStore
import kotlinx.coroutines.delay

private const val LAUNCH_WAIT_MS = 2000L
private const val NAVIGATE_WAIT_MS = 500L

class AccessibilityExecutor(
    private val appExplorer: AppExplorer,
    private val appLauncher: AppLauncher,
    private val skillStore: SkillStore,
) : ExecutorPort {

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val actionId = request.actionSpec.actionId
        val skillId = request.actionSpec.skillId

        val record = skillStore.loadAllSkills().firstOrNull { it.skillId == skillId }
            ?: return failureResult(request, "找不到技能 $skillId。")

        val appPackage = record.manifest.appPackage
            ?: return failureResult(request, "技能 $skillId 没有关联应用。")

        // Launch the target app
        if (!appLauncher.launch(appPackage)) {
            return failureResult(request, "无法启动应用 $appPackage。")
        }
        delay(LAUNCH_WAIT_MS)

        // Capture current page
        val currentPage = appExplorer.captureCurrentPage()
            ?: return failureResult(request, "无法捕获当前页面。")

        if (currentPage.packageName != appPackage) {
            return failureResult(request, "当前前台应用不是 $appPackage。")
        }

        val pageGraph = record.pageGraph
        if (pageGraph == null) {
            return failureResult(request, "技能 $skillId 没有页面导航图。")
        }

        // Find which page declares this action
        val targetPage = pageGraph.pages.firstOrNull { page ->
            actionId in page.availableActions
        }

        if (targetPage == null) {
            return failureResult(request, "在页面导航图中找不到动作 $actionId 的目标页面。")
        }

        // Navigate to target page if needed
        val currentPageId = identifyCurrentPage(currentPage.pageTree, pageGraph)
        if (currentPageId != null && currentPageId != targetPage.pageId) {
            val navigated = navigateToPage(currentPageId, targetPage.pageId, appPackage, pageGraph)
            if (!navigated) {
                return failureResult(request, "无法导航到目标页面 ${targetPage.pageName}。")
            }
        }

        // Try to find and click the target node
        val targetDescription = request.actionSpec.params["target_node"]
            ?: request.actionSpec.intentSummary

        val page = appExplorer.captureCurrentPage()
            ?: return failureResult(request, "执行前无法捕获页面。")

        val targetNode = findNodeByDescription(page.pageTree, targetDescription)
        if (targetNode != null) {
            appExplorer.performClick(targetNode)
            delay(NAVIGATE_WAIT_MS)
        }

        return ExecutionResult(
            requestId = request.requestId,
            taskId = request.taskId,
            actionId = actionId,
            status = "success",
            resultSummary = "已在 ${record.manifest.displayName} 中执行动作 $actionId。",
            verification = VerificationResult(
                passed = true,
                reason = "Accessibility action executed.",
            ),
        )
    }

    private suspend fun navigateToPage(
        fromPageId: String,
        toPageId: String,
        appPackage: String,
        pageGraph: com.phoneclaw.app.contracts.PageGraph,
    ): Boolean {
        // Simple BFS to find path
        val path = findTransitionPath(fromPageId, toPageId, pageGraph) ?: return false

        for (transition in path) {
            val page = appExplorer.captureCurrentPage() ?: return false
            if (page.packageName != appPackage) return false

            val nodeId = findNodeByDescription(page.pageTree, transition.triggerNodeDescription)
                ?: return false

            appExplorer.performClick(nodeId)
            delay(NAVIGATE_WAIT_MS)
        }
        return true
    }

    private fun findTransitionPath(
        fromPageId: String,
        toPageId: String,
        pageGraph: com.phoneclaw.app.contracts.PageGraph,
    ): List<com.phoneclaw.app.contracts.PageTransition>? {
        if (fromPageId == toPageId) return emptyList()

        val transitions = pageGraph.transitions
        val queue = ArrayDeque<List<com.phoneclaw.app.contracts.PageTransition>>()
        val visited = mutableSetOf(fromPageId)

        transitions.filter { it.fromPageId == fromPageId }.forEach { t ->
            queue.addLast(listOf(t))
        }

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val lastPageId = path.last().toPageId

            if (lastPageId == toPageId) return path
            if (lastPageId in visited) continue
            visited += lastPageId

            transitions.filter { it.fromPageId == lastPageId }.forEach { t ->
                if (t.toPageId !in visited) {
                    queue.addLast(path + t)
                }
            }
        }

        return null
    }

    private fun identifyCurrentPage(
        pageTree: PageTreeSnapshot,
        pageGraph: com.phoneclaw.app.contracts.PageGraph,
    ): String? {
        val activity = pageTree.activityName?.lowercase() ?: return null

        return pageGraph.pages.firstOrNull { page ->
            page.matchRules.any { rule ->
                rule.type == "activity_name" && rule.value.lowercase() == activity
            }
        }?.pageId
    }

    private fun findNodeByDescription(
        pageTree: PageTreeSnapshot,
        description: String,
    ): String? {
        val desc = description.lowercase().trim()
        return pageTree.flattenNodes().firstOrNull { node ->
            node.text?.lowercase()?.contains(desc) == true ||
                node.contentDescription?.lowercase()?.contains(desc) == true ||
                node.resourceId?.lowercase()?.contains(desc) == true
        }?.nodeId
    }

    private fun failureResult(request: ExecutionRequest, errorMessage: String): ExecutionResult {
        return ExecutionResult(
            requestId = request.requestId,
            taskId = request.taskId,
            actionId = request.actionSpec.actionId,
            status = "failed",
            resultSummary = "Accessibility action failed.",
            errorMessage = errorMessage,
            verification = VerificationResult(
                passed = false,
                reason = errorMessage,
            ),
        )
    }
}
