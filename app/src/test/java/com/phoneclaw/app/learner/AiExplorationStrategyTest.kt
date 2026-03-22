package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExplorationStrategyTest {

    @Test
    fun parseOrderedIndices_validJson() {
        val json = """{"indices": [3, 1, 2]}"""
        val result = parseOrderedIndices(json, candidateCount = 5)
        assertEquals(listOf(2, 0, 1), result)
    }

    @Test
    fun parseOrderedIndices_outOfRange() {
        val json = """{"indices": [1, 99, 2, 0, -1]}"""
        val result = parseOrderedIndices(json, candidateCount = 3)
        assertEquals(listOf(0, 1), result)
    }

    @Test
    fun parseOrderedIndices_duplicates() {
        val json = """{"indices": [2, 2, 1, 1]}"""
        val result = parseOrderedIndices(json, candidateCount = 3)
        assertEquals(listOf(1, 0), result)
    }

    @Test
    fun parseOrderedIndices_invalidJson() {
        val result = parseOrderedIndices("not json", candidateCount = 3)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseOrderedIndices_missingField() {
        val json = """{"order": [1, 2]}"""
        val result = parseOrderedIndices(json, candidateCount = 3)
        assertTrue(result.isEmpty())
    }

    @Test
    fun reorder_appliesModelOrder() {
        val a = ClickCandidate("a", "Alpha")
        val b = ClickCandidate("b", "Beta")
        val c = ClickCandidate("c", "Charlie")
        val candidates = listOf(a, b, c)

        val result = reorder(candidates, listOf(2, 0))
        assertEquals(listOf(c, a, b), result)
    }

    @Test
    fun reorder_appendsUnselected() {
        val a = ClickCandidate("a", "Alpha")
        val b = ClickCandidate("b", "Beta")
        val c = ClickCandidate("c", "Charlie")
        val d = ClickCandidate("d", "Delta")
        val candidates = listOf(a, b, c, d)

        val result = reorder(candidates, listOf(2))
        assertEquals(listOf(c, a, b, d), result)
    }

    @Test
    fun reorder_emptyIndices() {
        val a = ClickCandidate("a", "Alpha")
        val b = ClickCandidate("b", "Beta")
        val candidates = listOf(a, b)

        val result = reorder(candidates, emptyList())
        assertEquals(candidates, result)
    }

    @Test
    fun buildPrompt_includesPageInfo() {
        val context = testContext(
            pageName = "Settings",
            activityName = "SettingsActivity",
            visitedNames = listOf("Home", "Profile"),
            stepsRemaining = 15,
            depthRemaining = 3,
        )
        val candidates = listOf(
            ClickCandidate("0/1", "Wi-Fi"),
            ClickCandidate("0/2", "Bluetooth"),
        )

        val prompt = buildPrompt(context, candidates)

        assertTrue(prompt.contains("SettingsActivity"))
        assertTrue(prompt.contains("Settings"))
        assertTrue(prompt.contains("Home, Profile"))
        assertTrue(prompt.contains("Steps remaining: 15"))
        assertTrue(prompt.contains("Depth remaining: 3"))
        assertTrue(prompt.contains("Wi-Fi"))
        assertTrue(prompt.contains("Bluetooth"))
        assertTrue(prompt.contains("[0/1]"))
        assertTrue(prompt.contains("[0/2]"))
    }

    @Test
    fun buildPrompt_noVisitedPagesOmitsSection() {
        val context = testContext(visitedNames = emptyList())
        val candidates = listOf(ClickCandidate("0/0", "Start"))

        val prompt = buildPrompt(context, candidates)

        assertTrue(!prompt.contains("Already visited"))
    }
}

private fun testContext(
    pageName: String = "TestPage",
    activityName: String = "TestActivity",
    visitedNames: List<String> = emptyList(),
    stepsRemaining: Int = 20,
    depthRemaining: Int = 3,
): ExplorationContext {
    val pageTree = PageTreeSnapshot(
        packageName = "com.test.app",
        activityName = activityName,
        timestamp = 1L,
        nodes = emptyList(),
    )
    val analysis = PageAnalysisResult(
        suggestedPageSpec = PageSpec(
            pageId = "test_page",
            pageName = pageName,
            appPackage = "com.test.app",
            activityName = activityName,
            matchRules = listOf(PageMatchRule(type = "activity_name", value = activityName)),
            availableActions = emptyList(),
        ),
        clickableElements = emptyList(),
        navigationHints = emptyList(),
    )
    return ExplorationContext(
        currentPage = pageTree,
        currentAnalysis = analysis,
        visitedPageIds = visitedNames.map { it.lowercase() }.toSet(),
        visitedPageNames = visitedNames,
        transitionsRecorded = 0,
        stepsRemaining = stepsRemaining,
        depthRemaining = depthRemaining,
    )
}
