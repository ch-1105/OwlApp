package com.phoneclaw.app.explorer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAppExplorerTest {
    @Test
    fun captureCurrentPage_returnsExplorationResult() = runTest {
        val snapshot = testSnapshot(timestamp = 10L)
        val explorer = AccessibilityAppExplorer(
            bridge = FakeAccessibilityExplorerBridge(capturedSnapshot = snapshot),
            initialWaitMs = 0L,
        )

        val result = explorer.captureCurrentPage()

        assertEquals("com.example.settings", result?.packageName)
        assertEquals(snapshot, result?.pageTree)
        assertEquals(10L, result?.capturedAt)
        assertNull(result?.screenshot)
    }

    @Test
    fun performClick_returnsUpdatedSnapshotAfterAction() = runTest {
        val beforeSnapshot = testSnapshot(timestamp = 10L)
        val afterSnapshot = testSnapshot(timestamp = 20L, activityName = "DetailActivity")
        val bridge = FakeAccessibilityExplorerBridge(
            capturedSnapshot = beforeSnapshot,
            clickResult = true,
            snapshotAfterClick = afterSnapshot,
        )
        val explorer = AccessibilityAppExplorer(
            bridge = bridge,
            initialWaitMs = 0L,
        )

        val result = explorer.performClick("0/0")

        assertTrue(bridge.clickedNodeIds.contains("0/0"))
        assertEquals(afterSnapshot, result?.pageTree)
        assertEquals("DetailActivity", result?.activityName)
    }

    @Test
    fun performBack_returnsNullWhenBridgeCannotNavigateBack() = runTest {
        val explorer = AccessibilityAppExplorer(
            bridge = FakeAccessibilityExplorerBridge(
                capturedSnapshot = testSnapshot(timestamp = 10L),
                backResult = false,
            ),
            initialWaitMs = 0L,
        )

        val result = explorer.performBack()

        assertNull(result)
    }
}

private class FakeAccessibilityExplorerBridge(
    capturedSnapshot: PageTreeSnapshot?,
    private val clickResult: Boolean = true,
    private val backResult: Boolean = true,
    private val snapshotAfterClick: PageTreeSnapshot? = null,
    private val snapshotAfterBack: PageTreeSnapshot? = null,
) : AccessibilityExplorerBridge {
    private val latestSnapshotState = MutableStateFlow(capturedSnapshot)
    val clickedNodeIds = mutableListOf<String>()

    override val latestSnapshot: StateFlow<PageTreeSnapshot?> = latestSnapshotState

    override fun captureCurrentPageTree(): PageTreeSnapshot? {
        return latestSnapshotState.value
    }

    override fun capturePageTreeForPackage(targetPackage: String): PageTreeSnapshot? {
        val snapshot = latestSnapshotState.value
        return if (snapshot?.packageName == targetPackage) snapshot else null
    }

    override fun performClick(nodeId: String): Boolean {
        clickedNodeIds += nodeId
        if (clickResult && snapshotAfterClick != null) {
            latestSnapshotState.value = snapshotAfterClick
        }
        return clickResult
    }

    override fun performBack(): Boolean {
        if (backResult && snapshotAfterBack != null) {
            latestSnapshotState.value = snapshotAfterBack
        }
        return backResult
    }

    override fun performHome(): Boolean {
        return false
    }
}

private fun testSnapshot(
    timestamp: Long,
    activityName: String? = "SettingsActivity",
): PageTreeSnapshot {
    return PageTreeSnapshot(
        packageName = "com.example.settings",
        activityName = activityName,
        timestamp = timestamp,
        nodes = listOf(
            AccessibilityNodeSnapshot(
                nodeId = "0",
                className = "android.widget.FrameLayout",
                text = null,
                contentDescription = null,
                resourceId = "root",
                isClickable = false,
                isScrollable = true,
                isEditable = false,
                bounds = "0,0,100,100",
                children = listOf(
                    AccessibilityNodeSnapshot(
                        nodeId = "0/0",
                        className = "android.widget.TextView",
                        text = "Open settings",
                        contentDescription = null,
                        resourceId = "title",
                        isClickable = true,
                        isScrollable = false,
                        isEditable = false,
                        bounds = "0,0,50,50",
                        children = emptyList(),
                    ),
                ),
            ),
        ),
    )
}
