package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.AppExplorer
import com.phoneclaw.app.explorer.ExplorationResult
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisPort
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import com.phoneclaw.app.skills.SkillActionBinding
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultExplorationAgentTest {
    @Test
    fun explore_discoversMultiplePagesViaClickableElements() = runTest {
        val homePage = testSnapshot(
            activityName = "HomeActivity",
            timestamp = 1L,
            clickableNodes = listOf(
                testClickableNode(nodeId = "0/0", text = "Settings"),
                testClickableNode(nodeId = "0/1", text = "Profile"),
            ),
        )
        val settingsPage = testSnapshot(
            activityName = "SettingsActivity",
            timestamp = 2L,
            clickableNodes = emptyList(),
        )
        val profilePage = testSnapshot(
            activityName = "ProfileActivity",
            timestamp = 3L,
            clickableNodes = emptyList(),
        )

        val explorer = NavigationFakeExplorer(
            initialPage = homePage,
            clickNavigations = mapOf(
                "0/0" to settingsPage,
                "0/1" to profilePage,
            ),
        )
        val progressUpdates = mutableListOf<ExplorationProgress>()

        val agent = DefaultExplorationAgent(
            appLauncher = { true },
            appExplorer = explorer,
            pageAnalysisPort = SimplePageAnalysisPort(),
            skillLearner = NoOpSkillLearner(),
        )

        val outcome = agent.explore(
            appPackage = APP_PACKAGE,
            appName = "Example",
            budget = ExplorationBudget(maxSteps = 20, maxDepth = 3, maxDurationMs = 30_000L),
            onProgress = { progressUpdates += it },
        )

        assertEquals(3, outcome.pagesDiscovered)
        assertTrue(outcome.transitionsRecorded >= 2)
        assertTrue(progressUpdates.isNotEmpty())
        assertTrue(progressUpdates.any { it.status == ExplorationStatus.EXPLORING })
    }

    @Test
    fun explore_respectsMaxDepthBudget() = runTest {
        val page1 = testSnapshot(activityName = "Activity1", timestamp = 1L, clickableNodes = listOf(
            testClickableNode(nodeId = "0/0", text = "Next"),
        ))
        val page2 = testSnapshot(activityName = "Activity2", timestamp = 2L, clickableNodes = listOf(
            testClickableNode(nodeId = "0/0", text = "Next"),
        ))
        val page3 = testSnapshot(activityName = "Activity3", timestamp = 3L, clickableNodes = listOf(
            testClickableNode(nodeId = "0/0", text = "Next"),
        ))
        val page4 = testSnapshot(activityName = "Activity4", timestamp = 4L, clickableNodes = emptyList())

        val explorer = NavigationFakeExplorer(
            initialPage = page1,
            clickNavigations = mapOf(
                "0/0" to page2,
            ),
            deepClickNavigations = mapOf(
                "activity2" to mapOf("0/0" to page3),
                "activity3" to mapOf("0/0" to page4),
            ),
        )

        val agent = DefaultExplorationAgent(
            appLauncher = { true },
            appExplorer = explorer,
            pageAnalysisPort = SimplePageAnalysisPort(),
            skillLearner = NoOpSkillLearner(),
        )

        val outcome = agent.explore(
            appPackage = APP_PACKAGE,
            appName = "Example",
            budget = ExplorationBudget(maxSteps = 20, maxDepth = 2, maxDurationMs = 30_000L),
        )

        // With maxDepth=2, should discover page1 + page2 + page3 (depth 0,1,2)
        // but page4 at depth 3 should not be reached
        assertTrue("Expected at most 3 pages but got ${outcome.pagesDiscovered}",
            outcome.pagesDiscovered <= 3)
    }

    @Test
    fun explore_skipsDangerousButtons() = runTest {
        val page = testSnapshot(
            activityName = "DangerActivity",
            timestamp = 1L,
            clickableNodes = listOf(
                testClickableNode(nodeId = "0/0", text = "删除全部"),
                testClickableNode(nodeId = "0/1", text = "发送消息"),
                testClickableNode(nodeId = "0/2", text = "付款"),
            ),
        )

        val explorer = NavigationFakeExplorer(initialPage = page)

        val agent = DefaultExplorationAgent(
            appLauncher = { true },
            appExplorer = explorer,
            pageAnalysisPort = SimplePageAnalysisPort(),
            skillLearner = NoOpSkillLearner(),
        )

        val outcome = agent.explore(
            appPackage = APP_PACKAGE,
            appName = "Example",
            budget = ExplorationBudget(maxSteps = 10),
        )

        assertEquals(1, outcome.pagesDiscovered)
        assertEquals(0, outcome.transitionsRecorded)
        assertTrue(explorer.clickedNodeIds.isEmpty())
    }

    @Test
    fun explore_stopsWhenLeavingTargetApp() = runTest {
        val homePage = testSnapshot(
            activityName = "HomeActivity",
            timestamp = 1L,
            clickableNodes = listOf(
                testClickableNode(nodeId = "0/0", text = "External Link"),
            ),
        )
        val externalPage = testSnapshot(
            packageName = "com.other.app",
            activityName = "BrowserActivity",
            timestamp = 2L,
            clickableNodes = emptyList(),
        )

        val explorer = NavigationFakeExplorer(
            initialPage = homePage,
            clickNavigations = mapOf("0/0" to externalPage),
        )

        val agent = DefaultExplorationAgent(
            appLauncher = { true },
            appExplorer = explorer,
            pageAnalysisPort = SimplePageAnalysisPort(),
            skillLearner = NoOpSkillLearner(),
        )

        val outcome = agent.explore(
            appPackage = APP_PACKAGE,
            appName = "Example",
            budget = ExplorationBudget(maxSteps = 10),
        )

        assertEquals(1, outcome.pagesDiscovered)
    }
}

private const val APP_PACKAGE = "com.example.app"

/**
 * Fake AppExplorer that simulates app navigation with a stack.
 * performClick pushes a new page, performBack pops back.
 */
private class NavigationFakeExplorer(
    initialPage: PageTreeSnapshot,
    private val clickNavigations: Map<String, PageTreeSnapshot> = emptyMap(),
    private val deepClickNavigations: Map<String, Map<String, PageTreeSnapshot>> = emptyMap(),
) : AppExplorer {
    private val pageStack = ArrayDeque<PageTreeSnapshot>().apply { addLast(initialPage) }
    val clickedNodeIds = mutableListOf<String>()

    override suspend fun captureCurrentPage(): ExplorationResult? {
        return pageStack.lastOrNull()?.toResult()
    }

    override suspend fun performClick(nodeId: String): ExplorationResult? {
        clickedNodeIds += nodeId
        val currentPageId = pageStack.last().activityName?.lowercase().orEmpty()
        val next = deepClickNavigations[currentPageId]?.get(nodeId)
            ?: clickNavigations[nodeId]
            ?: return pageStack.lastOrNull()?.toResult()
        pageStack.addLast(next)
        return next.toResult()
    }

    override suspend fun performBack(): ExplorationResult? {
        if (pageStack.size > 1) pageStack.removeLast()
        return pageStack.lastOrNull()?.toResult()
    }

    private fun PageTreeSnapshot.toResult(): ExplorationResult {
        return ExplorationResult(
            packageName = packageName,
            activityName = activityName,
            pageTree = this,
            screenshot = null,
            capturedAt = timestamp,
        )
    }
}

private class SimplePageAnalysisPort : PageAnalysisPort {
    override suspend fun analyzePage(
        appPackage: String,
        pageTree: PageTreeSnapshot,
        screenshot: ByteArray?,
    ): PageAnalysisResult {
        val pageId = pageTree.activityName?.lowercase() ?: "unknown_${pageTree.timestamp}"
        return PageAnalysisResult(
            suggestedPageSpec = PageSpec(
                pageId = pageId,
                pageName = pageTree.activityName ?: "Unknown",
                appPackage = appPackage,
                activityName = pageTree.activityName,
                matchRules = listOf(
                    PageMatchRule(type = "activity_name", value = pageTree.activityName.orEmpty()),
                ),
                availableActions = emptyList(),
            ),
            clickableElements = emptyList(),
            navigationHints = emptyList(),
        )
    }
}

private class NoOpSkillLearner : SkillLearner {
    override suspend fun generateSkillDraft(
        appPackage: String,
        appName: String,
        pages: List<PageLearningInput>,
    ): LearnedSkillDraft {
        return LearnedSkillDraft(
            manifest = SkillManifest(
                schemaVersion = "v1alpha1",
                skillId = "learned.$appPackage",
                skillVersion = "0.1.0",
                skillType = "app",
                displayName = "$appName Learned Skill",
                owner = "learner",
                platform = "android",
                appPackage = appPackage,
                defaultRiskLevel = RiskLevel.GUARDED,
                enabled = false,
                actions = listOf(
                    SkillActionManifest(
                        actionId = "explore_action",
                        displayName = "Explore Action",
                        description = "Auto-generated",
                        executorType = "accessibility",
                        riskLevel = RiskLevel.GUARDED,
                        requiresConfirmation = true,
                        expectedOutcome = "App responds",
                    ),
                ),
            ),
            pageGraph = com.phoneclaw.app.contracts.PageGraph(
                appPackage = appPackage,
                pages = emptyList(),
                transitions = emptyList(),
            ),
            bindings = listOf(SkillActionBinding(actionId = "explore_action", intentAction = "")),
            evidence = emptyList(),
        )
    }
}

private fun testSnapshot(
    packageName: String = APP_PACKAGE,
    activityName: String,
    timestamp: Long,
    clickableNodes: List<AccessibilityNodeSnapshot>,
): PageTreeSnapshot {
    return PageTreeSnapshot(
        packageName = packageName,
        activityName = activityName,
        timestamp = timestamp,
        nodes = listOf(
            AccessibilityNodeSnapshot(
                nodeId = "0",
                className = "android.widget.FrameLayout",
                text = null,
                contentDescription = null,
                resourceId = "$packageName:id/root",
                isClickable = false,
                isScrollable = false,
                isEditable = false,
                bounds = "0,0,1080,2400",
                children = clickableNodes,
            ),
        ),
    )
}

private fun testClickableNode(nodeId: String, text: String): AccessibilityNodeSnapshot {
    return AccessibilityNodeSnapshot(
        nodeId = nodeId,
        className = "android.widget.Button",
        text = text,
        contentDescription = null,
        resourceId = null,
        isClickable = true,
        isScrollable = false,
        isEditable = false,
        bounds = "0,0,200,100",
        children = emptyList(),
    )
}
