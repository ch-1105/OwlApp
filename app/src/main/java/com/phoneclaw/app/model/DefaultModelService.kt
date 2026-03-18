package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.gateway.ports.SkillRegistryPort

private const val TASK_TYPE_SUMMARIZE_WEB_CONTENT = "summarize_web_content"

interface CloudModelAdapter {
    suspend fun planAction(request: ModelRequest): ModelResponse
}

class DefaultModelService(
    private val cloudModelAdapter: CloudModelAdapter,
) : ModelPort {
    override suspend fun infer(request: ModelRequest): ModelResponse {
        return cloudModelAdapter.planAction(request)
    }
}

class StubCloudModelAdapter(
    private val skillRegistry: SkillRegistryPort,
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
