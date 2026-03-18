package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.PlanningTrace

sealed interface PlannerOutcome {
    data class PlannedAction(val actionSpec: ActionSpec) : PlannerOutcome
    data class ClarificationNeeded(val question: String) : PlannerOutcome
    data class Refused(val reason: String) : PlannerOutcome
}

data class PlannerResult(
    val outcome: PlannerOutcome,
    val trace: PlanningTrace,
)

interface PlannerPort {
    suspend fun planAction(taskId: String, userMessage: String): PlannerResult
    suspend fun summarizeWebContent(taskId: String, userMessage: String, webContent: Map<String, String>): String?
}
