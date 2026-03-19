package com.phoneclaw.app.contracts

const val CONTRACT_SCHEMA_VERSION = "v1alpha1"

enum class RiskLevel {
    SAFE,
    GUARDED,
    SENSITIVE,
}

enum class TaskState {
    RECEIVED,
    PLANNING,
    AWAITING_CONFIRMATION,
    APPROVED,
    REFUSED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class SkillManifest(
    val schemaVersion: String,
    val skillId: String,
    val skillVersion: String,
    val skillType: String,
    val displayName: String,
    val owner: String,
    val platform: String,
    val appPackage: String? = null,
    val defaultRiskLevel: RiskLevel,
    val enabled: Boolean,
    val actions: List<SkillActionManifest>,
)

data class SkillActionManifest(
    val actionId: String,
    val displayName: String,
    val description: String,
    val executorType: String,
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean,
    val expectedOutcome: String,
    val enabled: Boolean = true,
    val exampleUtterances: List<String> = emptyList(),
    val matchKeywords: List<String> = emptyList(),
)

data class ActionSpec(
    val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
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
    val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
    val requestId: String,
    val taskId: String,
    val actionSpec: ActionSpec,
    val permissionSnapshot: List<PermissionState> = emptyList(),
)

data class ExecutionResult(
    val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
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
    val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
    val requestId: String,
    val taskId: String,
    val taskType: String,
    val inputMessages: List<String>,
    val allowCloud: Boolean,
    val preferredProvider: String,
)

data class PlannedActionPayload(
    val actionId: String,
    val skillId: String,
    val intentSummary: String,
    val params: Map<String, String> = emptyMap(),
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean,
    val executorType: String,
    val expectedOutcome: String,
)

enum class ModelErrorKind {
    DISABLED,
    NETWORK,
    INVALID_RESPONSE,
    PROVIDER_FAILURE,
    PROVIDER_REFUSED,
    UNKNOWN,
}

data class ModelResponse(
    val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
    val requestId: String,
    val provider: String,
    val modelId: String,
    val outputText: String,
    val plannedAction: PlannedActionPayload? = null,
    val error: String? = null,
    val errorKind: ModelErrorKind? = null,
)

data class PlanningTrace(
    val provider: String,
    val modelId: String,
    val outputText: String,
    val errorMessage: String? = null,
    val errorKind: ModelErrorKind? = null,
    val usedRemote: Boolean,
)

data class PageSpec(
    val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
    val pageId: String,
    val pageName: String,
    val appPackage: String,
    val activityName: String?,
    val matchRules: List<PageMatchRule>,
    val availableActions: List<String>,
    val evidenceFields: Map<String, String> = emptyMap(),
)

data class PageMatchRule(
    val type: String,
    val value: String,
)

data class PageGraph(
    val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
    val appPackage: String,
    val pages: List<PageSpec>,
    val transitions: List<PageTransition>,
)

data class PageTransition(
    val fromPageId: String,
    val toPageId: String,
    val triggerActionId: String,
    val triggerNodeDescription: String,
)

data class ModelProfile(
    val provider: String,
    val modelId: String,
    val supportsToolCalling: Boolean,
    val supportsStructuredOutput: Boolean,
    val supportsMultimodal: Boolean,
    val preferredForPlanning: Boolean,
    val preferredForSummary: Boolean,
    val preferredForPageUnderstanding: Boolean,
    val allowSensitiveData: Boolean,
)

data class TaskSnapshot(
    val taskId: String,
    val state: TaskState,
    val userMessage: String,
    val actionSpec: ActionSpec? = null,
    val planningTrace: PlanningTrace? = null,
    val executionResult: ExecutionResult? = null,
    val errorMessage: String? = null,
)
