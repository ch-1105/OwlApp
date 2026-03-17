package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
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
                structuredOutput = "open_system_settings",
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
) : PlanningService {
    override suspend fun planAction(taskId: String, userMessage: String): PlanningOutcome {
        val request = ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = taskId,
            taskType = "plan_action",
            inputMessages = listOf(userMessage),
            allowCloud = true,
            preferredProvider = "stub-cloud",
        )

        val response = cloudModelAdapter.planAction(request)
        return when {
            response.structuredOutput == "open_system_settings" -> {
                PlanningOutcome.PlannedAction(
                    ActionSpec(
                        actionId = "open_system_settings",
                        skillId = "system.settings",
                        taskId = taskId,
                        intentSummary = "Open Android system settings",
                        params = emptyMap(),
                        riskLevel = RiskLevel.SAFE,
                        requiresConfirmation = false,
                        executorType = "intent",
                        expectedOutcome = "System settings becomes foreground",
                    ),
                )
            }

            response.error != null -> PlanningOutcome.Refused(response.error)
            else -> PlanningOutcome.ClarificationNeeded("Please clarify what settings page you want to open.")
        }
    }
}

