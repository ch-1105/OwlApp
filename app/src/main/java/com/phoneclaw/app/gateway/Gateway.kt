package com.phoneclaw.app.gateway

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.executor.ActionExecutor
import com.phoneclaw.app.gateway.ports.PlannerOutcome
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.SessionPort
import com.phoneclaw.app.gateway.ports.TaskEvent
import com.phoneclaw.app.gateway.ports.TaskEventType
import com.phoneclaw.app.policy.PolicyEngine
import java.util.UUID

private const val DEFAULT_SESSION_ID = "default"

interface Gateway {
    suspend fun submitUserMessage(userMessage: String): TaskSnapshot
}

class DefaultGateway(
    private val plannerPort: PlannerPort,
    private val policyEngine: PolicyEngine,
    private val actionExecutor: ActionExecutor,
    private val sessionPort: SessionPort,
) : Gateway {
    override suspend fun submitUserMessage(userMessage: String): TaskSnapshot {
        val taskId = sessionPort.createTask(DEFAULT_SESSION_ID, userMessage)
        sessionPort.updateTaskState(taskId, TaskState.PLANNING)

        val planning = plannerPort.planAction(taskId, userMessage)
        sessionPort.appendTaskEvent(
            taskId = taskId,
            event = TaskEvent(
                type = TaskEventType.PLANNING_COMPLETED,
                planningTrace = planning.trace,
            ),
        )

        return when (val outcome = planning.outcome) {
            is PlannerOutcome.PlannedAction -> {
                sessionPort.appendTaskEvent(
                    taskId = taskId,
                    event = TaskEvent(
                        type = TaskEventType.ACTION_PLANNED,
                        actionSpec = outcome.actionSpec,
                    ),
                )

                val decision = policyEngine.review(outcome.actionSpec)
                if (!decision.allowed) {
                    sessionPort.updateTaskState(taskId, TaskState.REFUSED)
                    sessionPort.appendTaskEvent(
                        taskId = taskId,
                        event = TaskEvent(
                            type = TaskEventType.POLICY_REFUSED,
                            errorMessage = decision.reason,
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
                    if (decision.requiresConfirmation) {
                        sessionPort.updateTaskState(taskId, TaskState.AWAITING_CONFIRMATION)
                    }
                    sessionPort.updateTaskState(taskId, TaskState.APPROVED)
                    sessionPort.updateTaskState(taskId, TaskState.EXECUTING)

                    val executionRequest = ExecutionRequest(
                        requestId = UUID.randomUUID().toString(),
                        taskId = taskId,
                        actionSpec = outcome.actionSpec,
                    )
                    val result = actionExecutor.execute(executionRequest)
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

                    val finalState = if (result.status == "success") TaskState.SUCCEEDED else TaskState.FAILED
                    sessionPort.updateTaskState(taskId, finalState)

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
                sessionPort.updateTaskState(taskId, TaskState.FAILED)
                sessionPort.appendTaskEvent(
                    taskId = taskId,
                    event = TaskEvent(
                        type = TaskEventType.CLARIFICATION_NEEDED,
                        errorMessage = outcome.question,
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
                sessionPort.updateTaskState(taskId, TaskState.REFUSED)
                sessionPort.appendTaskEvent(
                    taskId = taskId,
                    event = TaskEvent(
                        type = TaskEventType.PLANNER_REFUSED,
                        errorMessage = outcome.reason,
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
