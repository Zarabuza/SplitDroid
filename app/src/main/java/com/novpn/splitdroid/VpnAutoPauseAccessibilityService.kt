package com.novpn.splitdroid

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Background watcher: kick third-party VPN on Russian bank/gov apps;
 * on leave, silently launch the selected VPN app and auto-click Connect.
 */
class VpnAutoPauseAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null
    private var restoreClickSucceeded = false
    private var fallbackRunnable: Runnable? = null
    private var returnHomeRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!AutoPausePrefs.isEnabled(this)) return

        val type = event.eventType
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return

        // While restoring, keep trying to click Connect on VPN UI
        if (AutoPausePrefs.isRestoring(this) && isSelectedVpnPackage(pkg)) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                tryAutoClickConnect()
            }
            return
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        if (shouldIgnorePackage(pkg)) return
        if (pkg == lastForegroundPackage) return
        val previous = lastForegroundPackage
        lastForegroundPackage = pkg

        val isRu = RussianPackages.packages.contains(pkg)
        if (isRu) {
            onEnteredRussianApp(pkg, previous)
        } else if (!isSelectedVpnPackage(pkg)) {
            onLeftRussianApp(pkg)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancelFallback()
        cancelReturnHome()
        super.onDestroy()
    }

    private fun onEnteredRussianApp(pkg: String, previous: String?) {
        cancelFallback()
        cancelReturnHome()
        AutoPausePrefs.setRestoring(this, false)
        restoreClickSucceeded = false

        // Remember where to return after silent reconnect (app before the bank)
        val returnPkg = previous
            ?.takeIf { it.isNotBlank() && !shouldIgnorePackage(it) && !isSelectedVpnPackage(it) }
            ?.takeIf { !RussianPackages.packages.contains(it) }
        if (!returnPkg.isNullOrBlank()) {
            AutoPausePrefs.setReturnPackage(this, returnPkg)
        }

        if (AutoPausePrefs.isPaused(this)) {
            Log.d(TAG, "Already paused; foreground=$pkg")
            return
        }
        Log.i(TAG, "Russian app foreground: $pkg — kicking VPN")
        AutoPausePrefs.setPaused(this, true)
        VpnKickService.start(this)
    }

    private fun onLeftRussianApp(pkg: String) {
        if (!AutoPausePrefs.isPaused(this)) return
        if (AutoPausePrefs.isRestoring(this)) return

        Log.i(TAG, "Left Russian app; foreground=$pkg — silent VPN restore")
        AutoPausePrefs.setPaused(this, false)
        startSilentRestore()
    }

    private fun startSilentRestore() {
        val vpnPkg = AutoPausePrefs.vpnPackage(this)
        if (vpnPkg.isBlank()) {
            Log.w(TAG, "No VPN package selected for restore")
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(null)
            }
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(vpnPkg)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (launchIntent == null) {
            Log.w(TAG, "Cannot launch VPN package $vpnPkg")
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(null)
            }
            return
        }

        AutoPausePrefs.setRestoring(this, true)
        restoreClickSucceeded = false

        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch VPN app $vpnPkg", e)
            AutoPausePrefs.setRestoring(this, false)
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(null)
            }
            return
        }

        // Retry click shortly after UI settles
        handler.postDelayed({ tryAutoClickConnect() }, 400)
        handler.postDelayed({ tryAutoClickConnect() }, 1200)
        handler.postDelayed({ tryAutoClickConnect() }, 2200)

        cancelFallback()
        val fallback = Runnable {
            if (restoreClickSucceeded) return@Runnable
            Log.w(TAG, "Silent connect click timed out")
            AutoPausePrefs.setRestoring(this, false)
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(launchIntent)
            } else {
                // Leave VPN UI; user can connect manually without a notification
                returnToPreviousOrHome()
            }
        }
        fallbackRunnable = fallback
        handler.postDelayed(fallback, FALLBACK_MS)
    }

    private fun tryAutoClickConnect() {
        if (!AutoPausePrefs.isRestoring(this) || restoreClickSucceeded) return
        val root = rootInActiveWindow ?: return
        try {
            val vpnPkg = AutoPausePrefs.vpnPackage(this)
            val rootPkg = root.packageName?.toString()
            if (vpnPkg.isNotBlank() && rootPkg != null && rootPkg != vpnPkg) {
                return
            }
            val clicked = clickConnectButton(root)
            if (clicked) {
                Log.i(TAG, "Auto-clicked Connect in VPN app")
                restoreClickSucceeded = true
                cancelFallback()
                AutoPausePrefs.setRestoring(this, false)
                // Brief delay so VPN can process the tap, then leave its UI
                cancelReturnHome()
                val go = Runnable { returnToPreviousOrHome() }
                returnHomeRunnable = go
                handler.postDelayed(go, 700)
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryAutoClickConnect failed", e)
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun clickConnectButton(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = buildString {
                node.text?.let { append(it) }
                append(' ')
                node.contentDescription?.let { append(it) }
            }.trim().lowercase()

            if (text.isNotEmpty() && looksLikeDisconnect(text)) {
                // skip
            } else if (text.isNotEmpty()) {
                val score = connectScore(text)
                if (score > bestScore && (node.isClickable || node.isEnabled)) {
                    bestScore = score
                    best = node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val target = best ?: return false
        if (bestScore <= 0) return false

        // Prefer clicking the node itself; else walk up to a clickable parent
        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        val toClick = clickable ?: target
        val ok = toClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            // Some VPN UIs use a Switch — try toggle via click on parent again
            toClick.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return true
    }

    private fun connectScore(text: String): Int {
        // Higher = better match. Avoid weak single-letter hits.
        return when {
            text == "подключить" || text == "connect" -> 100
            text == "включить" || text == "enable" -> 95
            text.contains("подключить") -> 90
            text.contains("переподключить") || text.contains("reconnect") -> 88
            text.contains("connect vpn") || text.contains("подключить vpn") -> 92
            text.contains("connect") -> 80
            text.contains("включить") -> 85
            text.contains("enable") || text.contains("turn on") -> 75
            text == "start" || text.contains("start vpn") -> 70
            text == "вкл" -> 60
            else -> 0
        }
    }

    private fun looksLikeDisconnect(text: String): Boolean {
        return DISCONNECT_HINTS.any { text == it || text.contains(it) }
    }

    private fun returnToPreviousOrHome() {
        val returnPkg = AutoPausePrefs.returnPackage(this)
        if (returnPkg.isNotBlank()) {
            val intent = packageManager.getLaunchIntentForPackage(returnPkg)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            if (intent != null) {
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to return to $returnPkg", e)
                }
            }
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun showRestoreNotification(launchIntent: Intent?) {
        ensureChannel()
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                1,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.auto_pause_restore_title))
            .setContentText(getString(R.string.auto_pause_restore_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_RESTORE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post restore notification", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.auto_pause_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun isSelectedVpnPackage(pkg: String): Boolean {
        val selected = AutoPausePrefs.vpnPackage(this)
        return selected.isNotBlank() && pkg == selected
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        if (pkg == packageName) return true
        return pkg in IGNORED_PACKAGES
    }

    private fun cancelFallback() {
        fallbackRunnable?.let { handler.removeCallbacks(it) }
        fallbackRunnable = null
    }

    private fun cancelReturnHome() {
        returnHomeRunnable?.let { handler.removeCallbacks(it) }
        returnHomeRunnable = null
    }

    companion object {
        private const val TAG = "VpnAutoPauseA11y"
        private const val CHANNEL_ID = "auto_pause_vpn"
        private const val NOTIF_RESTORE = 2002
        private const val FALLBACK_MS = 3000L

        const val PREFERRED_VPN_PACKAGE = "st.uboo.android.client"

        private val DISCONNECT_HINTS = listOf(
            "отключить",
            "выключить",
            "disconnect",
            "turn off",
            "stop vpn",
            "disable"
        )

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "android"
        )

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = ComponentName(context, VpnAutoPauseAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { flat ->
                ComponentName.unflattenFromString(flat)?.flattenToString() ==
                    expected.flattenToString()
            }
        }
    }
}
