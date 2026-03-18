package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace

interface AuditPort {
    fun recordTaskEvent(
        taskId: String,
        traceId: String,
        eventType: String,
        payload: Map<String, String> = emptyMap(),
    )

    fun recordModelTrace(taskId: String, traceId: String, trace: PlanningTrace)

    fun recordExecutionTrace(taskId: String, traceId: String, result: ExecutionResult)
}
