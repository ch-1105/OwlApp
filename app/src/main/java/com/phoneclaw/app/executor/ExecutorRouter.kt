package com.phoneclaw.app.executor

import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.gateway.ports.ExecutorPort

class ExecutorRouter(
    private val intentExecutor: ExecutorPort,
    private val accessibilityExecutor: ExecutorPort?,
) : ExecutorPort {

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        return when (request.actionSpec.executorType) {
            "intent", "browser_intent", "web_fetch" -> intentExecutor.execute(request)
            "accessibility" -> {
                accessibilityExecutor?.execute(request)
                    ?: failureResult(request, "无障碍执行器未启用，请先开启无障碍服务。")
            }
            else -> failureResult(
                request,
                "不支持的执行类型：${request.actionSpec.executorType}。",
            )
        }
    }

    private fun failureResult(request: ExecutionRequest, errorMessage: String): ExecutionResult {
        return ExecutionResult(
            requestId = request.requestId,
            taskId = request.taskId,
            actionId = request.actionSpec.actionId,
            status = "failed",
            resultSummary = "Unsupported executor type.",
            errorMessage = errorMessage,
            verification = VerificationResult(
                passed = false,
                reason = errorMessage,
            ),
        )
    }
}
