package com.phoneclaw.app.ui.explore

import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import com.phoneclaw.app.learner.ExploredPage
import com.phoneclaw.app.learner.LearningSessionState
import com.phoneclaw.app.learner.LearningStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreScreenLogicTest {
    @Test
    fun learningNodeSourceSnapshot_returnsLastCapturedSessionPage() {
        val firstPage = exploredPage(pageId = "page_one", pageName = "Page One", timestamp = 1L)
        val secondPage = exploredPage(pageId = "page_two", pageName = "Page Two", timestamp = 2L)
        val uiState = ExploreUiState(
            activeSessionId = "session-1",
            activeSession = LearningSessionState(
                appPackage = "com.example.settings",
                appName = "Settings",
                exploredPages = listOf(firstPage, secondPage),
                transitions = emptyList(),
                status = LearningStatus.EXPLORING,
            ),
        )

        val sourceSnapshot = learningNodeSourceSnapshot(uiState)

        assertEquals(secondPage.pageTree, sourceSnapshot)
    }

    @Test
    fun learningNodeSourceSnapshot_returnsNullWithoutActiveSession() {
        assertNull(learningNodeSourceSnapshot(ExploreUiState()))
    }
}

private fun exploredPage(
    pageId: String,
    pageName: String,
    timestamp: Long,
): ExploredPage {
    val pageTree = PageTreeSnapshot(
        packageName = "com.example.settings",
        activityName = pageName,
        timestamp = timestamp,
        nodes = listOf(
            AccessibilityNodeSnapshot(
                nodeId = pageId,
                className = "android.widget.TextView",
                text = pageName,
                contentDescription = null,
                resourceId = "com.example.settings:id/$pageId",
                isClickable = true,
                isScrollable = false,
                isEditable = false,
                bounds = "0,0,100,100",
                children = emptyList(),
            ),
        ),
    )

    return ExploredPage(
        pageTree = pageTree,
        analysis = PageAnalysisResult(
            suggestedPageSpec = PageSpec(
                pageId = pageId,
                pageName = pageName,
                appPackage = "com.example.settings",
                activityName = pageName,
                matchRules = listOf(
                    PageMatchRule(type = "activity_name", value = pageName),
                ),
                availableActions = listOf("tap_primary"),
            ),
            clickableElements = emptyList(),
            navigationHints = emptyList(),
        ),
        capturedAt = timestamp,
        screenshot = null,
        screenshotPath = null,
        arrivedBy = null,
    )
}
