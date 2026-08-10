package com.novpn.splitdroid

import android.content.Context

object AutoPausePrefs {
    private const val PREFS = "auto_pause_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VPN_PACKAGE = "vpn_package"
    private const val KEY_PAUSED = "paused"
    private const val KEY_RESTORING = "restoring"
    private const val KEY_RETURN_PACKAGE = "return_package"
    private const val KEY_NOTIFY_FALLBACK = "notify_fallback"
    private const val KEY_BYPASS_PACKAGES = "bypass_packages"
    private const val KEY_VPN_NEEDED_PACKAGES = "vpn_needed_packages"
    private const val KEY_LISTS_INITIALIZED = "lists_initialized"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val e = prefs(context).edit().putBoolean(KEY_ENABLED, enabled)
        if (!enabled) {
            e.putBoolean(KEY_PAUSED, false).putBoolean(KEY_RESTORING, false)
        }
        e.apply()
    }

    fun vpnPackage(context: Context): String =
        prefs(context).getString(KEY_VPN_PACKAGE, "") ?: ""

    fun setVpnPackage(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY_VPN_PACKAGE, packageName).apply()
    }

    fun isPaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun isRestoring(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESTORING, false)

    fun setRestoring(context: Context, restoring: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESTORING, restoring).apply()
    }

    fun returnPackage(context: Context): String =
        prefs(context).getString(KEY_RETURN_PACKAGE, "") ?: ""

    fun setReturnPackage(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY_RETURN_PACKAGE, packageName).apply()
    }

    /** Optional notification if silent connect click fails. Default off. */
    fun isNotifyFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_FALLBACK, false)

    fun setNotifyFallback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_FALLBACK, enabled).apply()
    }

    fun ensureListsInitialized(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_LISTS_INITIALIZED, false)) return
        p.edit()
            .putStringSet(KEY_BYPASS_PACKAGES, AppRouteDefaults.bypassPackages)
            .putStringSet(KEY_VPN_NEEDED_PACKAGES, AppRouteDefaults.vpnNeededPackages)
            .putBoolean(KEY_LISTS_INITIALIZED, true)
            .apply()
    }

    fun bypassPackages(context: Context): Set<String> {
        ensureListsInitialized(context)
        return prefs(context).getStringSet(KEY_BYPASS_PACKAGES, null)
            ?: AppRouteDefaults.bypassPackages
    }

    fun setBypassPackages(context: Context, packages: Set<String>) {
        ensureListsInitialized(context)
        prefs(context).edit()
            .putStringSet(KEY_BYPASS_PACKAGES, packages.toSet())
            .apply()
    }

    fun vpnNeededPackages(context: Context): Set<String> {
        ensureListsInitialized(context)
        return prefs(context).getStringSet(KEY_VPN_NEEDED_PACKAGES, null)
            ?: AppRouteDefaults.vpnNeededPackages
    }

    fun setVpnNeededPackages(context: Context, packages: Set<String>) {
        ensureListsInitialized(context)
        prefs(context).edit()
            .putStringSet(KEY_VPN_NEEDED_PACKAGES, packages.toSet())
            .apply()
    }

    fun isBypassPackage(context: Context, packageName: String): Boolean =
        bypassPackages(context).contains(packageName)

    fun isVpnNeededPackage(context: Context, packageName: String): Boolean {
        // Bypass wins if listed in both
        if (isBypassPackage(context, packageName)) return false
        return vpnNeededPackages(context).contains(packageName)
    }

    fun addBypassPackage(context: Context, packageName: String) {
        val next = bypassPackages(context).toMutableSet()
        next.add(packageName)
        // Keep lists mutually exclusive for clarity
        val vpn = vpnNeededPackages(context).toMutableSet()
        vpn.remove(packageName)
        setBypassPackages(context, next)
        setVpnNeededPackages(context, vpn)
    }

    fun removeBypassPackage(context: Context, packageName: String) {
        setBypassPackages(context, bypassPackages(context) - packageName)
    }

    fun addVpnNeededPackage(context: Context, packageName: String) {
        val next = vpnNeededPackages(context).toMutableSet()
        next.add(packageName)
        val bypass = bypassPackages(context).toMutableSet()
        bypass.remove(packageName)
        setVpnNeededPackages(context, next)
        setBypassPackages(context, bypass)
    }

    fun removeVpnNeededPackage(context: Context, packageName: String) {
        setVpnNeededPackages(context, vpnNeededPackages(context) - packageName)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
