package com.phoneclaw.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
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
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
            pageGraphJson = "{\"pages\":[]}",
            evidenceJson = "[]",
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

    @Test
    fun migration_2_3_addsLearnedSkillArtifactColumnsWithDefaults() = runBlocking {
        val databaseName = "phoneclaw-migration-2-3-test.db"
        context.deleteDatabase(databaseName)

        createVersion2SkillsDatabase(context.getDatabasePath(databaseName))

        val migratedDatabase = Room.databaseBuilder(
            context,
            PhoneClawDatabase::class.java,
            databaseName,
        )
            .addMigrations(PHONECLAW_DB_MIGRATION_1_2, PHONECLAW_DB_MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            val skill = requireNotNull(migratedDatabase.skillDao().getById("browser.web"))

            assertNull(skill.pageGraphJson)
            assertEquals("[]", skill.evidenceJson)
            assertEquals("builtin", skill.source)
            assertEquals(true, skill.enabled)
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }
}

private fun createVersion2SkillsDatabase(databaseFile: File) {
    databaseFile.parentFile?.mkdirs()
    val rawDatabase = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)

    try {
        rawDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                taskId TEXT NOT NULL PRIMARY KEY,
                sessionId TEXT NOT NULL,
                userMessage TEXT NOT NULL,
                state TEXT NOT NULL,
                actionSpecJson TEXT,
                executionResultJson TEXT,
                planningTraceJson TEXT,
                errorMessage TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        rawDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS authorized_apps (
                packageName TEXT NOT NULL PRIMARY KEY,
                appName TEXT NOT NULL,
                authorized INTEGER NOT NULL,
                authorizedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        rawDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS skills (
                skillId TEXT NOT NULL PRIMARY KEY,
                manifestJson TEXT NOT NULL,
                bindingsJson TEXT NOT NULL DEFAULT '[]',
                source TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                reviewStatus TEXT NOT NULL,
                learnedAt INTEGER,
                appVersion TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        rawDatabase.execSQL(
            """
            INSERT INTO skills (
                skillId,
                manifestJson,
                bindingsJson,
                source,
                enabled,
                reviewStatus,
                learnedAt,
                appVersion,
                createdAt,
                updatedAt
            ) VALUES (
                'browser.web',
                '{}',
                '[]',
                'builtin',
                1,
                'approved',
                NULL,
                NULL,
                10,
                10
            )
            """.trimIndent(),
        )
        rawDatabase.version = 2
    } finally {
        rawDatabase.close()
    }
}

