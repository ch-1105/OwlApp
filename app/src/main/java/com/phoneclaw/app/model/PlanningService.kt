package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.skills.SkillRegistry
import java.util.UUID

sealed interface PlanningOutcome {
    data class PlannedAction(val actionSpec: ActionSpec) : PlanningOutcome
    data class ClarificationNeeded(val question: String) : PlanningOutcome
    data class Refused(val reason: String) : PlanningOutcome
}

data class PlanningResult(
    val outcome: PlanningOutcome,
    val trace: PlanningTrace,
)

interface CloudModelAdapter {
    suspend fun planAction(request: ModelRequest): ModelResponse
}

interface PlanningService {
    suspend fun planAction(taskId: String, userMessage: String): PlanningResult
}

class StubCloudModelAdapter(
    private val skillRegistry: SkillRegistry,
) : CloudModelAdapter {
    override suspend fun planAction(request: ModelRequest): ModelResponse {
        val matchedAction = skillRegistry.matchUserMessage(request.inputMessages.joinToString(" "))

        return if (matchedAction != null) {
            ModelResponse(
                requestId = request.requestId,
                provider = "stub-cloud",
                modelId = "stub-skill-router",
                outputText = "Planned ${matchedAction.actionId} via stub adapter.",
                plannedAction = matchedAction.toPlannedActionPayload(),
            )
        } else {
            ModelResponse(
                requestId = request.requestId,
                provider = "stub-cloud",
                modelId = "stub-skill-router",
                outputText = "Need clarification",
                error = buildString {
                    append("Supported actions in this build: ")
                    append(skillRegistry.allActions().joinToString { it.actionId })
                    append('.')
                },
            )
        }
    }
}

class DefaultPlanningService(
    private val cloudModelAdapter: CloudModelAdapter,
    private val allowCloud: Boolean,
    private val preferredProvider: String,
) : PlanningService {
    override suspend fun planAction(taskId: String, userMessage: String): PlanningResult {
        val request = ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = taskId,
            taskType = "plan_action",
            inputMessages = listOf(userMessage),
            allowCloud = allowCloud,
            preferredProvider = preferredProvider,
        )

        val response = cloudModelAdapter.planAction(request)
        val trace = response.toPlanningTrace()

        response.plannedAction?.let { payload ->
            return PlanningResult(
                outcome = PlanningOutcome.PlannedAction(payload.toActionSpec(taskId)),
                trace = trace,
            )
        }

        if (response.error != null) {
            return PlanningResult(
                outcome = PlanningOutcome.Refused(response.error),
                trace = trace,
            )
        }

        return PlanningResult(
            outcome = PlanningOutcome.ClarificationNeeded(
                response.outputText.ifBlank {
                    "Please clarify which supported settings screen you want to open."
                },
            ),
            trace = trace,
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

    private fun ModelResponse.toPlanningTrace(): PlanningTrace {
        return PlanningTrace(
            provider = provider,
            modelId = modelId,
            outputText = outputText,
            errorMessage = error,
            errorKind = errorKind,
            usedRemote = provider.isNotBlank() && provider != "stub-cloud",
        )
    }
}
