package com.phoneclaw.app.explorer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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
        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "Accessibility service connected service=${System.identityHashCode(this)} initialPackage=${initialSnapshot?.packageName} initialActivity=${initialSnapshot?.activityName}",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPackageName = event.packageName?.toString()
        if (!eventPackageName.isNullOrBlank()) {
            lastPackageName = eventPackageName
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            lastActivityName = event.className?.toString() ?: lastActivityName
        }

        if (!shouldCaptureSnapshot(event.eventType)) return

        val snapshot = captureCurrentPageTree()
        AccessibilityCaptureBridge.publishSnapshot(snapshot)
        if (snapshot != null) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Captured snapshot event=${eventTypeName(event.eventType)} package=${snapshot.packageName} activity=${snapshot.activityName} nodes=${snapshot.totalNodeCount()}",
            )
            return
        }

        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "Snapshot capture returned null event=${eventTypeName(event.eventType)} eventPackage=$eventPackageName eventClass=${event.className}",
        )
    }

    override fun onInterrupt() {
        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "Accessibility service interrupted service=${System.identityHashCode(this)} lastPackage=$lastPackageName lastActivity=$lastActivityName",
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "Accessibility service unbound service=${System.identityHashCode(this)} lastPackage=$lastPackageName lastActivity=$lastActivityName",
        )
        AccessibilityCaptureBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "Accessibility service destroyed service=${System.identityHashCode(this)} lastPackage=$lastPackageName lastActivity=$lastActivityName",
        )
        AccessibilityCaptureBridge.detach(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    fun captureCurrentPageTree(): PageTreeSnapshot? {
        val rootNode = rootInActiveWindow ?: findRootFromWindows()
        if (rootNode == null) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "captureCurrentPageTree: rootInActiveWindow=null windowsFallback=null lastPackage=$lastPackageName lastActivity=$lastActivityName",
            )
            return null
        }

        return try {
            buildSnapshot(rootNode)
        } finally {
            rootNode.recycle()
        }
    }

    @Suppress("DEPRECATION")
    fun capturePageTreeForPackage(targetPackage: String): PageTreeSnapshot? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            if (activeRoot.packageName?.toString() == targetPackage) {
                return try {
                    buildSnapshot(activeRoot)
                } finally {
                    activeRoot.recycle()
                }
            }
            activeRoot.recycle()
        }

        val windowRoot = findRootFromWindows(targetPackage)
        if (windowRoot == null) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "capturePageTreeForPackage: target=$targetPackage not found in windows lastPackage=$lastPackageName",
            )
            return null
        }

        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "capturePageTreeForPackage: found target=$targetPackage via windows fallback",
        )
        return try {
            buildSnapshot(windowRoot)
        } finally {
            windowRoot.recycle()
        }
    }

    @Suppress("DEPRECATION")
    fun performClick(nodeId: String): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "performClick: rootInActiveWindow=null nodeId=$nodeId lastPackage=$lastPackageName",
            )
            return false
        }
        var targetNode: AccessibilityNodeInfo? = null
        var clickableNode: AccessibilityNodeInfo? = null
        return try {
            targetNode = rootNode.findNodeById(nodeId)
            clickableNode = targetNode?.clickableAncestor()
            val performed = clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "performClick: nodeId=$nodeId targetFound=${targetNode != null} clickableFound=${clickableNode != null} performed=$performed",
            )
            performed
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
        val performed = performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(ACCESSIBILITY_LOG_TAG, "performBack: performed=$performed")
        return performed
    }

    fun performHome(): Boolean {
        val performed = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(ACCESSIBILITY_LOG_TAG, "performHome: performed=$performed")
        return performed
    }

    private fun buildSnapshot(rootNode: AccessibilityNodeInfo): PageTreeSnapshot? {
        val rootPackageName = rootNode.packageName?.toString()
        logSnapshotPackageMismatch(rootPackageName, lastPackageName)
        val metadata = resolveSnapshotMetadata(
            rootPackageName = rootPackageName,
            eventPackageName = lastPackageName,
            eventActivityName = lastActivityName,
        )
        if (metadata == null) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "buildSnapshot: metadata is null rootPackage=$rootPackageName lastPackage=$lastPackageName lastActivity=$lastActivityName",
            )
            return null
        }

        return PageTreeSnapshot(
            packageName = metadata.packageName,
            activityName = metadata.activityName,
            timestamp = System.currentTimeMillis(),
            nodes = listOf(rootNode.captureInput(ROOT_NODE_ID).toSnapshot()),
        )
    }

    @Suppress("DEPRECATION")
    private fun findRootFromWindows(targetPackage: String? = null): AccessibilityNodeInfo? {
        val windowList = try {
            windows
        } catch (_: Exception) {
            return null
        }
        if (windowList.isNullOrEmpty()) return null

        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (targetPackage == null || root.packageName?.toString() == targetPackage) {
                return root
            }
            root.recycle()
        }
        return null
    }
}

internal data class SnapshotMetadata(
    val packageName: String,
    val activityName: String?,
)

internal fun resolveSnapshotMetadata(
    rootPackageName: String?,
    eventPackageName: String?,
    eventActivityName: String?,
): SnapshotMetadata? {
    val packageName = rootPackageName ?: eventPackageName ?: return null
    val activityName = if (packageName == eventPackageName) {
        eventActivityName
    } else {
        null
    }

    return SnapshotMetadata(
        packageName = packageName,
        activityName = activityName,
    )
}

private fun shouldCaptureSnapshot(eventType: Int): Boolean {
    return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
}

private fun logSnapshotPackageMismatch(
    rootPackageName: String?,
    eventPackageName: String?,
) {
    if (rootPackageName.isNullOrBlank()) return
    if (eventPackageName.isNullOrBlank()) return
    if (rootPackageName == eventPackageName) return

    Log.d(
        ACCESSIBILITY_LOG_TAG,
        "Snapshot package mismatch event=$eventPackageName root=$rootPackageName",
    )
}

private fun eventTypeName(eventType: Int): String {
    return when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
        else -> eventType.toString()
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

