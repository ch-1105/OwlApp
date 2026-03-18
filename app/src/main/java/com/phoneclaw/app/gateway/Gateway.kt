package com.phoneclaw.app.gateway

import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.executor.ActionExecutor
import com.phoneclaw.app.gateway.ports.PlannerOutcome
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.policy.PolicyEngine
import java.util.UUID

interface Gateway {
    suspend fun submitUserMessage(userMessage: String): TaskSnapshot
}

class DefaultGateway(
    private val plannerPort: PlannerPort,
    private val policyEngine: PolicyEngine,
    private val actionExecutor: ActionExecutor,
) : Gateway {
    override suspend fun submitUserMessage(userMessage: String): TaskSnapshot {
        val taskId = UUID.randomUUID().toString()
        val planning = plannerPort.planAction(taskId, userMessage)

        return when (val outcome = planning.outcome) {
            is PlannerOutcome.PlannedAction -> {
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

            is PlannerOutcome.ClarificationNeeded -> {
                TaskSnapshot(
                    taskId = taskId,
                    state = TaskState.FAILED,
                    userMessage = userMessage,
                    planningTrace = planning.trace,
                    errorMessage = outcome.question,
                )
            }

            is PlannerOutcome.Refused -> {
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

