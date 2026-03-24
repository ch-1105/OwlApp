package com.phoneclaw.app.scanner

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
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
    fun scanInstalledApps_includesSystemAppsByDefault() {
        val apps = PackageManagerAppScanner(context).scanInstalledApps()

        val clockApp = apps.firstOrNull { it.packageName == "com.example.clock" }
        assertTrue(clockApp != null)
        assertEquals("Clock", clockApp?.appName)
        assertEquals("1.2.0", clockApp?.versionName)
        assertEquals(12L, clockApp?.versionCode)
        assertFalse(clockApp?.isSystemApp == true)
        assertTrue(apps.any { it.packageName == "com.android.settings" && it.isSystemApp })
    }

    @Test
    fun scanInstalledApps_canFilterOutSystemAppsWhenRequested() {
        val apps = PackageManagerAppScanner(context, includeSystemApps = false).scanInstalledApps()

        assertTrue(apps.any { it.packageName == "com.example.clock" })
        assertFalse(apps.any { it.packageName == "com.android.settings" })
    }

    @Test
    fun scanInstalledApps_includesLaunchableSystemAppsFromLauncherQuery() {
        addLaunchableApp(
            packageName = "com.android.chromium",
            appName = "浏览器",
            flags = ApplicationInfo.FLAG_SYSTEM,
        )

        val apps = PackageManagerAppScanner(context).scanInstalledApps()

        assertTrue(apps.any { app ->
            app.packageName == "com.android.chromium" &&
                app.appName == "浏览器" &&
                app.isSystemApp
        })
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

    private fun addLaunchableApp(
        packageName: String,
        appName: String,
        flags: Int,
    ) {
        val applicationInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.flags = flags
            nonLocalizedLabel = appName
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.MainActivity"
                this.applicationInfo = applicationInfo
            }
        }

        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            resolveInfo,
        )
    }
}
