package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.RiskLevel
import java.util.UUID

sealed interface PlanningOutcome {
    data class PlannedAction(val actionSpec: ActionSpec) : PlanningOutcome
    data class ClarificationNeeded(val question: String) : PlanningOutcome
    data class Refused(val reason: String) : PlanningOutcome
}

interface CloudModelAdapter {
    suspend fun planAction(request: ModelRequest): ModelResponse
}

interface PlanningService {
    suspend fun planAction(taskId: String, userMessage: String): PlanningOutcome
}

class StubCloudModelAdapter : CloudModelAdapter {
    override suspend fun planAction(request: ModelRequest): ModelResponse {
        val normalized = request.inputMessages.joinToString(" ").lowercase()
        val wantsSettings =
            normalized.contains("settings") ||
                normalized.contains("设置") ||
                normalized.contains("system setting")

        return if (wantsSettings) {
            ModelResponse(
                requestId = request.requestId,
                provider = "stub-cloud",
                modelId = "stub-settings-router",
                outputText = "Planned open_system_settings",
                plannedAction = PlannedActionPayload(
                    actionId = "open_system_settings",
                    skillId = "system.settings",
                    intentSummary = "Open Android system settings",
                    riskLevel = RiskLevel.SAFE,
                    requiresConfirmation = false,
                    executorType = "intent",
                    expectedOutcome = "System settings becomes foreground",
                ),
            )
        } else {
            ModelResponse(
                requestId = request.requestId,
                provider = "stub-cloud",
                modelId = "stub-settings-router",
                outputText = "Need clarification",
                error = "Only the open_system_settings path is scaffolded in this build.",
            )
        }
    }
}

class DefaultPlanningService(
    private val cloudModelAdapter: CloudModelAdapter,
    private val allowCloud: Boolean,
    private val preferredProvider: String,
) : PlanningService {
    override suspend fun planAction(taskId: String, userMessage: String): PlanningOutcome {
        val request = ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = taskId,
            taskType = "plan_action",
            inputMessages = listOf(userMessage),
            allowCloud = allowCloud,
            preferredProvider = preferredProvider,
        )

        val response = cloudModelAdapter.planAction(request)
        response.plannedAction?.let { payload ->
            return PlanningOutcome.PlannedAction(payload.toActionSpec(taskId))
        }

        if (response.error != null) {
            return PlanningOutcome.Refused(response.error)
        }

        return PlanningOutcome.ClarificationNeeded(
            response.outputText.ifBlank {
                "Please clarify what settings page you want to open."
            },
        )
    }

    private fun PlannedActionPayload.toActionSpec(taskId: String): ActionSpec {
        return ActionSpec(
            actionId = actionId,
            skillId = skillId,
            taskId = taskId,
            intentSummary = intentSummary,
            params = params,
            riskLevel = riskLevel,
            requiresConfirmation = requiresConfirmation,
            executorType = executorType,
            expectedOutcome = expectedOutcome,
        )
    }
}
