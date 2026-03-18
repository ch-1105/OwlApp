package com.phoneclaw.app.telemetry

import android.util.Log
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.gateway.ports.TelemetryPort

class LogcatTelemetry : TelemetryPort {
    override fun recordTaskEvent(taskId: String, eventType: String, payload: Map<String, String>) {
        Log.i(
            TAG,
            """
            event=task_event task_id=$taskId event_type=$eventType payload=${payload.asLogPayload()}
            """.trimIndent(),
        )
    }

    override fun recordModelTrace(taskId: String, trace: PlanningTrace) {
        val payload = mapOf(
            "provider" to trace.provider,
            "model_id" to trace.modelId,
            "used_remote" to trace.usedRemote.toString(),
            "error_kind" to (trace.errorKind?.name ?: ""),
            "error_message" to (trace.errorMessage ?: ""),
            "output_text" to trace.outputText,
        )
        Log.i(
            TAG,
            """
            event=model_trace task_id=$taskId payload=${payload.asLogPayload()}
            """.trimIndent(),
        )
    }

    override fun recordExecutionTrace(taskId: String, result: ExecutionResult) {
        val payload = mapOf(
            "request_id" to result.requestId,
            "action_id" to result.actionId,
            "status" to result.status,
            "result_summary" to result.resultSummary,
            "error_message" to (result.errorMessage ?: ""),
            "verification_passed" to result.verification.passed.toString(),
            "verification_reason" to result.verification.reason,
        ) + result.outputData.mapKeys { "output_${it.key}" }

        Log.i(
            TAG,
            """
            event=execution_trace task_id=$taskId payload=${payload.asLogPayload()}
            """.trimIndent(),
        )
    }

    private fun Map<String, String>.asLogPayload(): String {
        return entries.joinToString(",") { entry ->
            "${entry.key}=${entry.value.oneLine()}"
        }
    }

    private fun String.oneLine(): String {
        return replace("\n", "\\n")
            .replace("\r", "")
    }

    private companion object {
        private const val TAG = "PhoneClaw.Telemetry"
    }
}
