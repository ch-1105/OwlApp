package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.gateway.ports.ModelPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultSummaryServiceTest {
    @Test
    fun summarize_returnsModelOutput() = runBlocking {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "m-1",
                provider = "stub",
                modelId = "stub",
                outputText = "这是总结",
            ),
        )
        val service = DefaultSummaryService(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val summary = service.summarize(
            taskId = "task-1",
            userMessage = "这篇网页说了什么",
            content = mapOf(
                "page_url" to "https://example.com",
                "page_title" to "Example",
                "page_content" to "This page describes a sample product and its release date.",
            ),
        )

        assertEquals("这是总结", summary)
        assertEquals("summarize_web_content", modelPort.lastRequest?.taskType)
    }

    @Test
    fun summarize_returnsNullWhenPageContentMissing() = runBlocking {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "m-2",
                provider = "stub",
                modelId = "stub",
                outputText = "unused",
            ),
        )
        val service = DefaultSummaryService(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val summary = service.summarize(
            taskId = "task-2",
            userMessage = "总结",
            content = emptyMap(),
        )

        assertNull(summary)
        assertNull(modelPort.lastRequest)
    }

    private class RecordingModelPort(
        private val response: ModelResponse,
    ) : ModelPort {
        var lastRequest: ModelRequest? = null

        override suspend fun infer(request: ModelRequest): ModelResponse {
            lastRequest = request
            return response.copy(requestId = request.requestId)
        }
    }
}
