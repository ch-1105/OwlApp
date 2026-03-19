package com.phoneclaw.app.explorer

import kotlinx.coroutines.delay

private const val DEFAULT_CAPTURE_WAIT_MS = 150L
private const val DEFAULT_CAPTURE_ATTEMPTS = 8

class AccessibilityAppExplorer(
    private val bridge: AccessibilityExplorerBridge = AccessibilityCaptureBridge,
    private val captureWaitMs: Long = DEFAULT_CAPTURE_WAIT_MS,
    private val captureAttempts: Int = DEFAULT_CAPTURE_ATTEMPTS,
) : AppExplorer {
    override suspend fun captureCurrentPage(): ExplorationResult? {
        val snapshot = bridge.captureCurrentPageTree() ?: bridge.latestSnapshot.value ?: return null
        return snapshot.toExplorationResult()
    }

    override suspend fun performClick(nodeId: String): ExplorationResult? {
        val previousTimestamp = bridge.latestSnapshot.value?.timestamp
        if (!bridge.performClick(nodeId)) {
            return null
        }
        return awaitUpdatedSnapshot(previousTimestamp)
    }

    override suspend fun performBack(): ExplorationResult? {
        val previousTimestamp = bridge.latestSnapshot.value?.timestamp
        if (!bridge.performBack()) {
            return null
        }
        return awaitUpdatedSnapshot(previousTimestamp)
    }

    private suspend fun awaitUpdatedSnapshot(previousTimestamp: Long?): ExplorationResult? {
        repeat(captureAttempts) {
            val snapshot = bridge.captureCurrentPageTree() ?: bridge.latestSnapshot.value
            if (snapshot != null && snapshot.timestamp != previousTimestamp) {
                return snapshot.toExplorationResult()
            }
            delay(captureWaitMs)
        }
        return bridge.latestSnapshot.value?.toExplorationResult()
    }
}

private fun PageTreeSnapshot.toExplorationResult(): ExplorationResult {
    return ExplorationResult(
        packageName = packageName,
        activityName = activityName,
        pageTree = this,
        screenshot = null,
        capturedAt = timestamp,
    )
}
