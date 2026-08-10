package com.novpn.splitdroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        viewModel.onVpnPermissionResult(granted)
        if (granted && pendingStartTunnel) {
            pendingStartTunnel = false
            startVpnService()
        }
        if (granted) {
            viewModel.refreshState()
            maybeFinishWizard()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.markNotificationsDone()
        else viewModel.markNotificationsDone() // allow continue; FGS may still work
        viewModel.refreshState()
    }

    private var pendingStartTunnel = false
    private var pendingAutoPause = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.refreshState()

        setContent {
            SplitDroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DisposableEffect(Unit) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                viewModel.refreshState()
                                maybeFinishWizard()
                                if (pendingAutoPause && viewModel.accessibilityEnabled) {
                                    pendingAutoPause = false
                                    ensureVpnPermissionForAutoPause()
                                }
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }

                    if (viewModel.showSetupWizard) {
                        SetupWizardScreen(
                            step = viewModel.setupStep,
                            notificationsGranted = viewModel.notificationsGranted,
                            restrictedDone = viewModel.restrictedSettingsDone,
                            accessibilityEnabled = viewModel.accessibilityEnabled,
                            accessibilityCrashed = viewModel.accessibilityCrashed,
                            vpnPermissionGranted = viewModel.vpnPermissionGranted,
                            batteryDone = viewModel.batteryMarkedDone,
                            batteryIgnored = viewModel.batteryOptIgnored,
                            autostartDone = viewModel.autostartMarkedDone,
                            vpnApps = viewModel.vpnApps,
                            selectedVpnPackage = viewModel.selectedVpnPackage,
                            onRequestNotifications = { requestNotificationPermission() },
                            onOpenAppInfo = { safeStart(PermissionIntents.appInfo(this)) },
                            onMarkRestrictedDone = { viewModel.markRestrictedDone() },
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onBackToRestricted = {
                                viewModel.resetRestrictedAndStayOnStep2()
                            },
                            onRequestVpn = { ensureVpnPermissionForAutoPause() },
                            onSelectVpn = { viewModel.selectVpnApp(it) },
                            onOpenBattery = { openBatteryOptimizationSettings() },
                            onOpenMiuiBattery = { openMiuiBattery() },
                            onMarkBatteryDone = { viewModel.markBatteryDone() },
                            onOpenAutostart = { openAutostart() },
                            onMarkAutostartDone = { viewModel.markAutostartDone() },
                            onFinish = {
                                viewModel.completeWizard()
                                pendingAutoPause = true
                                ensureVpnPermissionForAutoPause()
                            }
                        )
                    } else {
                        MainScreen(
                            isRunning = viewModel.isRunning,
                            statusText = viewModel.statusText,
                            autoPauseEnabled = viewModel.autoPauseEnabled,
                            autoPauseStatus = viewModel.autoPauseStatus,
                            accessibilityEnabled = viewModel.accessibilityEnabled,
                            accessibilityCrashed = viewModel.accessibilityCrashed,
                            selectedVpnLabel = viewModel.selectedVpnLabel,
                            selectedVpnPackage = viewModel.selectedVpnPackage,
                            vpnApps = viewModel.vpnApps,
                            bypassApps = viewModel.bypassApps,
                            vpnNeededApps = viewModel.vpnNeededApps,
                            allLaunchableApps = viewModel.allLaunchableApps,
                            notifyFallback = viewModel.notifyFallback,
                            onToggleTunnel = { enabled -> onTunnelChanged(enabled) },
                            onToggleAutoPause = { enabled -> onAutoPauseChanged(enabled) },
                            onToggleNotifyFallback = { viewModel.updateNotifyFallback(it) },
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onOpenBattery = { openBatteryOptimizationSettings() },
                            onOpenSetup = { viewModel.reopenWizard() },
                            onSelectVpn = { viewModel.selectVpnApp(it) },
                            onRequestVpnPermission = { ensureVpnPermissionForAutoPause() },
                            onAddBypass = { viewModel.addBypassApp(it) },
                            onRemoveBypass = { viewModel.removeBypassApp(it) },
                            onAddVpnNeeded = { viewModel.addVpnNeededApp(it) },
                            onRemoveVpnNeeded = { viewModel.removeVpnNeededApp(it) },
                            onReloadApps = { viewModel.reloadInstalledApps() }
                        )
                    }
                }
            }
        }
    }

    private fun maybeFinishWizard() {
        // Wizard finishes only via explicit Finish button after all steps.
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.markNotificationsDone()
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun openMiuiBattery() {
        val intent = PermissionIntents.miuiBatterySaver(this)
        if (intent != null) safeStart(intent) else openBatteryOptimizationSettings()
    }

    private fun openAutostart() {
        val intent = PermissionIntents.miuiAutostart(this)
        if (intent != null) safeStart(intent) else safeStart(PermissionIntents.appInfo(this))
    }

    private fun onTunnelChanged(enabled: Boolean) {
        if (enabled) {
            viewModel.setStarting()
            pendingStartTunnel = true
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.onVpnPermissionResult(true)
                pendingStartTunnel = false
                startVpnService()
            }
        } else {
            stopService(Intent(this, SplitTunnelVpnService::class.java))
            viewModel.markRunning(false)
        }
    }

    private fun onAutoPauseChanged(enabled: Boolean) {
        if (enabled) {
            if (!viewModel.accessibilityEnabled || !viewModel.restrictedSettingsDone) {
                pendingAutoPause = true
                viewModel.reopenWizard()
                return
            }
            viewModel.updateAutoPauseEnabled(true)
            ensureVpnPermissionForAutoPause()
        } else {
            pendingAutoPause = false
            viewModel.updateAutoPauseEnabled(false)
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(PermissionIntents.ignoreBatteryOptimizations(this))
        } catch (_: Exception) {
            try {
                startActivity(PermissionIntents.batteryOptimizationList())
            } catch (_: Exception) {
                safeStart(PermissionIntents.appInfo(this))
            }
        }
    }

    private fun openAppInfoForRestrictedSettings() {
        safeStart(PermissionIntents.appInfo(this))
    }

    private fun ensureVpnPermissionForAutoPause() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingStartTunnel = false
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.onVpnPermissionResult(true)
            maybeFinishWizard()
        }
    }

    private fun openAccessibilitySettings() {
        val component = android.content.ComponentName(
            this,
            VpnAutoPauseAccessibilityService::class.java
        )
        try {
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(details)
            return
        } catch (_: Exception) {
        }
        try {
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra("android.intent.extra.COMPONENT_NAME", component.flattenToString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(details)
            return
        } catch (_: Exception) {
        }

        val showArgs = component.flattenToString()
        val fragmentArgs = Bundle().apply {
            putString(":settings:fragment_args_key", showArgs)
        }
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", showArgs)
                putExtra(":settings:show_fragment_args", fragmentArgs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun startVpnService() {
        val intent = Intent(this, SplitTunnelVpnService::class.java)
        startForegroundService(intent)
        viewModel.markRunning(true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupWizardScreen(
    step: Int,
    notificationsGranted: Boolean,
    restrictedDone: Boolean,
    accessibilityEnabled: Boolean,
    accessibilityCrashed: Boolean,
    vpnPermissionGranted: Boolean,
    batteryDone: Boolean,
    batteryIgnored: Boolean,
    autostartDone: Boolean,
    vpnApps: List<VpnAppInfo>,
    selectedVpnPackage: String,
    onRequestNotifications: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onMarkRestrictedDone: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onBackToRestricted: () -> Unit,
    onRequestVpn: () -> Unit,
    onSelectVpn: (VpnAppInfo) -> Unit,
    onOpenBattery: () -> Unit,
    onOpenMiuiBattery: () -> Unit,
    onMarkBatteryDone: () -> Unit,
    onOpenAutostart: () -> Unit,
    onMarkAutostartDone: () -> Unit,
    onFinish: () -> Unit
) {
    val current = when {
        !notificationsGranted -> 1
        !restrictedDone -> 2
        !accessibilityEnabled || accessibilityCrashed -> 3
        !vpnPermissionGranted || selectedVpnPackage.isBlank() -> 4
        !batteryDone -> 5
        !autostartDone -> 6
        else -> 7
    }.coerceAtLeast(step).coerceAtMost(7)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Настройка разрешений", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Без этих пунктов HyperOS убивает слежение. Каждый шаг открывает нужный экран.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        StepDots(current = current.coerceAtMost(6), total = 6)
        Spacer(modifier = Modifier.height(20.dp))

        when {
            !notificationsGranted -> WizardCard(
                step = 1, title = "Уведомления",
                body = "Нужны для постоянного уведомления «активен». Без него Xiaomi чистит процесс.",
                primary = "Разрешить уведомления", onPrimary = onRequestNotifications,
                secondary = "Пропустить", onSecondary = onRequestNotifications
            )
            !restrictedDone -> WizardCard(
                step = 2, title = "Ограниченные настройки",
                body = "Сведения о приложении → ⋮ сверху справа → «Разрешить настройки с ограниченным доступом».",
                primary = "Открыть сведения о приложении", onPrimary = onOpenAppInfo,
                secondary = "Готово — разрешил", onSecondary = onMarkRestrictedDone
            )
            !accessibilityEnabled || accessibilityCrashed -> WizardCard(
                step = 3,
                title = if (accessibilityCrashed) "Спец. возможности убиты — включи снова" else "Спец. возможности",
                body = if (accessibilityCrashed)
                    "Выключи и снова включи «Раздельный туннель». Иначе kick/restore не работают."
                else
                    "Включи службу «Раздельный туннель». Xiaomi: Скачанные приложения → Раздельный туннель.",
                primary = "Открыть спец. возможности", onPrimary = onOpenAccessibility,
                secondary = null, onSecondary = null,
                hint = "После включения вернись — шаг отметится сам."
            )
            !vpnPermissionGranted || selectedVpnPackage.isBlank() -> {
                WizardCard(
                    step = 4, title = "VPN + какое VPN возвращать",
                    body = "Разреши VPN один раз (для сброса чужого). Выбери ЮБуст для обратного включения.",
                    primary = if (!vpnPermissionGranted) "Разрешить VPN" else null,
                    onPrimary = if (!vpnPermissionGranted) onRequestVpn else null,
                    secondary = null, onSecondary = null
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vpnApps.forEach { app ->
                        FilterChip(
                            selected = app.packageName == selectedVpnPackage,
                            onClick = { onSelectVpn(app) },
                            label = { Text(app.label) }
                        )
                    }
                    if (vpnApps.isEmpty()) Text("Установи ЮБуст и вернись", color = Color.Gray)
                }
            }
            !batteryDone -> WizardCard(
                step = 5, title = "Батарея без ограничений",
                body = "Иначе HyperOS OneKeyClean снова убьёт слежение.",
                primary = "Разрешить без ограничений", onPrimary = onOpenBattery,
                secondary = "Готово", onSecondary = onMarkBatteryDone,
                hint = if (batteryIgnored) "Система уже разрешила — жми Готово." else "Можно также: Экран MIUI батареи ниже."
            )
            !autostartDone -> WizardCard(
                step = 6, title = "Автозапуск Xiaomi",
                body = "В автозапуске включи «Раздельный туннель». Иначе после очистки снова умрёт.",
                primary = "Открыть автозапуск", onPrimary = onOpenAutostart,
                secondary = "Готово — включил", onSecondary = onMarkAutostartDone
            )
            else -> {
                WizardCard(
                    step = 6, title = "Всё готово",
                    body = "Автопауза: банк → VPN off, Telegram/YouTube → VPN on. Не смахивай уведомление «активен».",
                    primary = "Включить автопаузу", onPrimary = onFinish,
                    secondary = null, onSecondary = null
                )
            }
        }
        if (!batteryDone && notificationsGranted && restrictedDone &&
            accessibilityEnabled && !accessibilityCrashed &&
            vpnPermissionGranted && selectedVpnPackage.isNotBlank()
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenMiuiBattery, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть экран батареи MIUI")
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val n = i + 1
            val active = n == current
            val done = n < current
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        when {
                            done -> Color(0xFF2E7D32)
                            active -> MaterialTheme.colorScheme.primary
                            else -> Color(0xFFBDBDBD)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (done) "✓" else "$n",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun WizardCard(
    step: Int,
    title: String,
    body: String,
    primary: String?,
    onPrimary: (() -> Unit)?,
    secondary: String?,
    onSecondary: (() -> Unit)?,
    hint: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Шаг $step из 3",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hint != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (primary != null && onPrimary != null) {
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                Text(primary)
            }
        }
        if (secondary != null && onSecondary != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                Text(secondary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    isRunning: Boolean,
    statusText: String,
    autoPauseEnabled: Boolean,
    autoPauseStatus: String,
    accessibilityEnabled: Boolean,
    accessibilityCrashed: Boolean,
    selectedVpnLabel: String,
    selectedVpnPackage: String,
    vpnApps: List<VpnAppInfo>,
    bypassApps: List<LaunchableApp>,
    vpnNeededApps: List<LaunchableApp>,
    allLaunchableApps: List<LaunchableApp>,
    notifyFallback: Boolean,
    onToggleTunnel: (Boolean) -> Unit,
    onToggleAutoPause: (Boolean) -> Unit,
    onToggleNotifyFallback: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenSetup: () -> Unit,
    onSelectVpn: (VpnAppInfo) -> Unit,
    onRequestVpnPermission: () -> Unit,
    onAddBypass: (String) -> Unit,
    onRemoveBypass: (String) -> Unit,
    onAddVpnNeeded: (String) -> Unit,
    onRemoveVpnNeeded: (String) -> Unit,
    onReloadApps: () -> Unit
) {
    val shieldTint = if (autoPauseEnabled || isRunning) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
    var addTarget by remember { mutableStateOf<AppListTarget?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Icon(
            painter = painterResource(id = R.drawable.ic_shield),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = shieldTint
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Раздельный туннель",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Банк/Госуслуги → VPN сброс. Telegram/YouTube → VPN снова. Home ничего не включает.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(text = "Автоотключение VPN", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Switch(checked = autoPauseEnabled, onCheckedChange = onToggleAutoPause)
            Text(
                text = autoPauseStatus,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    autoPauseStatus.startsWith("Следит") -> Color(0xFF2E7D32)
                    accessibilityCrashed -> Color(0xFFC62828)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }

        if (accessibilityCrashed) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "HyperOS убила слежение (очистка памяти). Выключи и снова включи спец. возможности.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFC62828),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text("Переключить спец. возможности")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenBattery, modifier = Modifier.fillMaxWidth()) {
                Text("Батарея без ограничений")
            }
        } else if (!accessibilityEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Пройти настройку заново")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть спец. возможности")
            }
        }

        if (autoPauseEnabled && accessibilityEnabled && !accessibilityCrashed) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onRequestVpnPermission) {
                Text("Проверить разрешение VPN")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenBattery, modifier = Modifier.fillMaxWidth()) {
                Text("Батарея без ограничений (важно)")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "VPN‑клиент для включения: $selectedVpnLabel",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (vpnApps.isEmpty()) {
                Text(
                    text = "VPN‑приложения не найдены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                vpnApps.forEach { app ->
                    FilterChip(
                        selected = app.packageName == selectedVpnPackage,
                        onClick = { onSelectVpn(app) },
                        label = { Text(app.label) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        AppRouteListBlock(
            title = "Без VPN",
            subtitle = "При входе VPN сбрасывается. Нажми чип, чтобы убрать.",
            apps = bypassApps,
            onRemove = onRemoveBypass,
            onAddClick = {
                onReloadApps()
                addTarget = AppListTarget.Bypass
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        AppRouteListBlock(
            title = "Нужен VPN",
            subtitle = "При входе (TG, YouTube…) VPN тихо включается. Не с Home.",
            apps = vpnNeededApps,
            onRemove = onRemoveVpnNeeded,
            onAddClick = {
                onReloadApps()
                addTarget = AppListTarget.VpnNeeded
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = "Уведомление, если клик не сработал", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "По умолчанию выкл.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Switch(checked = notifyFallback, onCheckedChange = onToggleNotifyFallback)
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onOpenSetup) {
            Text("Мастер настройки разрешений")
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Старый DNS‑туннель (опционально)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Switch(
            checked = isRunning || statusText == "Запускается...",
            onCheckedChange = onToggleTunnel
        )
        Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))
    }

    val target = addTarget
    if (target != null) {
        val selected = when (target) {
            AppListTarget.Bypass -> bypassApps.map { it.packageName }.toSet()
            AppListTarget.VpnNeeded -> vpnNeededApps.map { it.packageName }.toSet()
        }
        AddAppDialog(
            title = when (target) {
                AppListTarget.Bypass -> "Добавить в «Без VPN»"
                AppListTarget.VpnNeeded -> "Добавить в «Нужен VPN»"
            },
            apps = allLaunchableApps,
            alreadySelected = selected,
            onDismiss = { addTarget = null },
            onPick = { pkg ->
                when (target) {
                    AppListTarget.Bypass -> onAddBypass(pkg)
                    AppListTarget.VpnNeeded -> onAddVpnNeeded(pkg)
                }
                addTarget = null
            }
        )
    }
}

private enum class AppListTarget { Bypass, VpnNeeded }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRouteListBlock(
    title: String,
    subtitle: String,
    apps: List<LaunchableApp>,
    onRemove: (String) -> Unit,
    onAddClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            apps.take(40).forEach { app ->
                AssistChip(
                    onClick = { onRemove(app.packageName) },
                    label = { Text(app.label) }
                )
            }
            if (apps.size > 40) {
                Text(
                    text = "+ ещё ${apps.size - 40}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onAddClick, modifier = Modifier.fillMaxWidth()) {
            Text("Добавить приложение")
        }
    }
}

@Composable
private fun AddAppDialog(
    title: String,
    apps: List<LaunchableApp>,
    alreadySelected: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query, alreadySelected) {
        apps.asSequence()
            .filter { it.packageName !in alreadySelected }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .take(80)
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск") }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    filtered.forEach { app ->
                        TextButton(
                            onClick = { onPick(app.packageName) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = app.label,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text("Ничего не найдено", color = Color.Gray)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
fun SplitDroidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFFA5D6A7))
    } else {
        lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF66BB6A))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
