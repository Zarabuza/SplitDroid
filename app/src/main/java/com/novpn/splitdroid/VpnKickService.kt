package com.novpn.splitdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Brief VpnService that establishes a dummy TUN then closes it.
 * Android allows only one active VPN — establishing this revokes any third-party VPN.
 */
class VpnKickService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        if (prepare(this) != null) {
            Log.w(TAG, "VPN permission not granted; cannot kick")
            finish()
            return START_NOT_STICKY
        }

        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = Builder()
                .setSession(SESSION_NAME)
                .addAddress(VPN_ADDRESS, 32)
                .establish()
            Log.i(TAG, "VPN kick established (revoking other VPN)")
        } catch (e: Exception) {
            Log.e(TAG, "VPN kick failed", e)
        } finally {
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }

        finish()
        return START_NOT_STICKY
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        finish()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.auto_pause_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.auto_pause_kick_title))
            .setContentText(getString(R.string.auto_pause_kick_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "VpnKick"
        private const val SESSION_NAME = "VPN auto-pause"
        private const val CHANNEL_ID = "vpn_kick"
        private const val NOTIFICATION_ID = 1002
        private const val VPN_ADDRESS = "10.0.0.2"

        fun start(context: Context) {
            val intent = Intent(context, VpnKickService::class.java)
            context.startForegroundService(intent)
        }
    }
}
