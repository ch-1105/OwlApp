package com.phoneclaw.app.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoneclaw.app.contracts.ActionSpec
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.PlanningTrace
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.data.db.PhoneClawDatabase
import com.phoneclaw.app.gateway.ports.TaskEvent
import com.phoneclaw.app.gateway.ports.TaskEventType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomSessionStoreTest {
    private lateinit var database: PhoneClawDatabase
    private lateinit var store: RoomSessionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhoneClawDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSessionStore(database.taskDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createTask_startsFromReceivedState() {
        val taskId = store.createTask(sessionId = "s1", userMessage = "open settings")
        val snapshot = store.loadSnapshot(taskId)

        assertNotNull(snapshot)
        assertEquals(taskId, snapshot?.taskId)
        assertEquals(TaskState.RECEIVED, snapshot?.state)
        assertEquals("open settings", snapshot?.userMessage)
        assertNull(snapshot?.actionSpec)
        assertNull(snapshot?.executionResult)
    }

    @Test
    fun storeAndLoadSnapshot_containsPlannedActionAndExecutionResult() {
        val taskId = store.createTask(sessionId = "s2", userMessage = "open wifi settings")

        val actionSpec = ActionSpec(
            actionId = "open_wifi_settings",
            skillId = "system.wifi_settings",
            taskId = taskId,
            intentSummary = "Open Wi-Fi settings",
            params = emptyMap(),
            riskLevel = RiskLevel.SAFE,
            requiresConfirmation = false,
            executorType = "intent",
            expectedOutcome = "Wi-Fi settings is foregrounded",
        )
        val trace = PlanningTrace(
            provider = "stub-cloud",
            modelId = "stub-router",
            outputText = "Planned open_wifi_settings",
            usedRemote = false,
        )
        val executionResult = ExecutionResult(
            requestId = "req-1",
            taskId = taskId,
            actionId = "open_wifi_settings",
            status = "success",
            resultSummary = "Launched Wi-Fi settings",
            verification = VerificationResult(
                passed = true,
                reason = "Intent dispatch completed",
            ),
        )

        store.updateTaskState(taskId, TaskState.PLANNING)
        store.appendTaskEvent(
            taskId,
            TaskEvent(
                type = TaskEventType.ACTION_PLANNED,
                actionSpec = actionSpec,
                planningTrace = trace,
            ),
        )
        store.storeExecutionResult(taskId, executionResult)
        store.updateTaskState(taskId, TaskState.SUCCEEDED)

        val snapshot = store.loadSnapshot(taskId)

        assertNotNull(snapshot)
        assertEquals(TaskState.SUCCEEDED, snapshot?.state)
        assertEquals(actionSpec, snapshot?.actionSpec)
        assertEquals(trace, snapshot?.planningTrace)
        assertEquals(executionResult, snapshot?.executionResult)
        assertNull(snapshot?.errorMessage)
    }
}
