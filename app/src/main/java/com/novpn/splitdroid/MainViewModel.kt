package com.novpn.splitdroid

import android.app.Application
import android.content.Context
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class MainViewModel(application: Application) : AndroidViewModel(application) {

    var isRunning by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("Остановлено")
        private set

    var vpnPermissionGranted by mutableStateOf(false)
        private set

    private var isStarting = false

    init {
        refreshState()
    }

    fun refreshState() {
        val context = getApplication<Application>()
        vpnPermissionGranted = VpnService.prepare(context) == null

        val live = SplitTunnelVpnService.isRunning.get()
        val prefs = context
            .getSharedPreferences(SplitTunnelVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SplitTunnelVpnService.KEY_IS_RUNNING, false)

        // After process death static flag resets; clear stale prefs
        if (!live && prefs) {
            context.getSharedPreferences(SplitTunnelVpnService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SplitTunnelVpnService.KEY_IS_RUNNING, false)
                .apply()
        }

        isRunning = live
        if (live) isStarting = false
        updateStatusText()
    }

    fun onVpnPermissionResult(granted: Boolean) {
        vpnPermissionGranted = granted
        if (!granted) {
            isStarting = false
            isRunning = false
        }
        updateStatusText()
    }

    fun setStarting() {
        isStarting = true
        updateStatusText()
    }

    fun markRunning(running: Boolean) {
        isRunning = running
        isStarting = false
        updateStatusText()
    }

    private fun updateStatusText() {
        statusText = when {
            isStarting -> "Запускается..."
            !vpnPermissionGranted && !isRunning -> "Не настроено"
            isRunning -> "Работает · банки и Госуслуги в обход VPN"
            else -> "Остановлено"
        }
    }
}
