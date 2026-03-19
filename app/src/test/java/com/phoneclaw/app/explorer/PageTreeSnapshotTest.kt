package com.phoneclaw.app.explorer

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PageTreeSnapshotTest {
    @Test
    fun nodeSnapshotInput_convertsToRecursiveSnapshot() {
        val snapshot = NodeSnapshotInput(
            nodeId = "0",
            className = "android.widget.FrameLayout",
            text = null,
            contentDescription = null,
            resourceId = "root",
            isClickable = false,
            isScrollable = true,
            isEditable = false,
            bounds = Rect(1, 2, 3, 4),
            children = listOf(
                NodeSnapshotInput(
                    nodeId = "0/0",
                    className = "android.widget.TextView",
                    text = "Hello",
                    contentDescription = "Greeting",
                    resourceId = "title",
                    isClickable = true,
                    isScrollable = false,
                    isEditable = false,
                    bounds = Rect(5, 6, 7, 8),
                    children = emptyList(),
                ),
            ),
        ).toSnapshot()

        assertEquals("0", snapshot.nodeId)
        assertEquals("1,2,3,4", snapshot.bounds)
        assertEquals(1, snapshot.children.size)
        assertEquals("0/0", snapshot.children.single().nodeId)
        assertEquals("Hello", snapshot.children.single().text)
        assertEquals("5,6,7,8", snapshot.children.single().bounds)
    }

    @Test
    fun pageTreeSnapshot_totalNodeCount_countsDescendants() {
        val snapshot = PageTreeSnapshot(
            packageName = "com.example.settings",
            activityName = "SettingsActivity",
            timestamp = 1L,
            nodes = listOf(
                AccessibilityNodeSnapshot(
                    nodeId = "0",
                    className = "Root",
                    text = null,
                    contentDescription = null,
                    resourceId = null,
                    isClickable = false,
                    isScrollable = false,
                    isEditable = false,
                    bounds = "0,0,10,10",
                    children = listOf(
                        AccessibilityNodeSnapshot(
                            nodeId = "0/0",
                            className = "Child",
                            text = null,
                            contentDescription = null,
                            resourceId = null,
                            isClickable = false,
                            isScrollable = false,
                            isEditable = false,
                            bounds = "0,0,5,5",
                            children = emptyList(),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(2, snapshot.totalNodeCount())
    }

    @Test
    fun pageTreeSnapshot_findNode_returnsMatchingDescendant() {
        val snapshot = PageTreeSnapshot(
            packageName = "com.example.settings",
            activityName = "SettingsActivity",
            timestamp = 1L,
            nodes = listOf(
                AccessibilityNodeSnapshot(
                    nodeId = "0",
                    className = "Root",
                    text = null,
                    contentDescription = null,
                    resourceId = null,
                    isClickable = false,
                    isScrollable = false,
                    isEditable = false,
                    bounds = "0,0,10,10",
                    children = listOf(
                        AccessibilityNodeSnapshot(
                            nodeId = "0/0",
                            className = "Child",
                            text = "Open settings",
                            contentDescription = null,
                            resourceId = "title",
                            isClickable = true,
                            isScrollable = false,
                            isEditable = false,
                            bounds = "0,0,5,5",
                            children = emptyList(),
                        ),
                    ),
                ),
            ),
        )

        assertNotNull(snapshot.findNode("0/0"))
        assertEquals("Open settings", snapshot.findNode("0/0")?.text)
        assertNull(snapshot.findNode("0/1"))
    }
}
