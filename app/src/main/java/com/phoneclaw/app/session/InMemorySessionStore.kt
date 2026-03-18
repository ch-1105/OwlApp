package com.phoneclaw.app.session

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.gateway.ports.SessionPort
import com.phoneclaw.app.gateway.ports.TaskEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemorySessionStore : SessionPort {
    private val tasks = ConcurrentHashMap<String, TaskRecord>()

    override fun createTask(sessionId: String, userMessage: String): String {
        val taskId = UUID.randomUUID().toString()
        val record = TaskRecord(
            sessionId = sessionId,
            taskId = taskId,
            userMessage = userMessage,
            state = TaskState.RECEIVED,
        )
        tasks[taskId] = record
        return taskId
    }

    override fun updateTaskState(taskId: String, state: TaskState) {
        tasks[taskId]?.let { record ->
            synchronized(record) {
                record.state = state
            }
        }
    }

    override fun appendTaskEvent(taskId: String, event: TaskEvent) {
        tasks[taskId]?.let { record ->
            synchronized(record) {
                event.actionSpec?.let { record.actionSpec = it }
                event.planningTrace?.let { record.planningTrace = it }
                event.errorMessage?.let { record.errorMessage = it }
                record.events.add(event)
            }
        }
    }

    override fun storeExecutionResult(taskId: String, result: ExecutionResult) {
        tasks[taskId]?.let { record ->
            synchronized(record) {
                record.executionResult = result
                record.errorMessage = result.errorMessage
            }
        }
    }

    override fun loadSnapshot(taskId: String): TaskSnapshot? {
        val record = tasks[taskId] ?: return null
        synchronized(record) {
            return TaskSnapshot(
                taskId = record.taskId,
                state = record.state,
                userMessage = record.userMessage,
                actionSpec = record.actionSpec,
                planningTrace = record.planningTrace,
                executionResult = record.executionResult,
                errorMessage = record.errorMessage,
            )
        }
    }

    private data class TaskRecord(
        val sessionId: String,
        val taskId: String,
        val userMessage: String,
        var state: TaskState,
        var actionSpec: ActionSpec? = null,
        var planningTrace: PlanningTrace? = null,
        var executionResult: ExecutionResult? = null,
        var errorMessage: String? = null,
        val events: MutableList<TaskEvent> = mutableListOf(),
    )
}
