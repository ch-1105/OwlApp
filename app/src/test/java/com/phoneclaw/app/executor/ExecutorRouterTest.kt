package com.phoneclaw.app.executor

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.gateway.ports.ExecutorPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutorRouterTest {

    @Test
    fun execute_intentType_routesToIntentExecutor() = runTest {
        val intentExecutor = FakeExecutor("intent-ok")
        val router = ExecutorRouter(
            intentExecutor = intentExecutor,
            accessibilityExecutor = null,
        )

        val result = router.execute(testRequest(executorType = "intent"))
        assertEquals("intent-ok", result.resultSummary)
    }

    @Test
    fun execute_browserIntentType_routesToIntentExecutor() = runTest {
        val intentExecutor = FakeExecutor("browser-ok")
        val router = ExecutorRouter(
            intentExecutor = intentExecutor,
            accessibilityExecutor = null,
        )

        val result = router.execute(testRequest(executorType = "browser_intent"))
        assertEquals("browser-ok", result.resultSummary)
    }

    @Test
    fun execute_webFetchType_routesToIntentExecutor() = runTest {
        val intentExecutor = FakeExecutor("web-ok")
        val router = ExecutorRouter(
            intentExecutor = intentExecutor,
            accessibilityExecutor = null,
        )

        val result = router.execute(testRequest(executorType = "web_fetch"))
        assertEquals("web-ok", result.resultSummary)
    }

    @Test
    fun execute_accessibilityType_routesToAccessibilityExecutor() = runTest {
        val accessibilityExecutor = FakeExecutor("accessibility-ok")
        val router = ExecutorRouter(
            intentExecutor = FakeExecutor("intent-ok"),
            accessibilityExecutor = accessibilityExecutor,
        )

        val result = router.execute(testRequest(executorType = "accessibility"))
        assertEquals("accessibility-ok", result.resultSummary)
    }

    @Test
    fun execute_accessibilityType_nullExecutor_returnsFailed() = runTest {
        val router = ExecutorRouter(
            intentExecutor = FakeExecutor("intent-ok"),
            accessibilityExecutor = null,
        )

        val result = router.execute(testRequest(executorType = "accessibility"))
        assertEquals("failed", result.status)
    }

    @Test
    fun execute_unknownType_returnsFailed() = runTest {
        val router = ExecutorRouter(
            intentExecutor = FakeExecutor("intent-ok"),
            accessibilityExecutor = FakeExecutor("accessibility-ok"),
        )

        val result = router.execute(testRequest(executorType = "unknown_type"))
        assertEquals("failed", result.status)
    }
}

private class FakeExecutor(private val summary: String) : ExecutorPort {
    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        return ExecutionResult(
            requestId = request.requestId,
            taskId = request.taskId,
            actionId = request.actionSpec.actionId,
            status = "success",
            resultSummary = summary,
            verification = VerificationResult(passed = true, reason = "fake"),
        )
    }
}

private fun testRequest(executorType: String): ExecutionRequest {
    return ExecutionRequest(
        requestId = "req-1",
        taskId = "task-1",
        actionSpec = ActionSpec(
            actionId = "test_action",
            skillId = "test_skill",
            taskId = "task-1",
            intentSummary = "test",
            params = emptyMap(),
            riskLevel = RiskLevel.SAFE,
            requiresConfirmation = false,
            executorType = executorType,
            expectedOutcome = "test",
        ),
    )
}
