package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.gateway.ports.SummaryPort
import java.util.UUID

private const val TASK_TYPE_SUMMARIZE_WEB_CONTENT = "summarize_web_content"

class DefaultSummaryService(
    private val modelPort: ModelPort,
    private val allowCloud: Boolean,
    private val preferredProvider: String,
) : SummaryPort {
    override suspend fun summarize(
        taskId: String,
        userMessage: String,
        content: Map<String, String>,
    ): String? {
        val pageContent = content["page_content"]?.trim().orEmpty()
        if (pageContent.isBlank()) {
            return null
        }

        val request = ModelRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = taskId,
            taskType = TASK_TYPE_SUMMARIZE_WEB_CONTENT,
            inputMessages = buildList {
                add("User question: $userMessage")
                content["page_url"]?.takeIf { it.isNotBlank() }?.let { add("Page url: $it") }
                content["page_title"]?.takeIf { it.isNotBlank() }?.let { add("Page title: $it") }
                add("Page content:\n$pageContent")
                add("Please provide a concise Chinese summary that answers the user question.")
            },
            allowCloud = allowCloud,
            preferredProvider = preferredProvider,
        )

        val response = modelPort.infer(request)
        if (response.error != null) {
            return null
        }

        return response.outputText.trim().takeIf { it.isNotBlank() }
            ?: pageContent.take(320)
    }
}
