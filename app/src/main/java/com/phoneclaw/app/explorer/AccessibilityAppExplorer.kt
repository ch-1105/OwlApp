package com.phoneclaw.app.explorer

import kotlinx.coroutines.delay

private const val DEFAULT_INITIAL_WAIT_MS = 100L
private const val DEFAULT_MAX_WAIT_MS = 1600L
private const val DEFAULT_CAPTURE_ATTEMPTS = 10

class AccessibilityAppExplorer(
    private val bridge: AccessibilityExplorerBridge = AccessibilityCaptureBridge,
    private val initialWaitMs: Long = DEFAULT_INITIAL_WAIT_MS,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
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
        var waitMs = initialWaitMs
        repeat(captureAttempts) {
            delay(waitMs)
            val snapshot = bridge.captureCurrentPageTree() ?: bridge.latestSnapshot.value
            if (snapshot != null && snapshot.timestamp != previousTimestamp) {
                return snapshot.toExplorationResult()
            }
            waitMs = (waitMs * 2).coerceAtMost(maxWaitMs)
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
