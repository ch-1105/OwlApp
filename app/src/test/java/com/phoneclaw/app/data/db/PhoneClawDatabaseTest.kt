package com.phoneclaw.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class PhoneClawDatabaseTest {
    private lateinit var database: PhoneClawDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhoneClawDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun taskDao_supportsBasicCrud() = runBlocking {
        val task = TaskEntity(
            taskId = "task-1",
            sessionId = "default",
            userMessage = "open wifi settings",
            state = "RECEIVED",
            createdAt = 1L,
            updatedAt = 1L,
        )

        database.taskDao().insert(task)
        assertEquals(task, database.taskDao().getById(task.taskId))
        assertEquals(listOf(task), database.taskDao().getAll())

        val updated = task.copy(state = "SUCCEEDED", updatedAt = 2L)
        database.taskDao().update(updated)
        assertEquals(updated, database.taskDao().getById(task.taskId))

        database.taskDao().deleteById(task.taskId)
        assertNull(database.taskDao().getById(task.taskId))
    }

    @Test
    fun skillDao_supportsBasicCrud() = runBlocking {
        val skill = SkillEntity(
            skillId = "browser.web",
            manifestJson = "{}",
            bindingsJson = "[]",
            source = "builtin",
            enabled = true,
            reviewStatus = "approved",
            createdAt = 10L,
            updatedAt = 10L,
        )

        database.skillDao().insert(skill)
        assertEquals(skill, database.skillDao().getById(skill.skillId))
        assertEquals(listOf(skill), database.skillDao().getAll())

        val updated = skill.copy(enabled = false, updatedAt = 20L)
        database.skillDao().update(updated)
        assertEquals(updated, database.skillDao().getById(skill.skillId))

        database.skillDao().deleteById(skill.skillId)
        assertNull(database.skillDao().getById(skill.skillId))
    }

    @Test
    fun authorizedAppDao_supportsBasicCrud() = runBlocking {
        val app = AuthorizedAppEntity(
            packageName = "com.example.clock",
            appName = "Clock",
            authorized = true,
            authorizedAt = 100L,
        )

        database.authorizedAppDao().insert(app)
        assertEquals(app, database.authorizedAppDao().getById(app.packageName))

        val storedApps = database.authorizedAppDao().getAll()
        assertEquals(1, storedApps.size)
        assertNotNull(storedApps.firstOrNull())

        val updated = app.copy(authorized = false, authorizedAt = 200L)
        database.authorizedAppDao().update(updated)
        assertEquals(updated, database.authorizedAppDao().getById(app.packageName))

        database.authorizedAppDao().deleteById(app.packageName)
        assertNull(database.authorizedAppDao().getById(app.packageName))
    }
}
