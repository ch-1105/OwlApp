package com.phoneclaw.app.explorer

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AccessibilityCaptureBridgeTest {
    @Before
    fun setUp() {
        AccessibilityCaptureBridge.resetForTest()
    }

    @After
    fun tearDown() {
        AccessibilityCaptureBridge.resetForTest()
    }

    @Test
    fun detach_keepsConnectedStateWhenAnotherServiceIsAttached() {
        val staleService = Robolectric.buildService(PhoneClawAccessibilityService::class.java).get()
        val activeService = Robolectric.buildService(PhoneClawAccessibilityService::class.java).get()

        AccessibilityCaptureBridge.attach(staleService)
        AccessibilityCaptureBridge.attach(activeService)
        AccessibilityCaptureBridge.detach(staleService)

        assertTrue(AccessibilityCaptureBridge.serviceConnected.value)
    }

    @Test
    fun detach_marksDisconnectedWhenActiveServiceDetaches() {
        val activeService = Robolectric.buildService(PhoneClawAccessibilityService::class.java).get()

        AccessibilityCaptureBridge.publishSnapshot(sampleSnapshot())
        AccessibilityCaptureBridge.attach(activeService)
        AccessibilityCaptureBridge.detach(activeService)

        assertFalse(AccessibilityCaptureBridge.serviceConnected.value)
        assertNull(AccessibilityCaptureBridge.latestSnapshot.value)
    }

    @Test
    fun publishSnapshot_marksServiceConnected() {
        AccessibilityCaptureBridge.publishSnapshot(sampleSnapshot())

        assertTrue(AccessibilityCaptureBridge.serviceConnected.value)
    }
}

private fun sampleSnapshot(): PageTreeSnapshot {
    return PageTreeSnapshot(
        packageName = "com.phoneclaw.app",
        activityName = "MainActivity",
        timestamp = 123L,
        nodes = listOf(
            NodeSnapshotInput(
                nodeId = "0",
                className = "android.view.View",
                text = "root",
                contentDescription = null,
                resourceId = null,
                isClickable = false,
                isScrollable = false,
                isEditable = false,
                bounds = Rect(0, 0, 10, 10),
                children = emptyList(),
            ).toSnapshot(),
        ),
    )
}
