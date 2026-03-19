package com.phoneclaw.app.gateway

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.gateway.ports.AuditPort
import com.phoneclaw.app.gateway.ports.ExecutorPort
import com.phoneclaw.app.gateway.ports.PlannerOutcome
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.PlannerResult
import com.phoneclaw.app.gateway.ports.PolicyDecision
import com.phoneclaw.app.gateway.ports.PolicyPort
import com.phoneclaw.app.gateway.ports.SummaryPort
import com.phoneclaw.app.gateway.ports.TaskEventType
import com.phoneclaw.app.gateway.ports.TelemetryPort
import com.phoneclaw.app.session.InMemorySessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGatewayConfirmationTest {
    @Test
    fun submitUserMessage_stopsAtAwaitingConfirmationUntilApproved() = runBlocking {
        val executor = RecordingExecutorPort()
        val gateway = buildGateway(
            plannerPort = PlannedActionPlannerPort(),
            policyPort = ConfirmationRequiredPolicyPort(),
            executorPort = executor,
        )

        val snapshot = gateway.submitUserMessage("open wifi settings")

        assertEquals(TaskState.AWAITING_CONFIRMATION, snapshot.state)
        assertEquals(0, executor.executionCount)
        assertEquals("open_wifi_settings", snapshot.actionSpec?.actionId)
    }

    @Test
    fun confirmAction_executesPendingTaskAfterApproval() = runBlocking {
        val executor = RecordingExecutorPort()
        val gateway = buildGateway(
            plannerPort = PlannedActionPlannerPort(),
            policyPort = ConfirmationRequiredPolicyPort(),
            executorPort = executor,
        )

        val pending = gateway.submitUserMessage("open wifi settings")
        val confirmed = gateway.confirmAction(pending.taskId, approved = true)

        assertEquals(TaskState.SUCCEEDED, confirmed.state)
        assertEquals(1, executor.executionCount)
        assertEquals("open_wifi_settings", confirmed.executionResult?.actionId)
    }

    @Test
    fun confirmAction_cancelsPendingTaskWhenRejected() = runBlocking {
        val executor = RecordingExecutorPort()
        val gateway = buildGateway(
            plannerPort = PlannedActionPlannerPort(),
            policyPort = ConfirmationRequiredPolicyPort(),
            executorPort = executor,
        )

        val pending = gateway.submitUserMessage("open wifi settings")
        val cancelled = gateway.confirmAction(pending.taskId, approved = false)

        assertEquals(TaskState.CANCELLED, cancelled.state)
        assertEquals(0, executor.executionCount)
        assertTrue(cancelled.errorMessage.orEmpty().contains("取消"))
    }

    @Test
    fun submitUserMessage_marksClarificationAsNeedsClarification() = runBlocking {
        val gateway = buildGateway(
            plannerPort = ClarificationPlannerPort(),
            policyPort = ConfirmationRequiredPolicyPort(),
            executorPort = RecordingExecutorPort(),
        )

        val snapshot = gateway.submitUserMessage("open something")

        assertEquals(TaskState.NEEDS_CLARIFICATION, snapshot.state)
        assertTrue(snapshot.errorMessage.orEmpty().contains("完整网页地址"))
    }

    @Test
    fun submitUserMessage_recordsConfirmationRequestAuditEvent() = runBlocking {
        val auditPort = RecordingAuditPort()
        val gateway = DefaultGateway(
            plannerPort = PlannedActionPlannerPort(),
            policyPort = ConfirmationRequiredPolicyPort(),
            executorPort = RecordingExecutorPort(),
            summaryPort = NoOpSummaryPort(),
            sessionPort = InMemorySessionStore(),
            telemetryPort = NoOpTelemetryPort(),
            auditPort = auditPort,
        )

        gateway.submitUserMessage("open wifi settings")

        assertTrue(auditPort.taskEvents.any { it.eventType == "confirmation_requested" })
        assertTrue(
            auditPort.taskEvents.any {
                it.eventType == "state_changed" && it.payload["state"] == TaskState.AWAITING_CONFIRMATION.name
            },
        )
    }

    private fun buildGateway(
        plannerPort: PlannerPort,
        policyPort: PolicyPort,
        executorPort: ExecutorPort,
    ): DefaultGateway {
        return DefaultGateway(
            plannerPort = plannerPort,
            policyPort = policyPort,
            executorPort = executorPort,
            summaryPort = NoOpSummaryPort(),
            sessionPort = InMemorySessionStore(),
            telemetryPort = NoOpTelemetryPort(),
            auditPort = RecordingAuditPort(),
        )
    }

    private class PlannedActionPlannerPort : PlannerPort {
        override suspend fun planAction(taskId: String, userMessage: String): PlannerResult {
            return PlannerResult(
                outcome = PlannerOutcome.PlannedAction(
                    actionSpec = ActionSpec(
                        actionId = "open_wifi_settings",
                        skillId = "system.wifi_settings",
                        taskId = taskId,
                        intentSummary = "Open Wi-Fi settings",
                        params = emptyMap(),
                        riskLevel = RiskLevel.SAFE,
                        requiresConfirmation = false,
                        executorType = "intent",
                        expectedOutcome = "Wi-Fi settings becomes foreground",
                    ),
                ),
                trace = stubPlanningTrace(),
            )
        }
    }

    private class ClarificationPlannerPort : PlannerPort {
        override suspend fun planAction(taskId: String, userMessage: String): PlannerResult {
            return PlannerResult(
                outcome = PlannerOutcome.ClarificationNeeded(
                    question = "请提供完整网页地址，例如 https://example.com 。",
                ),
                trace = stubPlanningTrace(),
            )
        }
    }

    private class ConfirmationRequiredPolicyPort : PolicyPort {
        override fun review(actionSpec: ActionSpec): PolicyDecision {
            return PolicyDecision(
                allowed = true,
                requiresConfirmation = true,
            )
        }
    }

    private class RecordingExecutorPort : ExecutorPort {
        var executionCount: Int = 0
            private set

        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
            executionCount += 1
            return ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "success",
                resultSummary = "Wi-Fi settings opened",
                verification = VerificationResult(
                    passed = true,
                    reason = "Intent dispatch completed",
                ),
            )
        }
    }

    private class NoOpSummaryPort : SummaryPort {
        override suspend fun summarize(taskId: String, userMessage: String, content: Map<String, String>): String? = null
    }

    private class NoOpTelemetryPort : TelemetryPort {
        override fun recordTaskEvent(taskId: String, eventType: String, payload: Map<String, String>) = Unit

        override fun recordModelTrace(taskId: String, trace: PlanningTrace) = Unit

        override fun recordExecutionTrace(taskId: String, result: ExecutionResult) = Unit
    }

    private class RecordingAuditPort : AuditPort {
        val taskEvents = mutableListOf<RecordedTaskEvent>()

        override fun recordTaskEvent(
            taskId: String,
            traceId: String,
            eventType: String,
            payload: Map<String, String>,
        ) {
            taskEvents += RecordedTaskEvent(taskId, traceId, eventType, payload)
        }

        override fun recordModelTrace(taskId: String, traceId: String, trace: PlanningTrace) = Unit

        override fun recordExecutionTrace(taskId: String, traceId: String, result: ExecutionResult) = Unit
    }

    private data class RecordedTaskEvent(
        val taskId: String,
        val traceId: String,
        val eventType: String,
        val payload: Map<String, String>,
    )
}

private fun stubPlanningTrace(): PlanningTrace {
    return PlanningTrace(
        provider = "stub-cloud",
        modelId = "stub-router",
        outputText = "Planned open_wifi_settings",
        usedRemote = false,
    )
}
