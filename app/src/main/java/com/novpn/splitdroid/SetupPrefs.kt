package com.novpn.splitdroid

import android.content.Context

object SetupPrefs {
    private const val PREFS = "setup_prefs"
    private const val KEY_RESTRICTED_DONE = "restricted_settings_done"
    private const val KEY_WIZARD_DONE = "wizard_done"
    private const val KEY_WIZARD_VERSION = "wizard_version"
    private const val KEY_BATTERY_DONE = "battery_done"
    private const val KEY_AUTOSTART_DONE = "autostart_done"
    private const val KEY_NOTIFICATIONS_DONE = "notifications_done"

    /** Bump to force everyone through the new permission wizard once. */
    const val CURRENT_WIZARD_VERSION = 3

    fun isRestrictedDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESTRICTED_DONE, false)

    fun setRestrictedDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESTRICTED_DONE, done).apply()
    }

    fun isWizardDone(context: Context): Boolean {
        migrateIfNeeded(context)
        return prefs(context).getBoolean(KEY_WIZARD_DONE, false) &&
            prefs(context).getInt(KEY_WIZARD_VERSION, 0) >= CURRENT_WIZARD_VERSION
    }

    fun setWizardDone(context: Context, done: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_WIZARD_DONE, done)
            .putInt(KEY_WIZARD_VERSION, if (done) CURRENT_WIZARD_VERSION else 0)
            .apply()
    }

    /** When wizard format changes, force restricted-settings step again. */
    fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        val v = p.getInt(KEY_WIZARD_VERSION, 0)
        if (v < CURRENT_WIZARD_VERSION) {
            p.edit()
                .putBoolean(KEY_WIZARD_DONE, false)
                .putBoolean(KEY_RESTRICTED_DONE, false)
                .putInt(KEY_WIZARD_VERSION, CURRENT_WIZARD_VERSION)
                .apply()
        }
    }

    fun isBatteryDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_DONE, false)

    fun setBatteryDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_DONE, done).apply()
    }

    fun isAutostartDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART_DONE, false)

    fun setAutostartDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART_DONE, done).apply()
    }

    fun isNotificationsDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATIONS_DONE, false)

    fun setNotificationsDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_DONE, done).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
