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
import com.phoneclaw.app.gateway.ports.TaskEvent
import com.phoneclaw.app.gateway.ports.TaskEventType
import com.phoneclaw.app.gateway.ports.TelemetryPort
import java.util.UUID

private const val DEFAULT_SESSION_ID = "default"
private const val ACTION_FETCH_WEB_PAGE_CONTENT = "fetch_web_page_content"

interface Gateway {
    suspend fun submitUserMessage(userMessage: String): TaskSnapshot
}

class DefaultGateway(
    private val plannerPort: PlannerPort,
    private val policyPort: PolicyPort,
    private val executorPort: ExecutorPort,
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
            is PlannerOutcome.PlannedAction -> {
                sessionPort.appendTaskEvent(
                    taskId = taskId,
                    event = TaskEvent(
                        type = TaskEventType.ACTION_PLANNED,
                        actionSpec = outcome.actionSpec,
                    ),
                )
                telemetryPort.recordTaskEvent(
                    taskId = taskId,
                    eventType = "action_planned",
                    payload = mapOf(
                        "action_id" to outcome.actionSpec.actionId,
                        "skill_id" to outcome.actionSpec.skillId,
                        "risk_level" to outcome.actionSpec.riskLevel.name,
                    ),
                )
                auditPort.recordTaskEvent(
                    taskId = taskId,
                    traceId = traceId,
                    eventType = "action_planned",
                    payload = mapOf(
                        "action_id" to outcome.actionSpec.actionId,
                        "skill_id" to outcome.actionSpec.skillId,
                        "risk_level" to outcome.actionSpec.riskLevel.name,
                    ),
                )

                val decision = policyPort.review(outcome.actionSpec)
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
                    loadSnapshotOrFallback(
                        taskId = taskId,
                        userMessage = userMessage,
                        fallbackState = TaskState.REFUSED,
                        actionSpec = outcome.actionSpec,
                        planningTrace = planning.trace,
                        errorMessage = decision.reason,
                    )
                } else {
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
                        transitionTask(taskId, traceId, TaskState.AWAITING_CONFIRMATION)
                    }
                    transitionTask(taskId, traceId, TaskState.APPROVED)
                    transitionTask(taskId, traceId, TaskState.EXECUTING)

                    val executionRequest = ExecutionRequest(
                        requestId = UUID.randomUUID().toString(),
                        taskId = taskId,
                        actionSpec = outcome.actionSpec,
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

                    loadSnapshotOrFallback(
                        taskId = taskId,
                        userMessage = userMessage,
                        fallbackState = finalState,
                        actionSpec = outcome.actionSpec,
                        planningTrace = planning.trace,
                        executionResult = result,
                        errorMessage = result.errorMessage,
                    )
                }
            }

            is PlannerOutcome.ClarificationNeeded -> {
                transitionTask(taskId, traceId, TaskState.FAILED)
                sessionPort.appendTaskEvent(
                    taskId = taskId,
                    event = TaskEvent(
                        type = TaskEventType.CLARIFICATION_NEEDED,
                        errorMessage = outcome.question,
                    ),
                )
                telemetryPort.recordTaskEvent(
                    taskId = taskId,
                    eventType = "clarification_needed",
                    payload = mapOf(
                        "question" to outcome.question,
                    ),
                )
                auditPort.recordTaskEvent(
                    taskId = taskId,
                    traceId = traceId,
                    eventType = "clarification_needed",
                    payload = mapOf(
                        "question" to outcome.question,
                    ),
                )
                loadSnapshotOrFallback(
                    taskId = taskId,
                    userMessage = userMessage,
                    fallbackState = TaskState.FAILED,
                    planningTrace = planning.trace,
                    errorMessage = outcome.question,
                )
            }

            is PlannerOutcome.Refused -> {
                transitionTask(taskId, traceId, TaskState.REFUSED)
                sessionPort.appendTaskEvent(
                    taskId = taskId,
                    event = TaskEvent(
                        type = TaskEventType.PLANNER_REFUSED,
                        errorMessage = outcome.reason,
                    ),
                )
                telemetryPort.recordTaskEvent(
                    taskId = taskId,
                    eventType = "planner_refused",
                    payload = mapOf(
                        "reason" to outcome.reason,
                    ),
                )
                auditPort.recordTaskEvent(
                    taskId = taskId,
                    traceId = traceId,
                    eventType = "planner_refused",
                    payload = mapOf(
                        "reason" to outcome.reason,
                    ),
                )
                loadSnapshotOrFallback(
                    taskId = taskId,
                    userMessage = userMessage,
                    fallbackState = TaskState.REFUSED,
                    planningTrace = planning.trace,
                    errorMessage = outcome.reason,
                )
            }
        }
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

        val summary = plannerPort.summarizeWebContent(taskId, userMessage, result.outputData)
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

