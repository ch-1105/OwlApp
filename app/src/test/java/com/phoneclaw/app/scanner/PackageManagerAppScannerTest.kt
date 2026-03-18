package com.phoneclaw.app.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PackageManagerAppScannerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        installPackage(
            packageName = "com.example.clock",
            appName = "Clock",
            versionName = "1.2.0",
            versionCode = 12L,
            flags = 0,
        )
        installPackage(
            packageName = "com.android.settings",
            appName = "Settings",
            versionName = "14",
            versionCode = 140L,
            flags = ApplicationInfo.FLAG_SYSTEM,
        )
    }

    @Test
    fun scanInstalledApps_filtersOutSystemAppsByDefault() {
        val apps = PackageManagerAppScanner(context).scanInstalledApps()

        val clockApp = apps.firstOrNull { it.packageName == "com.example.clock" }
        assertTrue(clockApp != null)
        assertEquals("Clock", clockApp?.appName)
        assertEquals("1.2.0", clockApp?.versionName)
        assertEquals(12L, clockApp?.versionCode)
        assertFalse(clockApp?.isSystemApp == true)
        assertFalse(apps.any { it.packageName == "com.android.settings" })
    }

    @Test
    fun scanInstalledApps_canIncludeSystemApps() {
        val apps = PackageManagerAppScanner(context, includeSystemApps = true).scanInstalledApps()

        assertTrue(apps.any { it.packageName == "com.example.clock" })
        assertTrue(apps.any { it.packageName == "com.android.settings" && it.isSystemApp })
    }

    private fun installPackage(
        packageName: String,
        appName: String,
        versionName: String,
        versionCode: Long,
        flags: Int,
    ) {
        val applicationInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.flags = flags
            nonLocalizedLabel = appName
        }
        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = applicationInfo
            this.versionName = versionName
            setLongVersionCode(versionCode)
        }
        shadowOf(context.packageManager).installPackage(packageInfo)
    }
}
