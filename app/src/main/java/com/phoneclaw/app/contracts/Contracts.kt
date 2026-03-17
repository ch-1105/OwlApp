package com.phoneclaw.app.contracts

enum class RiskLevel {
    SAFE,
    GUARDED,
    SENSITIVE,
}

enum class TaskState {
    RECEIVED,
    PLANNING,
    REFUSED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
}

data class SkillManifest(
    val skillId: String,
    val skillVersion: String,
    val displayName: String,
    val actions: List<String>,
)

data class ActionSpec(
    val actionId: String,
    val skillId: String,
    val taskId: String,
    val intentSummary: String,
    val params: Map<String, String>,
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean,
    val executorType: String,
    val expectedOutcome: String,
)

data class PermissionState(
    val permissionName: String,
    val granted: Boolean,
)

data class VerificationResult(
    val passed: Boolean,
    val reason: String,
)

data class ExecutionRequest(
    val requestId: String,
    val taskId: String,
    val actionSpec: ActionSpec,
    val permissionSnapshot: List<PermissionState> = emptyList(),
)

data class ExecutionResult(
    val requestId: String,
    val taskId: String,
    val actionId: String,
    val status: String,
    val resultSummary: String,
    val outputData: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    val verification: VerificationResult,
)

data class ModelRequest(
    val requestId: String,
    val taskId: String,
    val taskType: String,
    val inputMessages: List<String>,
    val allowCloud: Boolean,
    val preferredProvider: String,
)

data class ModelResponse(
    val requestId: String,
    val provider: String,
    val modelId: String,
    val outputText: String,
    val structuredOutput: String? = null,
    val error: String? = null,
)

data class TaskSnapshot(
    val taskId: String,
    val state: TaskState,
    val userMessage: String,
    val actionSpec: ActionSpec? = null,
    val executionResult: ExecutionResult? = null,
    val errorMessage: String? = null,
)

