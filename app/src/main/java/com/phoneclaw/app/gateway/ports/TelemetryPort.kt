package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace

interface TelemetryPort {
    fun recordTaskEvent(taskId: String, eventType: String, payload: Map<String, String> = emptyMap())
    fun recordModelTrace(taskId: String, trace: PlanningTrace)
    fun recordExecutionTrace(taskId: String, result: ExecutionResult)
}
