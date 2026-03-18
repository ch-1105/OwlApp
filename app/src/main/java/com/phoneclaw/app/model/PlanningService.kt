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

private const val TASK_TYPE_PLAN_ACTION = "plan_action"
private const val TASK_TYPE_SUMMARIZE_WEB_CONTENT = "summarize_web_content"
private val browserActionIds = setOf("open_web_url", "fetch_web_page_content")

interface CloudModelAdapter {
    suspend fun planAction(request: ModelRequest): ModelResponse
}

interface PlanningService : PlannerPort

class StubCloudModelAdapter(
    private val skillRegistry: SkillRegistry,
) : CloudModelAdapter {
    override suspend fun planAction(request: ModelRequest): ModelResponse {
        if (request.taskType == TASK_TYPE_SUMMARIZE_WEB_CONTENT) {
            return summarizeWebContent(request)
        }

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

    private fun summarizeWebContent(request: ModelRequest): ModelResponse {
        val flattened = request.inputMessages.joinToString("\n")
        val pageContent = flattened.substringAfter("Page content:", "").trim()
        val summary = if (pageContent.isBlank()) {
            "我没有拿到可读网页正文，请提供一个可访问的网页地址后再试一次。"
        } else {
            """
            网页内容总结（stub）：
            ${pageContent.take(320)}
            """.trimIndent()
        }

        return ModelResponse(
            requestId = request.requestId,
            provider = "stub-cloud",
            modelId = "stub-skill-router",
            outputText = summary,
        )
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
            taskType = TASK_TYPE_PLAN_ACTION,
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

    override suspend fun summarizeWebContent(
        taskId: String,
        userMessage: String,
        webContent: Map<String, String>,
    ): String? {
        val pageContent = webContent["page_content"]?.trim().orEmpty()
        if (pageContent.isBlank()) {
            return null
        }

        val request = ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = taskId,
            taskType = TASK_TYPE_SUMMARIZE_WEB_CONTENT,
            inputMessages = buildList {
                add("User question: $userMessage")
                webContent["page_url"]?.takeIf { it.isNotBlank() }?.let { add("Page url: $it") }
                webContent["page_title"]?.takeIf { it.isNotBlank() }?.let { add("Page title: $it") }
                add("Page content:\n$pageContent")
                add("Please provide a concise Chinese summary that answers the user question.")
            },
            allowCloud = allowCloud,
            preferredProvider = preferredProvider,
        )

        val response = cloudModelAdapter.planAction(request)
        if (response.error != null) {
            return null
        }

        return response.outputText.trim().takeIf { it.isNotBlank() }
            ?: pageContent.take(320)
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
