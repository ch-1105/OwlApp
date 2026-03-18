package com.phoneclaw.app.gateway

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.executor.ActionExecutor
import com.phoneclaw.app.gateway.ports.AuditPort
import com.phoneclaw.app.gateway.ports.PlannerOutcome
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.PlannerResult
import com.phoneclaw.app.gateway.ports.TelemetryPort
import com.phoneclaw.app.policy.PolicyDecision
import com.phoneclaw.app.policy.PolicyEngine
import com.phoneclaw.app.session.InMemorySessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGatewayAuditTest {
    @Test
    fun submitUserMessage_recordsAuditTimelineWithSingleTraceId() = runBlocking {
        val auditPort = RecordingAuditPort()
        val sessionPort = InMemorySessionStore()
        val gateway = DefaultGateway(
            plannerPort = FakePlannerPort(),
            policyEngine = FakePolicyEngine(),
            actionExecutor = FakeActionExecutor(),
            sessionPort = sessionPort,
            telemetryPort = NoOpTelemetryPort(),
            auditPort = auditPort,
        )

        val snapshot = gateway.submitUserMessage("open wifi settings")

        assertEquals(TaskState.SUCCEEDED, snapshot.state)
        assertTrue(auditPort.taskEvents.any { it.eventType == "task_created" })
        assertTrue(auditPort.taskEvents.any { it.eventType == "planning_completed" })
        assertTrue(auditPort.taskEvents.any { it.eventType == "action_planned" })
        assertTrue(auditPort.taskEvents.any { it.eventType == "policy_allowed" })
        assertTrue(
            auditPort.taskEvents.any {
                it.eventType == "state_changed" && it.payload["state"] == TaskState.SUCCEEDED.name
            },
        )
        assertEquals(1, auditPort.modelTraceCount)
        assertEquals(1, auditPort.executionTraceCount)
        assertEquals(1, auditPort.traceIds.size)
    }

    private class FakePlannerPort : PlannerPort {
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
                trace = PlanningTrace(
                    provider = "stub-cloud",
                    modelId = "stub-router",
                    outputText = "Planned open_wifi_settings",
                    usedRemote = false,
                ),
            )
        }

        override suspend fun summarizeWebContent(
            taskId: String,
            userMessage: String,
            webContent: Map<String, String>,
        ): String? = null
    }

    private class FakePolicyEngine : PolicyEngine {
        override fun review(actionSpec: ActionSpec): PolicyDecision {
            return PolicyDecision(
                allowed = true,
                requiresConfirmation = false,
            )
        }
    }

    private class FakeActionExecutor : ActionExecutor {
        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
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

    private class NoOpTelemetryPort : TelemetryPort {
        override fun recordTaskEvent(taskId: String, eventType: String, payload: Map<String, String>) = Unit

        override fun recordModelTrace(taskId: String, trace: PlanningTrace) = Unit

        override fun recordExecutionTrace(taskId: String, result: ExecutionResult) = Unit
    }

    private class RecordingAuditPort : AuditPort {
        val taskEvents = mutableListOf<RecordedTaskEvent>()
        var modelTraceCount: Int = 0
        var executionTraceCount: Int = 0
        val traceIds: Set<String>
            get() = taskEvents.map { it.traceId }.toSet() +
                modelTraces.map { it.traceId }.toSet() +
                executionTraces.map { it.traceId }.toSet()

        private val modelTraces = mutableListOf<RecordedTrace>()
        private val executionTraces = mutableListOf<RecordedTrace>()

        override fun recordTaskEvent(
            taskId: String,
            traceId: String,
            eventType: String,
            payload: Map<String, String>,
        ) {
            taskEvents += RecordedTaskEvent(taskId, traceId, eventType, payload)
        }

        override fun recordModelTrace(taskId: String, traceId: String, trace: PlanningTrace) {
            modelTraceCount += 1
            modelTraces += RecordedTrace(taskId, traceId)
        }

        override fun recordExecutionTrace(taskId: String, traceId: String, result: ExecutionResult) {
            executionTraceCount += 1
            executionTraces += RecordedTrace(taskId, traceId)
        }
    }

    private data class RecordedTaskEvent(
        val taskId: String,
        val traceId: String,
        val eventType: String,
        val payload: Map<String, String>,
    )

    private data class RecordedTrace(
        val taskId: String,
        val traceId: String,
    )
}
