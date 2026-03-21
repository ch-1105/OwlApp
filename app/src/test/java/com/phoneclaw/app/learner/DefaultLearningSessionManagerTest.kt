package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.AppExplorer
import com.phoneclaw.app.explorer.ExplorationResult
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.ClickableElementSuggestion
import com.phoneclaw.app.gateway.ports.PageAnalysisPort
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import com.phoneclaw.app.skills.SkillActionBinding
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultLearningSessionManagerTest {
    @Test
    fun startLearning_captureAndAnalyze_finishExploration_returnsDraftAndEvidence() = runTest {
        val pageOne = sampleExplorationResult(
            pageId = "wifi_settings",
            pageName = "Wi-Fi",
            timestamp = 10L,
            screenshot = byteArrayOf(1, 2, 3),
        )
        val explorer = FakeAppExplorer(
            captureResults = listOf(pageOne),
        )
        val pageAnalysisPort = FakePageAnalysisPort()
        val skillLearner = FakeSkillLearner(
            draft = sampleDraft(),
        )
        val manager = DefaultLearningSessionManager(
            appExplorer = explorer,
            pageAnalysisPort = pageAnalysisPort,
            skillLearner = skillLearner,
            sessionIdFactory = { "session-1" },
        )

        val sessionId = manager.startLearning(
            appPackage = "com.example.settings",
            appName = "Settings",
        )
        val exploredPage = manager.captureAndAnalyze(sessionId)
        val draft = manager.finishExploration(sessionId)
        val state = requireNotNull(manager.getSessionState(sessionId))
        val learnerPage = skillLearner.lastPages.single()

        assertEquals("session-1", sessionId)
        assertEquals("wifi_settings", exploredPage.analysis.suggestedPageSpec.pageId)
        assertArrayEquals(byteArrayOf(1, 2, 3), exploredPage.screenshot)
        assertEquals(sampleDraft(), draft)
        assertEquals(LearningStatus.REVIEW_PENDING, state.status)
        assertEquals(sampleDraft(), state.draft)
        assertEquals(1, state.exploredPages.size)
        assertTrue(state.transitions.isEmpty())
        assertEquals(1, skillLearner.lastPages.size)
        assertEquals(10L, learnerPage.capturedAt)
        assertArrayEquals(byteArrayOf(1, 2, 3), learnerPage.screenshotBytes)
        assertNull(learnerPage.arrivedBy)
    }

    @Test
    fun tapAndCapture_recordsTransitionAndPassesItToLearner() = runTest {
        val pageOne = sampleExplorationResult(
            pageId = "wifi_settings",
            pageName = "Wi-Fi",
            timestamp = 10L,
            screenshot = byteArrayOf(1),
        )
        val pageTwo = sampleExplorationResult(
            pageId = "advanced_settings",
            pageName = "Advanced",
            timestamp = 20L,
            screenshot = byteArrayOf(2),
        )
        val explorer = FakeAppExplorer(
            captureResults = listOf(pageOne),
            clickResults = mapOf("0/0" to pageTwo),
        )
        val skillLearner = FakeSkillLearner(sampleDraft())
        val manager = DefaultLearningSessionManager(
            appExplorer = explorer,
            pageAnalysisPort = FakePageAnalysisPort(),
            skillLearner = skillLearner,
            sessionIdFactory = { "session-2" },
        )

        val sessionId = manager.startLearning(
            appPackage = "com.example.settings",
            appName = "Settings",
        )
        manager.captureAndAnalyze(sessionId)
        val exploredPage = manager.tapAndCapture(
            sessionId = sessionId,
            nodeId = "0/0",
            nodeDescription = "Advanced row",
        )
        manager.finishExploration(sessionId)
        val state = requireNotNull(manager.getSessionState(sessionId))
        val transition = requireNotNull(state.transitions.singleOrNull())
        val learnerPage = skillLearner.lastPages.last()

        assertEquals("advanced_settings", exploredPage.analysis.suggestedPageSpec.pageId)
        assertEquals(listOf("0/0"), explorer.clickedNodeIds)
        assertEquals(2, state.exploredPages.size)
        assertEquals("advanced_settings", state.exploredPages.last().analysis.suggestedPageSpec.pageId)
        assertEquals("wifi_settings", transition.fromPageId)
        assertEquals("advanced_settings", transition.toPageId)
        assertEquals("0/0", transition.triggerNodeId)
        assertEquals("Advanced row", transition.triggerNodeDescription)
        assertEquals(transition, state.exploredPages.last().arrivedBy)
        assertEquals(transition, learnerPage.arrivedBy)
        assertArrayEquals(byteArrayOf(2), learnerPage.screenshotBytes)
    }
    @Test
    fun tapAndCapture_rejectsWhenForegroundPageHasDriftedSinceLastCapture() = runTest {
        val pageOne = sampleExplorationResult(
            pageId = "wifi_settings",
            pageName = "Wi-Fi",
            timestamp = 10L,
        )
        val driftedPage = sampleExplorationResult(
            pageId = "advanced_settings",
            pageName = "Advanced",
            timestamp = 15L,
        )
        val explorer = FakeAppExplorer(
            captureResults = listOf(pageOne, driftedPage),
            clickResults = mapOf(
                "0/0" to sampleExplorationResult(
                    pageId = "details_settings",
                    pageName = "Details",
                    timestamp = 20L,
                ),
            ),
        )
        val manager = DefaultLearningSessionManager(
            appExplorer = explorer,
            pageAnalysisPort = FakePageAnalysisPort(),
            skillLearner = FakeSkillLearner(sampleDraft()),
            sessionIdFactory = { "session-drift" },
        )

        val sessionId = manager.startLearning(
            appPackage = "com.example.settings",
            appName = "Settings",
        )
        manager.captureAndAnalyze(sessionId)

        try {
            manager.tapAndCapture(sessionId, nodeId = "0/0", nodeDescription = "Advanced row")
            fail("Expected tapAndCapture to reject a drifted foreground page.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("重新采集当前页面"))
        }

        val state = requireNotNull(manager.getSessionState(sessionId))
        assertEquals(LearningStatus.EXPLORING, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("重新采集当前页面"))
        assertTrue(explorer.clickedNodeIds.isEmpty())
    }

    @Test
    fun captureAndAnalyze_marksSessionFailedWhenForegroundAppDoesNotMatch() = runTest {
        val explorer = FakeAppExplorer(
            captureResults = listOf(
                sampleExplorationResult(
                    appPackage = "com.other.app",
                    pageId = "wrong_page",
                    pageName = "Wrong",
                    timestamp = 10L,
                ),
            ),
        )
        val manager = DefaultLearningSessionManager(
            appExplorer = explorer,
            pageAnalysisPort = FakePageAnalysisPort(),
            skillLearner = FakeSkillLearner(sampleDraft()),
            sessionIdFactory = { "session-3" },
        )

        val sessionId = manager.startLearning(
            appPackage = "com.example.settings",
            appName = "Settings",
        )

        try {
            manager.captureAndAnalyze(sessionId)
            fail("Expected captureAndAnalyze to fail when foreground app does not match session app.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("com.other.app"))
        }

        val state = requireNotNull(manager.getSessionState(sessionId))
        assertEquals(LearningStatus.FAILED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("com.other.app"))
    }

    @Test
    fun captureAndAnalyze_rejectsReviewPendingSessionWithoutChangingDraft() = runTest {
        val explorer = FakeAppExplorer(
            captureResults = listOf(
                sampleExplorationResult(
                    pageId = "wifi_settings",
                    pageName = "Wi-Fi",
                    timestamp = 10L,
                ),
            ),
        )
        val draft = sampleDraft()
        val manager = DefaultLearningSessionManager(
            appExplorer = explorer,
            pageAnalysisPort = FakePageAnalysisPort(),
            skillLearner = FakeSkillLearner(draft),
            sessionIdFactory = { "session-4" },
        )

        val sessionId = manager.startLearning(
            appPackage = "com.example.settings",
            appName = "Settings",
        )
        manager.captureAndAnalyze(sessionId)
        manager.finishExploration(sessionId)

        try {
            manager.captureAndAnalyze(sessionId)
            fail("Expected captureAndAnalyze to reject a review-pending session.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("等待审核"))
        }

        val state = requireNotNull(manager.getSessionState(sessionId))
        assertEquals(LearningStatus.REVIEW_PENDING, state.status)
        assertEquals(draft, state.draft)
        assertEquals(1, state.exploredPages.size)
        assertEquals(1, explorer.captureCalls)
    }

    @Test
    fun captureAndAnalyze_rejectsFailedSessionResume() = runTest {
        val explorer = FakeAppExplorer(
            captureResults = listOf(
                sampleExplorationResult(
                    appPackage = "com.other.app",
                    pageId = "wrong_page",
                    pageName = "Wrong",
                    timestamp = 10L,
                ),
            ),
        )
        val manager = DefaultLearningSessionManager(
            appExplorer = explorer,
            pageAnalysisPort = FakePageAnalysisPort(),
            skillLearner = FakeSkillLearner(sampleDraft()),
            sessionIdFactory = { "session-5" },
        )

        val sessionId = manager.startLearning(
            appPackage = "com.example.settings",
            appName = "Settings",
        )

        try {
            manager.captureAndAnalyze(sessionId)
            fail("Expected the first capture to fail.")
        } catch (_: IllegalStateException) {
        }

        try {
            manager.captureAndAnalyze(sessionId)
            fail("Expected failed sessions to reject resume.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("重新开始新的学习会话"))
        }

        val state = requireNotNull(manager.getSessionState(sessionId))
        assertEquals(LearningStatus.FAILED, state.status)
        assertEquals(1, explorer.captureCalls)
    }

    @Test
    fun tapAndCapture_requiresAnExistingCapturedPage() = runTest {
        val explorer = FakeAppExplorer(
            clickResults = mapOf(
                "0/0" to sampleExplorationResult(
                    pageId = "advanced_settings",
                    pageName = "Advanced",
                    timestamp = 20L,
                ),
            ),
        )
        val manager = DefaultLearningSessionManager(
            appExplorer = explorer,
            pageAnalysisPort = FakePageAnalysisPort(),
            skillLearner = FakeSkillLearner(sampleDraft()),
            sessionIdFactory = { "session-6" },
        )

        val sessionId = manager.startLearning(
            appPackage = "com.example.settings",
            appName = "Settings",
        )

        try {
            manager.tapAndCapture(sessionId, nodeId = "0/0", nodeDescription = "Advanced row")
            fail("Expected tapAndCapture to require a previously captured page.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("请先采集当前页面"))
        }

        val state = requireNotNull(manager.getSessionState(sessionId))
        assertEquals(LearningStatus.EXPLORING, state.status)
        assertTrue(state.exploredPages.isEmpty())
        assertTrue(state.errorMessage.orEmpty().contains("请先采集当前页面"))
    }

    private class FakeAppExplorer(
        private val captureResults: List<ExplorationResult?> = emptyList(),
        private val clickResults: Map<String, ExplorationResult?> = emptyMap(),
    ) : AppExplorer {
        val clickedNodeIds = mutableListOf<String>()
        var captureCalls: Int = 0
            private set

        override suspend fun captureCurrentPage(): ExplorationResult? {
            captureCalls += 1
            if (captureResults.isEmpty()) {
                return null
            }

            val index = (captureCalls - 1).coerceAtMost(captureResults.lastIndex)
            return captureResults[index]
        }

        override suspend fun performClick(nodeId: String): ExplorationResult? {
            clickedNodeIds += nodeId
            return clickResults[nodeId]
        }

        override suspend fun performBack(): ExplorationResult? = null
    }

    private class FakePageAnalysisPort : PageAnalysisPort {
        override suspend fun analyzePage(
            appPackage: String,
            pageTree: PageTreeSnapshot,
            screenshot: ByteArray?,
        ): PageAnalysisResult {
            return PageAnalysisResult(
                suggestedPageSpec = PageSpec(
                    pageId = pageTree.activityName.orEmpty().lowercase(),
                    pageName = pageTree.nodes.firstOrNull()?.children?.firstOrNull()?.text ?: "Unknown",
                    appPackage = appPackage,
                    activityName = pageTree.activityName,
                    matchRules = listOf(
                        PageMatchRule(type = "activity_name", value = pageTree.activityName.orEmpty()),
                    ),
                    availableActions = listOf("tap_primary"),
                    evidenceFields = mapOf("captured_at" to pageTree.timestamp.toString()),
                ),
                clickableElements = listOf(
                    ClickableElementSuggestion(
                        resourceId = "com.example.settings:id/primary",
                        text = pageTree.nodes.firstOrNull()?.children?.firstOrNull()?.text,
                        contentDescription = null,
                        suggestedActionName = "tap_primary",
                        suggestedDescription = "Tap primary",
                    ),
                ),
                navigationHints = listOf("Tap the primary row."),
            )
        }
    }

    private class FakeSkillLearner(
        private val draft: LearnedSkillDraft,
    ) : SkillLearner {
        var lastPages: List<PageLearningInput> = emptyList()
            private set

        override suspend fun generateSkillDraft(
            appPackage: String,
            appName: String,
            pages: List<PageLearningInput>,
        ): LearnedSkillDraft {
            lastPages = pages
            return draft
        }
    }
}

private fun sampleExplorationResult(
    appPackage: String = "com.example.settings",
    pageId: String,
    pageName: String,
    timestamp: Long,
    screenshot: ByteArray? = null,
): ExplorationResult {
    val activityName = pageId.replaceFirstChar { char ->
        if (char.isLowerCase()) {
            char.titlecase()
        } else {
            char.toString()
        }
    }

    return ExplorationResult(
        packageName = appPackage,
        activityName = activityName,
        pageTree = PageTreeSnapshot(
            packageName = appPackage,
            activityName = activityName,
            timestamp = timestamp,
            nodes = listOf(
                AccessibilityNodeSnapshot(
                    nodeId = "0",
                    className = "android.widget.FrameLayout",
                    text = null,
                    contentDescription = null,
                    resourceId = "com.example.settings:id/root",
                    isClickable = false,
                    isScrollable = true,
                    isEditable = false,
                    bounds = "0,0,1080,2400",
                    children = listOf(
                        AccessibilityNodeSnapshot(
                            nodeId = "0/0",
                            className = "android.widget.TextView",
                            text = pageName,
                            contentDescription = "$pageName entry",
                            resourceId = "com.example.settings:id/primary",
                            isClickable = true,
                            isScrollable = false,
                            isEditable = false,
                            bounds = "0,200,1080,320",
                            children = emptyList(),
                        ),
                    ),
                ),
            ),
        ),
        screenshot = screenshot,
        capturedAt = timestamp,
    )
}

private fun sampleDraft(): LearnedSkillDraft {
    return LearnedSkillDraft(
        manifest = SkillManifest(
            schemaVersion = "v1alpha1",
            skillId = "learned.com.example.settings",
            skillVersion = "0.1.0",
            skillType = "app",
            displayName = "Settings Learned Skill",
            owner = "learner",
            platform = "android",
            appPackage = "com.example.settings",
            defaultRiskLevel = RiskLevel.GUARDED,
            enabled = false,
            actions = listOf(
                SkillActionManifest(
                    actionId = "tap_primary",
                    displayName = "Tap Primary",
                    description = "Tap the primary entry",
                    executorType = "accessibility",
                    riskLevel = RiskLevel.GUARDED,
                    requiresConfirmation = true,
                    expectedOutcome = "The app reacts to the primary action.",
                ),
            ),
        ),
        pageGraph = PageGraph(
            appPackage = "com.example.settings",
            pages = emptyList(),
            transitions = emptyList(),
        ),
        bindings = listOf(
            SkillActionBinding(
                actionId = "tap_primary",
                intentAction = "",
            ),
        ),
        evidence = emptyList(),
    )
}

