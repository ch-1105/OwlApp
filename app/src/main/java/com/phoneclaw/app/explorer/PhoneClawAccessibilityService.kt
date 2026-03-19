package com.phoneclaw.app.explorer

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val ACCESSIBILITY_LOG_TAG = "PhoneClawA11y"

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
                nodes = listOf(rootNode.captureInput().toSnapshot()),
            )
        } finally {
            rootNode.recycle()
        }
    }
}

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.captureInput(): NodeSnapshotInput {
    val bounds = Rect().also(::getBoundsInScreen)
    val childInputs = buildList {
        for (index in 0 until childCount) {
            val childNode = getChild(index) ?: continue
            try {
                add(childNode.captureInput())
            } finally {
                childNode.recycle()
            }
        }
    }

    return NodeSnapshotInput(
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

