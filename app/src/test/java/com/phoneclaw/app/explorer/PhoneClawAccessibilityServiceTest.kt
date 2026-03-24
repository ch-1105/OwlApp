package com.phoneclaw.app.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneClawAccessibilityServiceTest {
    @Test
    fun resolveSnapshotMetadata_prefersRootPackageName() {
        val metadata = resolveSnapshotMetadata(
            rootPackageName = "com.android.chromium",
            eventPackageName = "app.lawnchair",
            eventActivityName = "LauncherActivity",
        )

        assertEquals("com.android.chromium", metadata?.packageName)
    }

    @Test
    fun resolveSnapshotMetadata_clearsActivityWhenRootAndEventPackagesDiffer() {
        val metadata = resolveSnapshotMetadata(
            rootPackageName = "com.android.chromium",
            eventPackageName = "app.lawnchair",
            eventActivityName = "LauncherActivity",
        )

        assertNull(metadata?.activityName)
    }

    @Test
    fun resolveSnapshotMetadata_keepsActivityWhenPackagesMatch() {
        val metadata = resolveSnapshotMetadata(
            rootPackageName = "com.android.chromium",
            eventPackageName = "com.android.chromium",
            eventActivityName = "ChromeActivity",
        )

        assertEquals("ChromeActivity", metadata?.activityName)
    }
}
