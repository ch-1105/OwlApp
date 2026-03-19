package com.phoneclaw.app.gateway

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.gateway.ports.AuditPort
import com.phoneclaw.app.gateway.ports.ExecutorPort
import com.phoneclaw.app.gateway.ports.PlannerOutcome
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.PolicyPort
import com.phoneclaw.app.gateway.ports.SessionPort
import com.phoneclaw.app.gateway.ports.SummaryPort
import com.phoneclaw.app.gateway.ports.TaskEvent
import com.phoneclaw.app.gateway.ports.TaskEventType
import com.phoneclaw.app.gateway.ports.TelemetryPort
import java.util.UUID

private const val DEFAULT_SESSION_ID = "default"
private const val ACTION_FETCH_WEB_PAGE_CONTENT = "fetch_web_page_content"

interface Gateway {
    suspend fun submitUserMessage(userMessage: String): TaskSnapshot

    suspend fun confirmAction(taskId: String, approved: Boolean): TaskSnapshot
}

class DefaultGateway(
    private val plannerPort: PlannerPort,
    private val policyPort: PolicyPort,
    private val executorPort: ExecutorPort,
    private val summaryPort: SummaryPort,
    private val sessionPort: SessionPort,
    private val telemetryPort: TelemetryPort,
    private val auditPort: AuditPort,
) : Gateway {
    override suspend fun submitUserMessage(userMessage: String): TaskSnapshot {
        val taskId = sessionPort.createTask(DEFAULT_SESSION_ID, userMessage)
        val traceId = UUID.randomUUID().toString()
        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "task_created",
            payload = mapOf(
                "session_id" to DEFAULT_SESSION_ID,
                "user_message" to userMessage,
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "task_created",
            payload = mapOf(
                "session_id" to DEFAULT_SESSION_ID,
                "user_message" to userMessage,
            ),
        )

        transitionTask(taskId, traceId, TaskState.PLANNING)

        val planning = plannerPort.planAction(taskId, userMessage)
        sessionPort.appendTaskEvent(
            taskId = taskId,
            event = TaskEvent(
                type = TaskEventType.PLANNING_COMPLETED,
                planningTrace = planning.trace,
            ),
        )
        telemetryPort.recordModelTrace(taskId, planning.trace)
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "planning_completed",
            payload = mapOf(
                "provider" to planning.trace.provider,
                "model_id" to planning.trace.modelId,
                "used_remote" to planning.trace.usedRemote.toString(),
            ),
        )
        auditPort.recordModelTrace(taskId, traceId, planning.trace)

        return when (val outcome = planning.outcome) {
            is PlannerOutcome.PlannedAction -> handlePlannedAction(
                taskId = taskId,
                traceId = traceId,
                userMessage = userMessage,
                actionSpec = outcome.actionSpec,
                planningTrace = planning.trace,
            )

            is PlannerOutcome.ClarificationNeeded -> handleClarificationNeeded(
                taskId = taskId,
                traceId = traceId,
                userMessage = userMessage,
                planningTrace = planning.trace,
                question = outcome.question,
            )

            is PlannerOutcome.Refused -> handlePlannerRefused(
                taskId = taskId,
                traceId = traceId,
                userMessage = userMessage,
                planningTrace = planning.trace,
                reason = outcome.reason,
            )
        }
    }

    override suspend fun confirmAction(taskId: String, approved: Boolean): TaskSnapshot {
        val traceId = UUID.randomUUID().toString()
        val snapshot = sessionPort.loadSnapshot(taskId)
            ?: return TaskSnapshot(
                taskId = taskId,
                state = TaskState.FAILED,
                userMessage = "",
                errorMessage = "找不到需要确认的任务。",
            )

        if (snapshot.state != TaskState.AWAITING_CONFIRMATION) {
            return snapshot
        }

        val actionSpec = snapshot.actionSpec
        if (actionSpec == null) {
            val errorMessage = "待确认任务缺少动作信息。"
            transitionTask(taskId, traceId, TaskState.FAILED)
            sessionPort.appendTaskEvent(
                taskId = taskId,
                event = TaskEvent(
                    type = TaskEventType.EXECUTION_FAILED,
                    errorMessage = errorMessage,
                ),
            )
            telemetryPort.recordTaskEvent(
                taskId = taskId,
                eventType = "confirmation_failed",
                payload = mapOf(
                    "reason" to errorMessage,
                ),
            )
            auditPort.recordTaskEvent(
                taskId = taskId,
                traceId = traceId,
                eventType = "confirmation_failed",
                payload = mapOf(
                    "reason" to errorMessage,
                ),
            )
            return loadSnapshotOrFallback(
                taskId = taskId,
                userMessage = snapshot.userMessage,
                fallbackState = TaskState.FAILED,
                planningTrace = snapshot.planningTrace,
                errorMessage = errorMessage,
            )
        }

        if (!approved) {
            val errorMessage = "这次操作已取消，未继续执行。"
            sessionPort.appendTaskEvent(
                taskId = taskId,
                event = TaskEvent(
                    type = TaskEventType.CONFIRMATION_REJECTED,
                    actionSpec = actionSpec,
                    errorMessage = errorMessage,
                ),
            )
            telemetryPort.recordTaskEvent(
                taskId = taskId,
                eventType = "confirmation_rejected",
                payload = mapOf(
                    "action_id" to actionSpec.actionId,
                ),
            )
            auditPort.recordTaskEvent(
                taskId = taskId,
                traceId = traceId,
                eventType = "confirmation_rejected",
                payload = mapOf(
                    "action_id" to actionSpec.actionId,
                ),
            )
            transitionTask(taskId, traceId, TaskState.CANCELLED)
            return loadSnapshotOrFallback(
                taskId = taskId,
                userMessage = snapshot.userMessage,
                fallbackState = TaskState.CANCELLED,
                actionSpec = actionSpec,
                planningTrace = snapshot.planningTrace,
                errorMessage = errorMessage,
            )
        }

        sessionPort.appendTaskEvent(
            taskId = taskId,
            event = TaskEvent(
                type = TaskEventType.CONFIRMATION_APPROVED,
                actionSpec = actionSpec,
            ),
        )
        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "confirmation_approved",
            payload = mapOf(
                "action_id" to actionSpec.actionId,
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "confirmation_approved",
            payload = mapOf(
                "action_id" to actionSpec.actionId,
            ),
        )

        return executeApprovedAction(
            taskId = taskId,
            traceId = traceId,
            userMessage = snapshot.userMessage,
            actionSpec = actionSpec,
            planningTrace = snapshot.planningTrace,
        )
    }

    private suspend fun handlePlannedAction(
        taskId: String,
        traceId: String,
        userMessage: String,
        actionSpec: ActionSpec,
        planningTrace: PlanningTrace,
    ): TaskSnapshot {
        sessionPort.appendTaskEvent(
            taskId = taskId,
            event = TaskEvent(
                type = TaskEventType.ACTION_PLANNED,
                actionSpec = actionSpec,
            ),
        )
        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "action_planned",
            payload = mapOf(
                "action_id" to actionSpec.actionId,
                "skill_id" to actionSpec.skillId,
                "risk_level" to actionSpec.riskLevel.name,
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "action_planned",
            payload = mapOf(
                "action_id" to actionSpec.actionId,
                "skill_id" to actionSpec.skillId,
                "risk_level" to actionSpec.riskLevel.name,
            ),
        )

        val decision = policyPort.review(actionSpec)
        if (!decision.allowed) {
            transitionTask(taskId, traceId, TaskState.REFUSED)
            sessionPort.appendTaskEvent(
                taskId = taskId,
                event = TaskEvent(
                    type = TaskEventType.POLICY_REFUSED,
                    errorMessage = decision.reason,
                ),
            )
            telemetryPort.recordTaskEvent(
                taskId = taskId,
                eventType = "policy_refused",
                payload = mapOf(
                    "reason" to (decision.reason ?: ""),
                ),
            )
            auditPort.recordTaskEvent(
                taskId = taskId,
                traceId = traceId,
                eventType = "policy_refused",
                payload = mapOf(
                    "reason" to (decision.reason ?: ""),
                ),
            )
            return loadSnapshotOrFallback(
                taskId = taskId,
                userMessage = userMessage,
                fallbackState = TaskState.REFUSED,
                actionSpec = actionSpec,
                planningTrace = planningTrace,
                errorMessage = decision.reason,
            )
        }

        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "policy_allowed",
            payload = mapOf(
                "requires_confirmation" to decision.requiresConfirmation.toString(),
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "policy_allowed",
            payload = mapOf(
                "requires_confirmation" to decision.requiresConfirmation.toString(),
            ),
        )

        if (decision.requiresConfirmation) {
            sessionPort.appendTaskEvent(
                taskId = taskId,
                event = TaskEvent(
                    type = TaskEventType.CONFIRMATION_REQUESTED,
                    actionSpec = actionSpec,
                ),
            )
            telemetryPort.recordTaskEvent(
                taskId = taskId,
                eventType = "confirmation_requested",
                payload = mapOf(
                    "action_id" to actionSpec.actionId,
                ),
            )
            auditPort.recordTaskEvent(
                taskId = taskId,
                traceId = traceId,
                eventType = "confirmation_requested",
                payload = mapOf(
                    "action_id" to actionSpec.actionId,
                ),
            )
            transitionTask(taskId, traceId, TaskState.AWAITING_CONFIRMATION)
            return loadSnapshotOrFallback(
                taskId = taskId,
                userMessage = userMessage,
                fallbackState = TaskState.AWAITING_CONFIRMATION,
                actionSpec = actionSpec,
                planningTrace = planningTrace,
            )
        }

        return executeApprovedAction(
            taskId = taskId,
            traceId = traceId,
            userMessage = userMessage,
            actionSpec = actionSpec,
            planningTrace = planningTrace,
        )
    }

    private suspend fun handleClarificationNeeded(
        taskId: String,
        traceId: String,
        userMessage: String,
        planningTrace: PlanningTrace,
        question: String,
    ): TaskSnapshot {
        transitionTask(taskId, traceId, TaskState.NEEDS_CLARIFICATION)
        sessionPort.appendTaskEvent(
            taskId = taskId,
            event = TaskEvent(
                type = TaskEventType.CLARIFICATION_NEEDED,
                errorMessage = question,
            ),
        )
        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "clarification_needed",
            payload = mapOf(
                "question" to question,
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "clarification_needed",
            payload = mapOf(
                "question" to question,
            ),
        )
        return loadSnapshotOrFallback(
            taskId = taskId,
            userMessage = userMessage,
            fallbackState = TaskState.NEEDS_CLARIFICATION,
            planningTrace = planningTrace,
            errorMessage = question,
        )
    }

    private fun handlePlannerRefused(
        taskId: String,
        traceId: String,
        userMessage: String,
        planningTrace: PlanningTrace,
        reason: String,
    ): TaskSnapshot {
        transitionTask(taskId, traceId, TaskState.REFUSED)
        sessionPort.appendTaskEvent(
            taskId = taskId,
            event = TaskEvent(
                type = TaskEventType.PLANNER_REFUSED,
                errorMessage = reason,
            ),
        )
        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "planner_refused",
            payload = mapOf(
                "reason" to reason,
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "planner_refused",
            payload = mapOf(
                "reason" to reason,
            ),
        )
        return loadSnapshotOrFallback(
            taskId = taskId,
            userMessage = userMessage,
            fallbackState = TaskState.REFUSED,
            planningTrace = planningTrace,
            errorMessage = reason,
        )
    }

    private suspend fun executeApprovedAction(
        taskId: String,
        traceId: String,
        userMessage: String,
        actionSpec: ActionSpec,
        planningTrace: PlanningTrace?,
    ): TaskSnapshot {
        transitionTask(taskId, traceId, TaskState.APPROVED)
        transitionTask(taskId, traceId, TaskState.EXECUTING)

        val executionRequest = ExecutionRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = taskId,
            actionSpec = actionSpec,
        )
        val rawResult = executorPort.execute(executionRequest)
        val result = maybeSummarizeWebContent(taskId, traceId, userMessage, rawResult)

        sessionPort.storeExecutionResult(taskId, result)
        sessionPort.appendTaskEvent(
            taskId = taskId,
            event = TaskEvent(
                type = if (result.status == "success") {
                    TaskEventType.EXECUTION_COMPLETED
                } else {
                    TaskEventType.EXECUTION_FAILED
                },
                errorMessage = result.errorMessage,
            ),
        )
        telemetryPort.recordExecutionTrace(taskId, result)
        auditPort.recordExecutionTrace(taskId, traceId, result)

        val finalState = if (result.status == "success") TaskState.SUCCEEDED else TaskState.FAILED
        transitionTask(taskId, traceId, finalState)

        return loadSnapshotOrFallback(
            taskId = taskId,
            userMessage = userMessage,
            fallbackState = finalState,
            actionSpec = actionSpec,
            planningTrace = planningTrace,
            executionResult = result,
            errorMessage = result.errorMessage,
        )
    }

    private suspend fun maybeSummarizeWebContent(
        taskId: String,
        traceId: String,
        userMessage: String,
        result: ExecutionResult,
    ): ExecutionResult {
        if (result.status != "success" || result.actionId != ACTION_FETCH_WEB_PAGE_CONTENT) {
            return result
        }

        val summary = summaryPort.summarize(taskId, userMessage, result.outputData)
            ?.trim()
            .orEmpty()

        if (summary.isBlank()) {
            return result
        }

        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "web_content_summarized",
            payload = mapOf(
                "summary_length" to summary.length.toString(),
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "web_content_summarized",
            payload = mapOf(
                "summary_length" to summary.length.toString(),
            ),
        )

        return result.copy(
            outputData = result.outputData + ("ai_summary" to summary),
        )
    }

    private fun transitionTask(taskId: String, traceId: String, state: TaskState) {
        sessionPort.updateTaskState(taskId, state)
        telemetryPort.recordTaskEvent(
            taskId = taskId,
            eventType = "state_changed",
            payload = mapOf(
                "state" to state.name,
            ),
        )
        auditPort.recordTaskEvent(
            taskId = taskId,
            traceId = traceId,
            eventType = "state_changed",
            payload = mapOf(
                "state" to state.name,
            ),
        )
    }

    private fun loadSnapshotOrFallback(
        taskId: String,
        userMessage: String,
        fallbackState: TaskState,
        actionSpec: ActionSpec? = null,
        planningTrace: PlanningTrace? = null,
        executionResult: ExecutionResult? = null,
        errorMessage: String? = null,
    ): TaskSnapshot {
        return sessionPort.loadSnapshot(taskId)
            ?: TaskSnapshot(
                taskId = taskId,
                state = fallbackState,
                userMessage = userMessage,
                actionSpec = actionSpec,
                planningTrace = planningTrace,
                executionResult = executionResult,
                errorMessage = errorMessage,
            )
    }
}
