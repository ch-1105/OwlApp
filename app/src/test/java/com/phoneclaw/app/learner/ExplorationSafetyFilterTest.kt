package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorationSafetyFilterTest {
    @Test
    fun isSafeToClick_allowsNormalClickableElements() {
        assertTrue(ExplorationSafetyFilter.isSafeToClick(node(text = "Settings", clickable = true)))
        assertTrue(ExplorationSafetyFilter.isSafeToClick(node(text = "Profile", clickable = true)))
        assertTrue(ExplorationSafetyFilter.isSafeToClick(node(text = "Home", clickable = true)))
    }

    @Test
    fun isSafeToClick_blocksNonClickableElements() {
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "Label", clickable = false)))
    }

    @Test
    fun isSafeToClick_blocksEditableFields() {
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "Input", clickable = true, editable = true)))
    }

    @Test
    fun isSafeToClick_blocksDangerousChineseLabels() {
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "发送", clickable = true)))
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "删除消息", clickable = true)))
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "确认付款", clickable = true)))
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "退出登录", clickable = true)))
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "转账给好友", clickable = true)))
    }

    @Test
    fun isSafeToClick_blocksDangerousEnglishLabels() {
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "Send Message", clickable = true)))
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "Delete", clickable = true)))
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "Pay Now", clickable = true)))
        assertFalse(ExplorationSafetyFilter.isSafeToClick(node(text = "Sign Out", clickable = true)))
    }

    @Test
    fun isSafeToClick_checksContentDescriptionToo() {
        assertFalse(ExplorationSafetyFilter.isSafeToClick(
            node(text = null, contentDescription = "发送消息", clickable = true),
        ))
    }

    @Test
    fun isSafeToClick_allowsUnlabeledClickables() {
        assertTrue(ExplorationSafetyFilter.isSafeToClick(
            node(text = null, contentDescription = null, clickable = true),
        ))
    }
}

private fun node(
    text: String? = null,
    contentDescription: String? = null,
    clickable: Boolean = false,
    editable: Boolean = false,
): AccessibilityNodeSnapshot {
    return AccessibilityNodeSnapshot(
        nodeId = "0/0",
        className = "android.widget.Button",
        text = text,
        contentDescription = contentDescription,
        resourceId = null,
        isClickable = clickable,
        isScrollable = false,
        isEditable = editable,
        bounds = "0,0,100,50",
        children = emptyList(),
    )
}
