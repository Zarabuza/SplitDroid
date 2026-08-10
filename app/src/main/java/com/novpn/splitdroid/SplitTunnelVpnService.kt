package com.novpn.splitdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class SplitTunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning.get()) {
            return START_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val established = Builder()
            .setSession(SESSION_NAME)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setBlocking(true)
            .establish()

        if (established == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return START_NOT_STICKY
        }

        vpnInterface = established
        isRunning.set(true)
        saveRunningFlag(true)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope = scope
        scope.launch { packetLoop(established) }

        return START_STICKY
    }

    private suspend fun packetLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)

        try {
            while (serviceScope?.isActive == true) {
                val length = input.read(buffer)
                if (length <= 0) continue

                val packet = buffer.copyOf(length)

                if (DnsParser.isDnsResponse(packet)) {
                    output.write(packet)
                    continue
                }

                val queryName = DnsParser.parseDnsQueryName(packet)
                if (queryName == null) {
                    // Not a DNS query we handle specially — pass through unchanged
                    output.write(packet)
                    continue
                }

                if (!RussianServicesList.matchesRussianDomain(queryName)) {
                    output.write(packet)
                    continue
                }

                // Russian domain: resolve locally and inject DNS response
                val response = withContext(Dispatchers.IO) {
                    resolveAndBuildResponse(packet, queryName)
                }
                if (response != null) {
                    output.write(response)
                } else {
                    output.write(packet)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Packet loop error", e)
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
            }
            try {
                output.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveAndBuildResponse(queryPacket: ByteArray, host: String): ByteArray? {
        return try {
            val addresses = InetAddress.getAllByName(host)
            val ipv4 = addresses.firstOrNull { it is Inet4Address }?.address ?: return null
            DnsParser.buildDnsResponse(queryPacket, ipv4)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve $host", e)
            null
        }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    private fun shutdown() {
        isRunning.set(false)
        saveRunningFlag(false)
        serviceScope?.cancel()
        serviceScope = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun saveRunningFlag(running: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_RUNNING, running)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Статус туннеля",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Раздельный туннель активен")
            .setContentText("Российские сервисы идут напрямую")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "SplitTunnelVpn"
        private const val SESSION_NAME = "Раздельный туннель"
        private const val CHANNEL_ID = "split_tunnel"
        private const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "split_tunnel_prefs"
        const val KEY_IS_RUNNING = "is_running"

        val isRunning = AtomicBoolean(false)

        fun isServiceRunning(context: Context): Boolean {
            if (isRunning.get()) return true
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_RUNNING, false)
        }
    }
}
