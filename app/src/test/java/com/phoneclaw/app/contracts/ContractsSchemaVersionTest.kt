package com.phoneclaw.app.contracts

import org.junit.Assert.assertEquals
import org.junit.Test

class ContractsSchemaVersionTest {
    @Test
    fun coreContractsDefaultToV1Alpha1() {
        val actionSpec = ActionSpec(
            actionId = "open_system_settings",
            skillId = "system.settings",
            taskId = "task-1",
            intentSummary = "Open settings",
            params = emptyMap(),
            riskLevel = RiskLevel.SAFE,
            requiresConfirmation = false,
            executorType = "intent",
            expectedOutcome = "Settings opened",
        )
        val executionRequest = ExecutionRequest(
            requestId = "req-1",
            taskId = "task-1",
            actionSpec = actionSpec,
        )
        val executionResult = ExecutionResult(
            requestId = "req-1",
            taskId = "task-1",
            actionId = "open_system_settings",
            status = "success",
            resultSummary = "ok",
            verification = VerificationResult(
                passed = true,
                reason = "ok",
            ),
        )
        val modelRequest = ModelRequest(
            requestId = "mreq-1",
            taskId = "task-1",
            taskType = "plan_action",
            inputMessages = listOf("open settings"),
            allowCloud = true,
            preferredProvider = "stub",
        )
        val modelResponse = ModelResponse(
            requestId = "mreq-1",
            provider = "stub",
            modelId = "model",
            outputText = "ok",
        )

        assertEquals("v1alpha1", actionSpec.schemaVersion)
        assertEquals("v1alpha1", executionRequest.schemaVersion)
        assertEquals("v1alpha1", executionResult.schemaVersion)
        assertEquals("v1alpha1", modelRequest.schemaVersion)
        assertEquals("v1alpha1", modelResponse.schemaVersion)
    }
}
