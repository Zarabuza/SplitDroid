package com.novpn.splitdroid

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
        if (granted) {
            startVpnService()
        } else {
            viewModel.setRunning(false)
        }
    }

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
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }

                    MainScreen(
                        isRunning = viewModel.isRunning,
                        statusText = viewModel.statusText,
                        onToggle = { enabled -> onSwitchChanged(enabled) }
                    )
                }
            }
        }
    }

    private fun onSwitchChanged(enabled: Boolean) {
        if (enabled) {
            viewModel.setStarting()
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.onVpnPermissionResult(true)
                startVpnService()
            }
        } else {
            stopService(Intent(this, SplitTunnelVpnService::class.java))
            viewModel.setRunning(false)
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, SplitTunnelVpnService::class.java)
        startForegroundService(intent)
        viewModel.setRunning(true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    isRunning: Boolean,
    statusText: String,
    onToggle: (Boolean) -> Unit
) {
    val shieldTint = if (isRunning) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
    val statusColor = when {
        statusText.startsWith("Работает") -> Color(0xFF2E7D32)
        statusText == "Остановлено" -> Color(0xFF9E9E9E)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val switchOn = isRunning || statusText == "Запускается..."

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Icon(
            painter = painterResource(id = R.drawable.ic_shield),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = shieldTint
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Раздельный туннель",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Российские сервисы работают без отключения VPN",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        Switch(
            checked = switchOn,
            onCheckedChange = onToggle
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = statusColor,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

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

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Трафик на российские сервисы идёт напрямую. Остальной трафик — через ваш VPN.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

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
