package com.phoneclaw.app.executor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.VerificationResult

interface ActionExecutor {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

class IntentActionExecutor(
    private val appContext: Context,
) : ActionExecutor {
    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        return when (request.actionSpec.actionId) {
            "open_system_settings" -> openSystemSettings(request)
            else -> ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "failed",
                resultSummary = "Unsupported action",
                errorMessage = "Executor has no implementation for ${request.actionSpec.actionId}.",
                verification = VerificationResult(
                    passed = false,
                    reason = "No executor route matched the requested action.",
                ),
            )
        }
    }

    private fun openSystemSettings(request: ExecutionRequest): ExecutionResult {
        return runCatching {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)

            ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "success",
                resultSummary = "System settings was launched.",
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
                resultSummary = "System settings launch failed.",
                errorMessage = error.message,
                verification = VerificationResult(
                    passed = false,
                    reason = "Intent dispatch raised an exception.",
                ),
            )
        }
    }
}

