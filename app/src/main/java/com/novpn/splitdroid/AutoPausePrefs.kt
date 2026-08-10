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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
