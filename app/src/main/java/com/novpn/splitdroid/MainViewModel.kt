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

    var autoPauseEnabled by mutableStateOf(false)
        private set

    var accessibilityEnabled by mutableStateOf(false)
        private set

    var selectedVpnPackage by mutableStateOf("")
        private set

    var selectedVpnLabel by mutableStateOf("Не выбран")
        private set

    var vpnApps by mutableStateOf<List<VpnAppInfo>>(emptyList())
        private set

    var autoPauseStatus by mutableStateOf("")
        private set

    var notifyFallback by mutableStateOf(false)
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

        if (!live && prefs) {
            context.getSharedPreferences(SplitTunnelVpnService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SplitTunnelVpnService.KEY_IS_RUNNING, false)
                .apply()
        }

        isRunning = live
        if (live) isStarting = false

        autoPauseEnabled = AutoPausePrefs.isEnabled(context)
        accessibilityEnabled = VpnAutoPauseAccessibilityService.isAccessibilityEnabled(context)
        notifyFallback = AutoPausePrefs.isNotifyFallback(context)
        vpnApps = VpnAppScanner.listVpnApps(context)

        selectedVpnPackage = AutoPausePrefs.vpnPackage(context)
        if (selectedVpnPackage.isBlank()) {
            val preferred = vpnApps.find {
                it.packageName == VpnAutoPauseAccessibilityService.PREFERRED_VPN_PACKAGE
            } ?: vpnApps.firstOrNull()
            if (preferred != null) {
                AutoPausePrefs.setVpnPackage(context, preferred.packageName)
                selectedVpnPackage = preferred.packageName
            }
        }

        selectedVpnLabel = vpnApps.find { it.packageName == selectedVpnPackage }?.label
            ?: if (selectedVpnPackage.isBlank()) "Не выбран" else selectedVpnPackage

        autoPauseStatus = when {
            !autoPauseEnabled -> "Выключено"
            !accessibilityEnabled -> "Включите спец. возможности"
            !vpnPermissionGranted -> "Нужно разрешение VPN (один раз)"
            selectedVpnPackage.isBlank() -> "Выберите VPN для возврата"
            AutoPausePrefs.isRestoring(context) -> "Тихо включает VPN…"
            AutoPausePrefs.isPaused(context) -> "Пауза · VPN сброшен для банка"
            else -> "Следит в фоне"
        }

        updateStatusText()
    }

    fun updateAutoPauseEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        AutoPausePrefs.setEnabled(context, enabled)
        autoPauseEnabled = enabled
        refreshState()
    }

    fun updateNotifyFallback(enabled: Boolean) {
        val context = getApplication<Application>()
        AutoPausePrefs.setNotifyFallback(context, enabled)
        notifyFallback = enabled
    }

    fun selectVpnApp(info: VpnAppInfo) {
        val context = getApplication<Application>()
        AutoPausePrefs.setVpnPackage(context, info.packageName)
        selectedVpnPackage = info.packageName
        selectedVpnLabel = info.label
        refreshState()
    }

    fun onVpnPermissionResult(granted: Boolean) {
        vpnPermissionGranted = granted
        if (!granted) {
            isStarting = false
            isRunning = false
        }
        updateStatusText()
        refreshState()
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
