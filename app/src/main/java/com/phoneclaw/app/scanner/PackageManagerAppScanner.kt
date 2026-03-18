package com.phoneclaw.app.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

class PackageManagerAppScanner(
    private val context: Context,
    private val includeSystemApps: Boolean = false,
) : AppScanner {
    private val packageManager: PackageManager
        get() = context.packageManager

    override fun scanInstalledApps(): List<InstalledApp> {
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { applicationInfo -> includeSystemApps || !applicationInfo.isSystemApp() }
            .map { applicationInfo ->
                val packageInfo = packageManager.getPackageInfoCompat(applicationInfo.packageName)
                InstalledApp(
                    packageName = applicationInfo.packageName,
                    appName = packageManager.getApplicationLabel(applicationInfo)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: applicationInfo.packageName,
                    versionName = packageInfo?.versionName,
                    versionCode = packageInfo?.getLongVersionCodeCompat() ?: 0L,
                    isSystemApp = applicationInfo.isSystemApp(),
                    iconDrawable = runCatching {
                        packageManager.getApplicationIcon(applicationInfo)
                    }.getOrNull(),
                )
            }
            .sortedWith(
                compareBy<InstalledApp>({ it.appName.lowercase() }, { it.packageName.lowercase() }),
            )
            .toList()
    }
}

private fun ApplicationInfo.isSystemApp(): Boolean {
    val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
    val isUpdatedSystemApp = (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    return isSystem || isUpdatedSystemApp
}

private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo? {
    return runCatching {
        getPackageInfo(packageName, PackageManager.GET_META_DATA)
    }.getOrNull()
}

private fun PackageInfo.getLongVersionCodeCompat(): Long {
    return longVersionCode
}
