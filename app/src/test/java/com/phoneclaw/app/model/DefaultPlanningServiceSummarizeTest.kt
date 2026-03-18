package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultPlanningServiceSummarizeTest {
    @Test
    fun summarizeWebContent_returnsModelOutput() = runBlocking {
        val adapter = RecordingAdapter(
            response = ModelResponse(
                requestId = "m-1",
                provider = "stub",
                modelId = "stub",
                outputText = "这是总结",
            ),
        )
        val service = DefaultPlanningService(
            cloudModelAdapter = adapter,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val summary = service.summarizeWebContent(
            taskId = "task-1",
            userMessage = "这篇网页说了什么",
            webContent = mapOf(
                "page_url" to "https://example.com",
                "page_title" to "Example",
                "page_content" to "This page describes a sample product and its release date.",
            ),
        )

        assertEquals("这是总结", summary)
        assertEquals("summarize_web_content", adapter.lastRequest?.taskType)
    }

    @Test
    fun summarizeWebContent_returnsNullWhenPageContentMissing() = runBlocking {
        val adapter = RecordingAdapter(
            response = ModelResponse(
                requestId = "m-2",
                provider = "stub",
                modelId = "stub",
                outputText = "unused",
            ),
        )
        val service = DefaultPlanningService(
            cloudModelAdapter = adapter,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val summary = service.summarizeWebContent(
            taskId = "task-2",
            userMessage = "总结",
            webContent = emptyMap(),
        )

        assertNull(summary)
        assertNull(adapter.lastRequest)
    }

    private class RecordingAdapter(
        private val response: ModelResponse,
    ) : CloudModelAdapter {
        var lastRequest: ModelRequest? = null

        override suspend fun planAction(request: ModelRequest): ModelResponse {
            lastRequest = request
            return response.copy(requestId = request.requestId)
        }
    }
}
