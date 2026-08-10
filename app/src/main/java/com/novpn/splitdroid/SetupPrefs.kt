package com.novpn.splitdroid

import android.content.Context

object SetupPrefs {
    private const val PREFS = "setup_prefs"
    private const val KEY_RESTRICTED_DONE = "restricted_settings_done"
    private const val KEY_WIZARD_DONE = "wizard_done"

    fun isRestrictedDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTRICTED_DONE, false)

    fun setRestrictedDone(context: Context, done: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RESTRICTED_DONE, done)
            .apply()
    }

    fun isWizardDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIZARD_DONE, false)

    fun setWizardDone(context: Context, done: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIZARD_DONE, done)
            .apply()
    }
}
