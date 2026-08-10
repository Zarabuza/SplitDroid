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
    private val knownVpnPackages = listOf(
        "st.uboo.android.client" to "ЮБуст",
        "org.outline.android.client" to "Outline",
        "com.v2ray.ang" to "v2rayNG",
        "com.v2raytun.android" to "V2RayTun",
        "com.happ.proxy" to "Happ",
        "org.amnezia.vpn" to "AmneziaVPN",
        "com.wireguard.android" to "WireGuard",
        "de.blinkt.openvpn" to "OpenVPN",
        "com.protonvpn.android" to "Proton VPN",
        "com.nordvpn.android" to "NordVPN"
    )

    fun listVpnApps(context: Context): List<VpnAppInfo> = listInstalledVpnApps(context)

    fun listInstalledVpnApps(context: Context): List<VpnAppInfo> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, VpnAppInfo>()

        val intent = Intent(VpnService.SERVICE_INTERFACE)
        @Suppress("DEPRECATION")
        val resolves = try {
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }

        resolves.forEach { info ->
            val serviceInfo = info.serviceInfo ?: return@forEach
            val pkg = serviceInfo.packageName
            if (pkg == context.packageName) return@forEach
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                info.loadLabel(pm)?.toString() ?: pkg
            }
            found[pkg] = VpnAppInfo(pkg, label)
        }

        knownVpnPackages.forEach { (pkg, fallbackLabel) ->
            if (found.containsKey(pkg)) return@forEach
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString().ifBlank { fallbackLabel }
                found[pkg] = VpnAppInfo(pkg, label)
            } catch (_: Exception) {
            }
        }

        return found.values.sortedBy { it.label.lowercase() }
    }
}
