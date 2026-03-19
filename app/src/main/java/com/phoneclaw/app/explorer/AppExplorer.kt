package com.phoneclaw.app.explorer

data class ExplorationResult(
    val packageName: String,
    val activityName: String?,
    val pageTree: PageTreeSnapshot,
    val screenshot: ByteArray?,
    val capturedAt: Long,
)

interface AppExplorer {
    suspend fun captureCurrentPage(): ExplorationResult?

    suspend fun performClick(nodeId: String): ExplorationResult?

    suspend fun performBack(): ExplorationResult?
}
