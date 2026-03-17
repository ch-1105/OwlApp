package com.phoneclaw.app.gateway

import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.executor.ActionExecutor
import com.phoneclaw.app.model.PlanningOutcome
import com.phoneclaw.app.model.PlanningService
import com.phoneclaw.app.policy.PolicyEngine
import java.util.UUID

interface Gateway {
    suspend fun submitUserMessage(userMessage: String): TaskSnapshot
}

class DefaultGateway(
    private val planningService: PlanningService,
    private val policyEngine: PolicyEngine,
    private val actionExecutor: ActionExecutor,
) : Gateway {
    override suspend fun submitUserMessage(userMessage: String): TaskSnapshot {
        val taskId = UUID.randomUUID().toString()
        val planning = planningService.planAction(taskId, userMessage)

        return when (val outcome = planning.outcome) {
            is PlanningOutcome.PlannedAction -> {
                val decision = policyEngine.review(outcome.actionSpec)
                if (!decision.allowed) {
                    TaskSnapshot(
                        taskId = taskId,
                        state = TaskState.REFUSED,
                        userMessage = userMessage,
                        actionSpec = outcome.actionSpec,
                        planningTrace = planning.trace,
                        errorMessage = decision.reason,
                    )
                } else {
                    val executionRequest = ExecutionRequest(
                        requestId = UUID.randomUUID().toString(),
                        taskId = taskId,
                        actionSpec = outcome.actionSpec,
                    )
                    val result = actionExecutor.execute(executionRequest)
                    TaskSnapshot(
                        taskId = taskId,
                        state = if (result.status == "success") TaskState.SUCCEEDED else TaskState.FAILED,
                        userMessage = userMessage,
                        actionSpec = outcome.actionSpec,
                        planningTrace = planning.trace,
                        executionResult = result,
                        errorMessage = result.errorMessage,
                    )
                }
            }

            is PlanningOutcome.ClarificationNeeded -> {
                TaskSnapshot(
                    taskId = taskId,
                    state = TaskState.FAILED,
                    userMessage = userMessage,
                    planningTrace = planning.trace,
                    errorMessage = outcome.question,
                )
            }

            is PlanningOutcome.Refused -> {
                TaskSnapshot(
                    taskId = taskId,
                    state = TaskState.REFUSED,
                    userMessage = userMessage,
                    planningTrace = planning.trace,
                    errorMessage = outcome.reason,
                )
            }
        }
    }
}
