package com.phoneclaw.app.explorer

data class PageTreeSnapshot(
    val packageName: String,
    val activityName: String?,
    val timestamp: Long,
    val nodes: List<AccessibilityNodeSnapshot>,
)

data class AccessibilityNodeSnapshot(
    val nodeId: String,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val bounds: String,
    val children: List<AccessibilityNodeSnapshot>,
)

internal data class NodeSnapshotInput(
    val nodeId: String,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val bounds: android.graphics.Rect,
    val children: List<NodeSnapshotInput>,
)

internal fun NodeSnapshotInput.toSnapshot(): AccessibilityNodeSnapshot {
    return AccessibilityNodeSnapshot(
        nodeId = nodeId,
        className = className,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        isClickable = isClickable,
        isScrollable = isScrollable,
        isEditable = isEditable,
        bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
        children = children.map { it.toSnapshot() },
    )
}

fun PageTreeSnapshot.totalNodeCount(): Int {
    return nodes.sumOf { it.totalNodeCount() }
}

fun PageTreeSnapshot.findNode(nodeId: String): AccessibilityNodeSnapshot? {
    return nodes.firstNotNullOfOrNull { node -> node.findDescendant(nodeId) }
}

private fun AccessibilityNodeSnapshot.totalNodeCount(): Int {
    return 1 + children.sumOf { it.totalNodeCount() }
}

private fun AccessibilityNodeSnapshot.findDescendant(nodeId: String): AccessibilityNodeSnapshot? {
    if (this.nodeId == nodeId) {
        return this
    }
    return children.firstNotNullOfOrNull { child -> child.findDescendant(nodeId) }
}
