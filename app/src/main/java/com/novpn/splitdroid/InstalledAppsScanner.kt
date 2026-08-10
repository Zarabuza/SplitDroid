package com.novpn.splitdroid

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class LaunchableApp(
    val packageName: String,
    val label: String
)

object InstalledAppsScanner {
    fun listLaunchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolves = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val self = context.packageName
        return resolves.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == self) return@mapNotNull null
            val label = try {
                info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
            } catch (_: Exception) {
                pkg
            }
            LaunchableApp(pkg, label)
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun labelFor(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
