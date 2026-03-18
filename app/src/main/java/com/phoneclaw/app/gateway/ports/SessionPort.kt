package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState

enum class TaskEventType {
    TASK_CREATED,
    PLANNING_COMPLETED,
    ACTION_PLANNED,
    POLICY_REFUSED,
    CLARIFICATION_NEEDED,
    EXECUTION_COMPLETED,
    EXECUTION_FAILED,
    PLANNER_REFUSED,
}

data class TaskEvent(
    val type: TaskEventType,
    val actionSpec: ActionSpec? = null,
    val planningTrace: PlanningTrace? = null,
    val errorMessage: String? = null,
    val message: String? = null,
)

interface SessionPort {
    fun createTask(sessionId: String, userMessage: String): String
    fun updateTaskState(taskId: String, state: TaskState)
    fun appendTaskEvent(taskId: String, event: TaskEvent)
    fun storeExecutionResult(taskId: String, result: ExecutionResult)
    fun loadSnapshot(taskId: String): TaskSnapshot?
}
