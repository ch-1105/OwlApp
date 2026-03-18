package com.phoneclaw.app.scanner

import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val iconDrawable: Drawable?,
)

interface AppScanner {
    fun scanInstalledApps(): List<InstalledApp>
}
