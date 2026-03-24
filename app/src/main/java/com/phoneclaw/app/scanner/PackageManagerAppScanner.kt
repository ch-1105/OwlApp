package com.phoneclaw.app.scanner

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

class PackageManagerAppScanner(
    private val context: Context,
    private val includeSystemApps: Boolean = true,
) : AppScanner {
    private val packageManager: PackageManager
        get() = context.packageManager

    override fun scanInstalledApps(): List<InstalledApp> {
        return loadVisibleApplications()
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

    private fun loadVisibleApplications(): List<ApplicationInfo> {
        val applicationsByPackage = linkedMapOf<String, ApplicationInfo>()

        packageManager.getInstalledApplications(PackageManager.GET_META_DATA).forEach { applicationInfo ->
            applicationsByPackage[applicationInfo.packageName] = applicationInfo
        }

        loadLaunchableApplications().forEach { applicationInfo ->
            applicationsByPackage.putIfAbsent(applicationInfo.packageName, applicationInfo)
        }

        return applicationsByPackage.values.toList()
    }

    private fun loadLaunchableApplications(): List<ApplicationInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return packageManager.queryLauncherActivitiesCompat(launcherIntent)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                activityInfo.applicationInfo ?: packageManager.getApplicationInfoCompat(activityInfo.packageName)
            }
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

private fun PackageManager.getApplicationInfoCompat(packageName: String): ApplicationInfo? {
    return runCatching {
        getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }.getOrNull()
}

@Suppress("DEPRECATION")
private fun PackageManager.queryLauncherActivitiesCompat(intent: Intent) =
    queryIntentActivities(intent, PackageManager.MATCH_ALL)

private fun PackageInfo.getLongVersionCodeCompat(): Long {
    return longVersionCode
}
