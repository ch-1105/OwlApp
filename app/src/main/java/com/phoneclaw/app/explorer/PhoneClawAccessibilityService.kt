package com.phoneclaw.app.explorer

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val ACCESSIBILITY_LOG_TAG = "PhoneClawA11y"
private const val ROOT_NODE_ID = "0"
private const val NODE_ID_SEPARATOR = "/"

class PhoneClawAccessibilityService : AccessibilityService() {
    private var lastPackageName: String? = null
    private var lastActivityName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityCaptureBridge.attach(this)
        val initialSnapshot = captureCurrentPageTree()
        AccessibilityCaptureBridge.publishSnapshot(initialSnapshot)
        Log.d(ACCESSIBILITY_LOG_TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        lastPackageName = event.packageName?.toString() ?: lastPackageName
        lastActivityName = event.className?.toString() ?: lastActivityName

        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val snapshot = captureCurrentPageTree()
            AccessibilityCaptureBridge.publishSnapshot(snapshot)
            if (snapshot != null) {
                Log.d(
                    ACCESSIBILITY_LOG_TAG,
                    "Captured snapshot package=${snapshot.packageName} activity=${snapshot.activityName} nodes=${snapshot.totalNodeCount()}",
                )
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityCaptureBridge.detach(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    fun captureCurrentPageTree(): PageTreeSnapshot? {
        val rootNode = rootInActiveWindow ?: return null
        return try {
            PageTreeSnapshot(
                packageName = lastPackageName ?: rootNode.packageName?.toString() ?: return null,
                activityName = lastActivityName,
                timestamp = System.currentTimeMillis(),
                nodes = listOf(rootNode.captureInput(ROOT_NODE_ID).toSnapshot()),
            )
        } finally {
            rootNode.recycle()
        }
    }

    @Suppress("DEPRECATION")
    fun performClick(nodeId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        var targetNode: AccessibilityNodeInfo? = null
        var clickableNode: AccessibilityNodeInfo? = null
        return try {
            targetNode = rootNode.findNodeById(nodeId)
            clickableNode = targetNode?.clickableAncestor()
            clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } finally {
            if (clickableNode != null && clickableNode !== targetNode && clickableNode !== rootNode) {
                clickableNode.recycle()
            }
            if (targetNode != null && targetNode !== rootNode) {
                targetNode.recycle()
            }
            rootNode.recycle()
        }
    }

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
}

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.captureInput(nodeId: String): NodeSnapshotInput {
    val bounds = Rect().also(::getBoundsInScreen)
    val childInputs = buildList {
        for (index in 0 until childCount) {
            val childNode = getChild(index) ?: continue
            try {
                add(childNode.captureInput("$nodeId$NODE_ID_SEPARATOR$index"))
            } finally {
                childNode.recycle()
            }
        }
    }

    return NodeSnapshotInput(
        nodeId = nodeId,
        className = className?.toString(),
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        resourceId = viewIdResourceName,
        isClickable = isClickable,
        isScrollable = isScrollable,
        isEditable = isEditable,
        bounds = Rect(bounds),
        children = childInputs,
    )
}

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.findNodeById(nodeId: String): AccessibilityNodeInfo? {
    val indexes = nodeId.split(NODE_ID_SEPARATOR)
    if (indexes.isEmpty() || indexes.first() != ROOT_NODE_ID) {
        return null
    }

    var current: AccessibilityNodeInfo = this
    for (segment in indexes.drop(1)) {
        val childIndex = segment.toIntOrNull() ?: return null.also {
            if (current !== this) {
                current.recycle()
            }
        }
        val nextNode = current.getChild(childIndex) ?: return null.also {
            if (current !== this) {
                current.recycle()
            }
        }
        if (current !== this) {
            current.recycle()
        }
        current = nextNode
    }

    return current
}

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo {
    if (isClickable) {
        return this
    }

    var current = this
    while (true) {
        val parent = current.parent ?: return current
        if (parent.isClickable) {
            if (current !== this) {
                current.recycle()
            }
            return parent
        }
        if (current !== this) {
            current.recycle()
        }
        current = parent
    }
}
