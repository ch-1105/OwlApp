package com.phoneclaw.app.scanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoneclaw.app.data.db.PhoneClawDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomAuthorizationManagerTest {
    private lateinit var database: PhoneClawDatabase
    private lateinit var authorizationManager: RoomAuthorizationManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhoneClawDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authorizationManager = RoomAuthorizationManager(
            authorizedAppDao = database.authorizedAppDao(),
            clock = { 1234L },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun authorizeThenRevoke_updatesAuthorizationState() {
        assertFalse(authorizationManager.isAuthorized("com.example.clock"))

        authorizationManager.authorizeApp(
            packageName = "com.example.clock",
            appName = "Clock",
        )

        assertTrue(authorizationManager.isAuthorized("com.example.clock"))
        assertEquals(
            listOf("com.example.clock"),
            authorizationManager.getAuthorizedApps().map { it.packageName },
        )

        authorizationManager.revokeApp("com.example.clock")

        assertFalse(authorizationManager.isAuthorized("com.example.clock"))
        assertEquals(emptyList<String>(), authorizationManager.getAuthorizedApps().map { it.packageName })
    }
}
