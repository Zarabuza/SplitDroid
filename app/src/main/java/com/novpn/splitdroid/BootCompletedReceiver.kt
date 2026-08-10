package com.novpn.splitdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AutoPausePrefs.isEnabled(context)) return
        AutoPauseKeepAliveService.start(context)
    }
}
