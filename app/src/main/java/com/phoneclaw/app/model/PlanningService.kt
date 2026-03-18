package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.gateway.ports.PlannerOutcome
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.PlannerResult
import com.phoneclaw.app.skills.SkillRegistry
import com.phoneclaw.app.web.extractFirstWebTarget
import com.phoneclaw.app.web.normalizeWebUrl
import java.util.UUID

private val browserActionIds = setOf("open_web_url", "fetch_web_page_content")

interface CloudModelAdapter {
    suspend fun planAction(request: ModelRequest): ModelResponse
}

interface PlanningService : PlannerPort

class StubCloudModelAdapter(
    private val skillRegistry: SkillRegistry,
) : CloudModelAdapter {
    override suspend fun planAction(request: ModelRequest): ModelResponse {
        val userMessage = request.inputMessages.joinToString(" ")
        val matchedAction = skillRegistry.matchUserMessage(userMessage)

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
    override suspend fun planAction(taskId: String, userMessage: String): PlannerResult {
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
            val normalizedPayload = payload.normalizeForUserMessage(userMessage)
            if (normalizedPayload.requiresUrlParam() && normalizedPayload.params["url"].isNullOrBlank()) {
                return PlannerResult(
                    outcome = PlannerOutcome.ClarificationNeeded(
                        "请提供完整网页地址，例如 https://example.com 。",
                    ),
                    trace = trace,
                )
            }

            return PlannerResult(
                outcome = PlannerOutcome.PlannedAction(normalizedPayload.toActionSpec(taskId)),
                trace = trace,
            )
        }

        if (response.error != null) {
            return PlannerResult(
                outcome = PlannerOutcome.Refused(response.error),
                trace = trace,
            )
        }

        return PlannerResult(
            outcome = PlannerOutcome.ClarificationNeeded(
                response.outputText.ifBlank {
                    "Please clarify which supported action you want to run."
                },
            ),
            trace = trace,
        )
    }

    private fun PlannedActionPayload.normalizeForUserMessage(userMessage: String): PlannedActionPayload {
        if (!requiresUrlParam()) return this

        val extractedUrl = params["url"]
            ?.takeIf { it.isNotBlank() }
            ?: extractFirstWebTarget(userMessage)

        val normalizedUrl = extractedUrl?.let(::normalizeWebUrl)
        return copy(
            params = params.toMutableMap().apply {
                if (!normalizedUrl.isNullOrBlank()) {
                    put("url", normalizedUrl)
                }
            },
        )
    }

    private fun PlannedActionPayload.requiresUrlParam(): Boolean {
        return actionId in browserActionIds
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

