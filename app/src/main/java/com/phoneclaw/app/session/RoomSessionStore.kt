package com.phoneclaw.app.session

import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.ModelErrorKind
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.data.db.TaskDao
import com.phoneclaw.app.data.db.TaskEntity
import com.phoneclaw.app.gateway.ports.SessionPort
import com.phoneclaw.app.gateway.ports.TaskEvent
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class RoomSessionStore(
    private val taskDao: TaskDao,
) : SessionPort {
    override fun createTask(sessionId: String, userMessage: String): String = runBlocking {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        taskDao.insert(
            TaskEntity(
                taskId = taskId,
                sessionId = sessionId,
                userMessage = userMessage,
                state = TaskState.RECEIVED.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
        taskId
    }

    override fun updateTaskState(taskId: String, state: TaskState) {
        runBlocking {
            val entity = taskDao.getById(taskId) ?: return@runBlocking
            taskDao.update(
                entity.copy(
                    state = state.name,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun appendTaskEvent(taskId: String, event: TaskEvent) {
        runBlocking {
            val entity = taskDao.getById(taskId) ?: return@runBlocking
            taskDao.update(
                entity.copy(
                    actionSpecJson = event.actionSpec?.let(::encodeActionSpec) ?: entity.actionSpecJson,
                    planningTraceJson = event.planningTrace?.let(::encodePlanningTrace) ?: entity.planningTraceJson,
                    errorMessage = event.errorMessage ?: entity.errorMessage,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun storeExecutionResult(taskId: String, result: ExecutionResult) {
        runBlocking {
            val entity = taskDao.getById(taskId) ?: return@runBlocking
            taskDao.update(
                entity.copy(
                    executionResultJson = encodeExecutionResult(result),
                    errorMessage = result.errorMessage,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun loadSnapshot(taskId: String): TaskSnapshot? = runBlocking {
        taskDao.getById(taskId)?.toTaskSnapshot()
    }
}

private fun TaskEntity.toTaskSnapshot(): TaskSnapshot {
    return TaskSnapshot(
        taskId = taskId,
        state = TaskState.valueOf(state),
        userMessage = userMessage,
        actionSpec = actionSpecJson?.let(::decodeActionSpec),
        planningTrace = planningTraceJson?.let(::decodePlanningTrace),
        executionResult = executionResultJson?.let(::decodeExecutionResult),
        errorMessage = errorMessage,
    )
}

private fun encodeActionSpec(actionSpec: ActionSpec): String {
    return JSONObject().apply {
        put("schemaVersion", actionSpec.schemaVersion)
        put("actionId", actionSpec.actionId)
        put("skillId", actionSpec.skillId)
        put("taskId", actionSpec.taskId)
        put("intentSummary", actionSpec.intentSummary)
        put("params", JSONObject(actionSpec.params))
        put("riskLevel", actionSpec.riskLevel.name)
        put("requiresConfirmation", actionSpec.requiresConfirmation)
        put("executorType", actionSpec.executorType)
        put("expectedOutcome", actionSpec.expectedOutcome)
    }.toString()
}

private fun decodeActionSpec(json: String): ActionSpec {
    val objectJson = JSONObject(json)
    return ActionSpec(
        schemaVersion = objectJson.getString("schemaVersion"),
        actionId = objectJson.getString("actionId"),
        skillId = objectJson.getString("skillId"),
        taskId = objectJson.getString("taskId"),
        intentSummary = objectJson.getString("intentSummary"),
        params = objectJson.getJSONObject("params").toStringMap(),
        riskLevel = RiskLevel.valueOf(objectJson.getString("riskLevel")),
        requiresConfirmation = objectJson.getBoolean("requiresConfirmation"),
        executorType = objectJson.getString("executorType"),
        expectedOutcome = objectJson.getString("expectedOutcome"),
    )
}

private fun encodePlanningTrace(trace: PlanningTrace): String {
    return JSONObject().apply {
        put("provider", trace.provider)
        put("modelId", trace.modelId)
        put("outputText", trace.outputText)
        trace.errorMessage?.let { put("errorMessage", it) }
        trace.errorKind?.let { put("errorKind", it.name) }
        put("usedRemote", trace.usedRemote)
    }.toString()
}

private fun decodePlanningTrace(json: String): PlanningTrace {
    val objectJson = JSONObject(json)
    return PlanningTrace(
        provider = objectJson.getString("provider"),
        modelId = objectJson.getString("modelId"),
        outputText = objectJson.getString("outputText"),
        errorMessage = objectJson.optString("errorMessage").takeIf { objectJson.has("errorMessage") },
        errorKind = objectJson.optString("errorKind").takeIf { objectJson.has("errorKind") }?.let(ModelErrorKind::valueOf),
        usedRemote = objectJson.getBoolean("usedRemote"),
    )
}

private fun encodeExecutionResult(result: ExecutionResult): String {
    return JSONObject().apply {
        put("schemaVersion", result.schemaVersion)
        put("requestId", result.requestId)
        put("taskId", result.taskId)
        put("actionId", result.actionId)
        put("status", result.status)
        put("resultSummary", result.resultSummary)
        put("outputData", JSONObject(result.outputData))
        result.errorMessage?.let { put("errorMessage", it) }
        put(
            "verification",
            JSONObject().apply {
                put("passed", result.verification.passed)
                put("reason", result.verification.reason)
            },
        )
    }.toString()
}

private fun decodeExecutionResult(json: String): ExecutionResult {
    val objectJson = JSONObject(json)
    val verificationJson = objectJson.getJSONObject("verification")
    return ExecutionResult(
        schemaVersion = objectJson.getString("schemaVersion"),
        requestId = objectJson.getString("requestId"),
        taskId = objectJson.getString("taskId"),
        actionId = objectJson.getString("actionId"),
        status = objectJson.getString("status"),
        resultSummary = objectJson.getString("resultSummary"),
        outputData = objectJson.getJSONObject("outputData").toStringMap(),
        errorMessage = objectJson.optString("errorMessage").takeIf { objectJson.has("errorMessage") },
        verification = VerificationResult(
            passed = verificationJson.getBoolean("passed"),
            reason = verificationJson.getString("reason"),
        ),
    )
}

private fun JSONObject.toStringMap(): Map<String, String> {
    return keys().asSequence().associateWith { key -> getString(key) }
}
