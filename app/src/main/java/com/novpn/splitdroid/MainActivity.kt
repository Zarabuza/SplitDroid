package com.novpn.splitdroid

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
                                if (pendingAutoPause && viewModel.accessibilityEnabled) {
                                    pendingAutoPause = false
                                    ensureVpnPermissionForAutoPause()
                                }
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }

                    MainScreen(
                        isRunning = viewModel.isRunning,
                        statusText = viewModel.statusText,
                        autoPauseEnabled = viewModel.autoPauseEnabled,
                        autoPauseStatus = viewModel.autoPauseStatus,
                        accessibilityEnabled = viewModel.accessibilityEnabled,
                        selectedVpnLabel = viewModel.selectedVpnLabel,
                        selectedVpnPackage = viewModel.selectedVpnPackage,
                        vpnApps = viewModel.vpnApps,
                        notifyFallback = viewModel.notifyFallback,
                        onToggleTunnel = { enabled -> onTunnelChanged(enabled) },
                        onToggleAutoPause = { enabled -> onAutoPauseChanged(enabled) },
                        onToggleNotifyFallback = { viewModel.updateNotifyFallback(it) },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onSelectVpn = { viewModel.selectVpnApp(it) },
                        onRequestVpnPermission = { ensureVpnPermissionForAutoPause() }
                    )
                }
            }
        }
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
            if (!viewModel.accessibilityEnabled) {
                pendingAutoPause = true
                viewModel.updateAutoPauseEnabled(true)
                openAccessibilitySettings()
                return
            }
            viewModel.updateAutoPauseEnabled(true)
            ensureVpnPermissionForAutoPause()
        } else {
            pendingAutoPause = false
            viewModel.updateAutoPauseEnabled(false)
        }
    }

    private fun ensureVpnPermissionForAutoPause() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingStartTunnel = false
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.onVpnPermissionResult(true)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun startVpnService() {
        val intent = Intent(this, SplitTunnelVpnService::class.java)
        startForegroundService(intent)
        viewModel.markRunning(true)
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
    selectedVpnLabel: String,
    selectedVpnPackage: String,
    vpnApps: List<VpnAppInfo>,
    notifyFallback: Boolean,
    onToggleTunnel: (Boolean) -> Unit,
    onToggleAutoPause: (Boolean) -> Unit,
    onToggleNotifyFallback: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onSelectVpn: (VpnAppInfo) -> Unit,
    onRequestVpnPermission: () -> Unit
) {
    val shieldTint = if (autoPauseEnabled || isRunning) Color(0xFF2E7D32) else Color(0xFF9E9E9E)

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
            text = "Автопауза чужого VPN в фоне: банк → сброс, выход → тихое включение",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Автоотключение VPN",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Switch(
                checked = autoPauseEnabled,
                onCheckedChange = onToggleAutoPause
            )
            Text(
                text = autoPauseStatus,
                style = MaterialTheme.typography.bodyLarge,
                color = if (autoPauseStatus.startsWith("Следит")) Color(0xFF2E7D32)
                else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (autoPauseEnabled && !accessibilityEnabled) {
            Button(onClick = onOpenAccessibility) {
                Text("Открыть спец. возможности")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Включите «Раздельный туннель» в Специальных возможностях — иначе фон не работает.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }

        if (autoPauseEnabled && accessibilityEnabled) {
            OutlinedButton(onClick = onRequestVpnPermission) {
                Text("Проверить разрешение VPN")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "VPN для тихого возврата: $selectedVpnLabel",
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
                    text = "VPN‑приложения не найдены. Установите ЮБуст или другой VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
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

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Уведомление, если клик не сработал",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "По умолчанию выкл. — восстановление без уведомлений",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Switch(
                checked = notifyFallback,
                onCheckedChange = onToggleNotifyFallback
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "В фоне: банк → чужой VPN сбрасывается. Выход → открывается ваш VPN и нажимается «Подключить» / Connect, затем возврат к предыдущему приложению.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

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
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(20.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RussianServicesList.services.forEach { service ->
                AssistChip(
                    onClick = {},
                    label = { Text(service.name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SplitDroidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF81C784),
            secondary = Color(0xFFA5D6A7)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2E7D32),
            secondary = Color(0xFF66BB6A)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
