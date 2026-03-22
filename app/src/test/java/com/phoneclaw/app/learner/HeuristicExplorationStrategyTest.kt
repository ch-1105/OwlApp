package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HeuristicExplorationStrategyTest {

    private val strategy = HeuristicExplorationStrategy()

    @Test
    fun selectCandidates_prefersTabsOverPlainButtons() = runTest {
        val tabNode = testNode(nodeId = "tab", resourceId = "com.app:id/tab_settings")
        val menuNode = testNode(nodeId = "menu", resourceId = "com.app:id/menu_item")
        val plainNode = testNode(nodeId = "plain", text = "Click me")
        val unlabeledNode = testNode(nodeId = "unlabeled")

        val pageTree = PageTreeSnapshot(
            packageName = "com.test.app",
            activityName = "TestActivity",
            timestamp = 1L,
            nodes = listOf(tabNode, menuNode, plainNode, unlabeledNode),
        )
        val context = testContext(pageTree)

        val candidates = listOf(
            ClickCandidate("plain", "Click me"),
            ClickCandidate("unlabeled", "unlabeled"),
            ClickCandidate("tab", "tab_settings"),
            ClickCandidate("menu", "menu_item"),
        )

        val result = strategy.selectCandidates(context, candidates)

        assertEquals("tab", result[0].nodeId)
        assertEquals("menu", result[1].nodeId)
    }

    @Test
    fun selectCandidates_singleCandidate() = runTest {
        val context = testContext()
        val candidates = listOf(ClickCandidate("a", "Alpha"))

        val result = strategy.selectCandidates(context, candidates)

        assertEquals(candidates, result)
    }

    @Test
    fun selectCandidates_emptyList() = runTest {
        val context = testContext()
        val result = strategy.selectCandidates(context, emptyList())
        assertEquals(emptyList<ClickCandidate>(), result)
    }
}

private fun testNode(
    nodeId: String,
    text: String? = null,
    contentDescription: String? = null,
    resourceId: String? = null,
): AccessibilityNodeSnapshot {
    return AccessibilityNodeSnapshot(
        nodeId = nodeId,
        className = "android.widget.Button",
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        isClickable = true,
        isScrollable = false,
        isEditable = false,
        bounds = "0,0,200,100",
        children = emptyList(),
    )
}

private fun testContext(
    pageTree: PageTreeSnapshot = PageTreeSnapshot(
        packageName = "com.test.app",
        activityName = "TestActivity",
        timestamp = 1L,
        nodes = emptyList(),
    ),
): ExplorationContext {
    return ExplorationContext(
        currentPage = pageTree,
        currentAnalysis = PageAnalysisResult(
            suggestedPageSpec = PageSpec(
                pageId = "test_page",
                pageName = "TestPage",
                appPackage = "com.test.app",
                activityName = "TestActivity",
                matchRules = listOf(PageMatchRule(type = "activity_name", value = "TestActivity")),
                availableActions = emptyList(),
            ),
            clickableElements = emptyList(),
            navigationHints = emptyList(),
        ),
        visitedPageIds = emptySet(),
        visitedPageNames = emptyList(),
        transitionsRecorded = 0,
        stepsRemaining = 20,
        depthRemaining = 3,
    )
}
