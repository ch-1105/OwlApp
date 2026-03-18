package com.phoneclaw.app.audit

import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.gateway.ports.AuditPort
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

class FileAuditTrail(
    private val auditRootDir: File,
    private val clock: Clock = Clock.systemUTC(),
) : AuditPort {
    private val writeLock = Any()

    override fun recordTaskEvent(
        taskId: String,
        traceId: String,
        eventType: String,
        payload: Map<String, String>,
    ) {
        appendLine(
            taskId = taskId,
            traceId = traceId,
            category = "task_event",
            payload = mapOf("event_type" to eventType) + payload,
        )
    }

    override fun recordModelTrace(taskId: String, traceId: String, trace: PlanningTrace) {
        appendLine(
            taskId = taskId,
            traceId = traceId,
            category = "model_trace",
            payload = mapOf(
                "provider" to trace.provider,
                "model_id" to trace.modelId,
                "used_remote" to trace.usedRemote.toString(),
                "error_kind" to (trace.errorKind?.name ?: ""),
                "error_message" to (trace.errorMessage ?: ""),
                "output_text" to trace.outputText,
            ),
        )
    }

    override fun recordExecutionTrace(taskId: String, traceId: String, result: ExecutionResult) {
        appendLine(
            taskId = taskId,
            traceId = traceId,
            category = "execution_trace",
            payload = mapOf(
                "request_id" to result.requestId,
                "action_id" to result.actionId,
                "status" to result.status,
                "result_summary" to result.resultSummary,
                "error_message" to (result.errorMessage ?: ""),
                "verification_passed" to result.verification.passed.toString(),
                "verification_reason" to result.verification.reason,
            ) + result.outputData.mapKeys { "output_${it.key}" },
        )
    }

    private fun appendLine(
        taskId: String,
        traceId: String,
        category: String,
        payload: Map<String, String>,
    ) {
        runCatching {
            synchronized(writeLock) {
                if (!auditRootDir.exists() && !auditRootDir.mkdirs()) {
                    return
                }

                val auditFile = File(auditRootDir, "$taskId.log")
                val line = buildString {
                    appendField("timestamp", Instant.now(clock).toString())
                    append(' ')
                    appendField("task_id", taskId)
                    append(' ')
                    appendField("trace_id", traceId)
                    append(' ')
                    appendField("category", category)
                    payload.toSortedMap().forEach { (key, value) ->
                        append(' ')
                        appendField(key.normalizeFieldName(), value)
                    }
                }

                auditFile.appendText("$line${System.lineSeparator()}", StandardCharsets.UTF_8)
            }
        }
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        append(key)
        append("=\"")
        append(value.toAuditValue())
        append('"')
    }

    private fun String.normalizeFieldName(): String {
        return replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    private fun String.toAuditValue(): String {
        val normalized = replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\"", "\\\"")

        return if (normalized.length <= MAX_VALUE_LENGTH) {
            normalized
        } else {
            normalized.take(MAX_VALUE_LENGTH) + "...(truncated)"
        }
    }

    private companion object {
        private const val MAX_VALUE_LENGTH = 2_000
    }
}
