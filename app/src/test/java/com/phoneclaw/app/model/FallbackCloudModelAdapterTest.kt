package com.phoneclaw.app.model

import com.phoneclaw.app.contracts.ModelErrorKind
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackCloudModelAdapterTest {
    @Test
    fun fallsBackWhenRemoteRefusesButStubCanPlan() = runBlocking {
        val request = sampleRequest()
        val adapter = FallbackCloudModelAdapter(
            remoteAdapter = FakeCloudModelAdapter(
                ModelResponse(
                    requestId = request.requestId,
                    provider = "remote",
                    modelId = "remote-model",
                    outputText = "{\"error\":\"unsupported\"}",
                    error = "unsupported",
                    errorKind = ModelErrorKind.PROVIDER_REFUSED,
                ),
            ),
            fallbackAdapter = FakeCloudModelAdapter(
                ModelResponse(
                    requestId = request.requestId,
                    provider = "stub-cloud",
                    modelId = "stub-skill-router",
                    outputText = "planned locally",
                    plannedAction = samplePlannedAction(),
                ),
            ),
            useRemote = true,
        )

        val result = adapter.planAction(request)

        assertEquals("stub-cloud", result.provider)
        assertEquals("open_web_url", result.plannedAction?.actionId)
    }

    @Test
    fun keepsRemoteResponseWhenFallbackAlsoCannotPlan() = runBlocking {
        val request = sampleRequest()
        val adapter = FallbackCloudModelAdapter(
            remoteAdapter = FakeCloudModelAdapter(
                ModelResponse(
                    requestId = request.requestId,
                    provider = "remote",
                    modelId = "remote-model",
                    outputText = "{\"error\":\"unsupported\"}",
                    error = "unsupported",
                    errorKind = ModelErrorKind.PROVIDER_REFUSED,
                ),
            ),
            fallbackAdapter = FakeCloudModelAdapter(
                ModelResponse(
                    requestId = request.requestId,
                    provider = "stub-cloud",
                    modelId = "stub-skill-router",
                    outputText = "still unsupported",
                    error = "still unsupported",
                    errorKind = ModelErrorKind.PROVIDER_REFUSED,
                ),
            ),
            useRemote = true,
        )

        val result = adapter.planAction(request)

        assertEquals("remote", result.provider)
        assertNull(result.plannedAction)
        assertEquals("unsupported", result.error)
    }

    private class FakeCloudModelAdapter(
        private val response: ModelResponse,
    ) : CloudModelAdapter {
        override suspend fun planAction(request: ModelRequest): ModelResponse = response
    }

    private fun sampleRequest(): ModelRequest {
        return ModelRequest(
            requestId = "req-1",
            taskId = "task-1",
            taskType = "plan_action",
            inputMessages = listOf("open https://openai.com"),
            allowCloud = true,
            preferredProvider = "test",
        )
    }

    private fun samplePlannedAction(): PlannedActionPayload {
        return PlannedActionPayload(
            actionId = "open_web_url",
            skillId = "browser.web",
            intentSummary = "Open a web page",
            params = mapOf("url" to "https://openai.com"),
            riskLevel = RiskLevel.SAFE,
            requiresConfirmation = false,
            executorType = "browser_intent",
            expectedOutcome = "The target URL becomes foreground in the default browser",
        )
    }
}
