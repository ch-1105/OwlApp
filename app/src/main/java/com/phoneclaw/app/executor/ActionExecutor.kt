package com.phoneclaw.app.executor

import android.content.Context
import android.content.Intent
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.skills.SkillRegistry

interface ActionExecutor {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

class IntentActionExecutor(
    private val appContext: Context,
    private val skillRegistry: SkillRegistry,
) : ActionExecutor {
    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val registeredAction = skillRegistry.findAction(request.actionSpec.actionId)
            ?: return ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "failed",
                resultSummary = "Unsupported action",
                errorMessage = "Executor has no registry entry for ${request.actionSpec.actionId}.",
                verification = VerificationResult(
                    passed = false,
                    reason = "No registered action matched the requested action id.",
                ),
            )

        if (registeredAction.action.executorType != "intent") {
            return ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "failed",
                resultSummary = "Unsupported executor type",
                errorMessage = "Executor only supports intent actions in this milestone.",
                verification = VerificationResult(
                    passed = false,
                    reason = "Registered action requires a non-intent executor.",
                ),
            )
        }

        return launchIntent(request, registeredAction.intentAction, registeredAction.action.displayName)
    }

    private fun launchIntent(
        request: ExecutionRequest,
        intentAction: String,
        displayName: String,
    ): ExecutionResult {
        return runCatching {
            val intent = Intent(intentAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)

            ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "success",
                resultSummary = "$displayName was launched.",
                verification = VerificationResult(
                    passed = true,
                    reason = "Intent dispatch completed without throwing.",
                ),
            )
        }.getOrElse { error ->
            ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "failed",
                resultSummary = "$displayName launch failed.",
                errorMessage = error.message,
                verification = VerificationResult(
                    passed = false,
                    reason = "Intent dispatch raised an exception.",
                ),
            )
        }
    }
}
