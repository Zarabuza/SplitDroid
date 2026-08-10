package com.novpn.splitdroid

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.net.VpnService
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
    var accessibilityCrashed by mutableStateOf(false)
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
    var bypassApps by mutableStateOf<List<LaunchableApp>>(emptyList())
        private set
    var vpnNeededApps by mutableStateOf<List<LaunchableApp>>(emptyList())
        private set
    var allLaunchableApps by mutableStateOf<List<LaunchableApp>>(emptyList())
        private set
    var showSetupWizard by mutableStateOf(true)
        private set
    var restrictedSettingsDone by mutableStateOf(false)
        private set
    var batteryMarkedDone by mutableStateOf(false)
        private set
    var autostartMarkedDone by mutableStateOf(false)
        private set
    var notificationsGranted by mutableStateOf(true)
        private set
    var batteryOptIgnored by mutableStateOf(false)
        private set
    var setupStep by mutableIntStateOf(1)
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
            prefs.let {
                context.getSharedPreferences(SplitTunnelVpnService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(SplitTunnelVpnService.KEY_IS_RUNNING, false).apply()
            }
        }
        isRunning = live
        if (live) isStarting = false

        autoPauseEnabled = AutoPausePrefs.isEnabled(context)
        accessibilityEnabled = VpnAutoPauseAccessibilityService.isAccessibilityEnabled(context)
        val accessibilityRunning = VpnAutoPauseAccessibilityService.isAccessibilityRunning(context)
        accessibilityCrashed = accessibilityEnabled && !accessibilityRunning
        notifyFallback = AutoPausePrefs.isNotifyFallback(context)
        vpnApps = VpnAppScanner.listVpnApps(context)
        restrictedSettingsDone = SetupPrefs.isRestrictedDone(context)
        // Ensure old installs re-see step 2 (restricted settings).
        SetupPrefs.migrateIfNeeded(context)
        restrictedSettingsDone = SetupPrefs.isRestrictedDone(context)
        batteryOptIgnored = PermissionIntents.isIgnoringBatteryOptimizations(context)
        batteryMarkedDone = SetupPrefs.isBatteryDone(context) || batteryOptIgnored
        autostartMarkedDone = SetupPrefs.isAutostartDone(context)
        notificationsGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED || SetupPrefs.isNotificationsDone(context)
        } else true

        AutoPausePrefs.ensureListsInitialized(context)
        if (allLaunchableApps.isEmpty()) {
            allLaunchableApps = InstalledAppsScanner.listLaunchableApps(context)
        }
        val installedByPkg = allLaunchableApps.associateBy { it.packageName }
        bypassApps = mapPkgs(AutoPausePrefs.bypassPackages(context), installedByPkg, context)
        vpnNeededApps = mapPkgs(AutoPausePrefs.vpnNeededPackages(context), installedByPkg, context)

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
            accessibilityCrashed -> "Убито HyperOS — переключите спец. возможности"
            !accessibilityEnabled -> "Включите спец. возможности"
            !vpnPermissionGranted -> "Нужно разрешение VPN"
            selectedVpnPackage.isBlank() -> "Выберите VPN"
            AutoPausePrefs.isRestoring(context) -> "Тихо включает VPN…"
            AutoPausePrefs.isPaused(context) -> "Пауза · VPN сброшен"
            else -> "Следит в фоне"
        }

        if (autoPauseEnabled && accessibilityRunning) AutoPauseKeepAliveService.start(context)
        else if (!autoPauseEnabled) AutoPauseKeepAliveService.stop(context)

        // Do NOT auto-skip "restricted settings": HyperOS can list a11y as enabled
        // while the toggle is still gray until restricted settings are allowed.
        if (batteryOptIgnored) {
            SetupPrefs.setBatteryDone(context, true)
            batteryMarkedDone = true
        }

        setupStep = when {
            !notificationsGranted -> 1
            !restrictedSettingsDone -> 2
            !accessibilityEnabled || accessibilityCrashed -> 3
            !vpnPermissionGranted || selectedVpnPackage.isBlank() -> 4
            !batteryMarkedDone -> 5
            !autostartMarkedDone -> 6
            else -> 7
        }

        val setupReady = notificationsGranted && restrictedSettingsDone &&
            accessibilityEnabled && !accessibilityCrashed &&
            vpnPermissionGranted && selectedVpnPackage.isNotBlank() &&
            batteryMarkedDone && autostartMarkedDone && SetupPrefs.isWizardDone(context)
        showSetupWizard = !setupReady
        updateStatusText()
    }

    private fun mapPkgs(
        pkgs: Set<String>,
        installed: Map<String, LaunchableApp>,
        context: Context
    ): List<LaunchableApp> {
        val list = pkgs.map { pkg ->
            installed[pkg] ?: LaunchableApp(pkg, InstalledAppsScanner.labelFor(context, pkg))
        }
        val a = list.filter { installed.containsKey(it.packageName) }
        val b = list.filterNot { installed.containsKey(it.packageName) }
        return (a + b).sortedBy { it.label.lowercase() }
    }

    fun markNotificationsDone() {
        SetupPrefs.setNotificationsDone(getApplication(), true)
        refreshState()
    }

    fun markRestrictedDone() {
        SetupPrefs.setRestrictedDone(getApplication(), true)
        refreshState()
    }

    fun markBatteryDone() {
        SetupPrefs.setBatteryDone(getApplication(), true)
        refreshState()
    }

    fun markAutostartDone() {
        SetupPrefs.setAutostartDone(getApplication(), true)
        refreshState()
    }

    fun completeWizard() {
        val context = getApplication<Application>()
        SetupPrefs.setWizardDone(context, true)
        SetupPrefs.setRestrictedDone(context, true)
        SetupPrefs.setBatteryDone(context, true)
        SetupPrefs.setAutostartDone(context, true)
        SetupPrefs.setNotificationsDone(context, true)
        AutoPausePrefs.setEnabled(context, true)
        AutoPauseKeepAliveService.start(context)
        showSetupWizard = false
        refreshState()
    }

    fun reopenWizard() {
        val context = getApplication<Application>()
        // Force user through restricted settings again — common HyperOS failure.
        SetupPrefs.setWizardDone(context, false)
        SetupPrefs.setRestrictedDone(context, false)
        restrictedSettingsDone = false
        showSetupWizard = true
        refreshState()
    }

    /** User must explicitly confirm restricted settings; never auto-skip. */
    fun resetRestrictedAndStayOnStep2() {
        val context = getApplication<Application>()
        SetupPrefs.setRestrictedDone(context, false)
        restrictedSettingsDone = false
        refreshState()
    }

    fun updateAutoPauseEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        AutoPausePrefs.setEnabled(context, enabled)
        if (enabled) AutoPauseKeepAliveService.start(context) else AutoPauseKeepAliveService.stop(context)
        refreshState()
    }

    fun updateNotifyFallback(enabled: Boolean) {
        AutoPausePrefs.setNotifyFallback(getApplication(), enabled)
        notifyFallback = enabled
    }

    fun selectVpnApp(info: VpnAppInfo) {
        AutoPausePrefs.setVpnPackage(getApplication(), info.packageName)
        selectedVpnPackage = info.packageName
        selectedVpnLabel = info.label
        refreshState()
    }

    fun addBypassApp(packageName: String) {
        AutoPausePrefs.addBypassPackage(getApplication(), packageName)
        refreshState()
    }

    fun removeBypassApp(packageName: String) {
        AutoPausePrefs.removeBypassPackage(getApplication(), packageName)
        refreshState()
    }

    fun addVpnNeededApp(packageName: String) {
        AutoPausePrefs.addVpnNeededPackage(getApplication(), packageName)
        refreshState()
    }

    fun removeVpnNeededApp(packageName: String) {
        AutoPausePrefs.removeVpnNeededPackage(getApplication(), packageName)
        refreshState()
    }

    fun reloadInstalledApps() {
        allLaunchableApps = InstalledAppsScanner.listLaunchableApps(getApplication())
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
            isRunning -> "Работает · DNS‑туннель"
            else -> "Остановлено"
        }
    }
}
