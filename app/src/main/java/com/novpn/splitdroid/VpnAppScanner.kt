package com.novpn.splitdroid

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService

data class VpnAppInfo(
    val packageName: String,
    val label: String
)

object VpnAppScanner {
    fun listVpnApps(context: Context): List<VpnAppInfo> = listInstalledVpnApps(context)

    fun listInstalledVpnApps(context: Context): List<VpnAppInfo> {
        val pm = context.packageManager
        val intent = Intent(VpnService.SERVICE_INTERFACE)
        @Suppress("DEPRECATION")
        val resolves = pm.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolves.mapNotNull { info ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            val pkg = serviceInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                info.loadLabel(pm)?.toString() ?: pkg
            }
            VpnAppInfo(pkg, label)
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
