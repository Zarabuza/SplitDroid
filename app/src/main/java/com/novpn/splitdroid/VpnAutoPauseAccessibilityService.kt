package com.novpn.splitdroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * Background watcher:
 * - Enter app from "без VPN" list → kick third-party VPN
 * - Enter app from "нужен VPN" list (TG, YouTube, …) → silent restore + Connect
 * - Home / other apps → do nothing (VPN stays off until a "нужен VPN" app)
 */
class VpnAutoPauseAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null
    private var lastBypassPackage: String? = null
    private var restoreClickSucceeded = false
    private var fallbackRunnable: Runnable? = null
    private var returnHomeRunnable: Runnable? = null
    private var lastKickAtMs = 0L
    private var lastRestoreAtMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!AutoPausePrefs.isEnabled(this)) return

        val type = event.eventType
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return

        // While restoring: keep trying Connect on the VPN UI
        if (AutoPausePrefs.isRestoring(this) && isSelectedVpnPackage(pkg)) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                tryAutoClickConnect()
            }
            return
        }

        val isLauncher = pkg in LAUNCHER_PACKAGES
        val paused = AutoPausePrefs.isPaused(this)
        val isBypass = AutoPausePrefs.isBypassPackage(this, pkg)
        val isVpnNeeded = AutoPausePrefs.isVpnNeededPackage(this, pkg)

        // HyperOS/MIUI often only emits CONTENT_CHANGED for manual icon taps;
        // adb/monkey startActivity reliably gets WINDOW_STATE_CHANGED.
        val isWindowSwitch = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val isContentHint = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            (isBypass || isVpnNeeded)
        if (!isWindowSwitch && !isContentHint) return

        // Content events can arrive from non-foreground windows — require match.
        if (isContentHint && !isWindowSwitch) {
            val active = rootInActiveWindow?.packageName?.toString()
            if (active != null && active != pkg) return
        }

        if (pkg in NOISE_PACKAGES) return
        if (pkg == packageName) return
        if (shouldIgnoreAsNoise(pkg) && !isLauncher && !isBypass && !isVpnNeeded) return
        // Home / launcher: never auto-restore VPN (restore only via "нужен VPN" apps)
        if (isLauncher) {
            lastForegroundPackage = pkg
            return
        }

        // Duplicate events: still allow bypass re-kick / vpn-needed restore when sticky.
        if (pkg == lastForegroundPackage) {
            when {
                isBypass && !paused -> {
                    Log.d(TAG, "Re-entry bypass pkg=$pkg — allow kick")
                    onEnteredBypassApp(pkg)
                }
                isVpnNeeded && paused -> {
                    Log.d(TAG, "Re-entry vpn-needed pkg=$pkg while paused — restore")
                    onEnteredVpnNeededApp(pkg)
                }
            }
            return
        }

        lastForegroundPackage = pkg
        Log.d(TAG, "foreground=$pkg bypass=$isBypass needVpn=$isVpnNeeded paused=$paused type=$type")
        when {
            isBypass -> onEnteredBypassApp(pkg)
            isVpnNeeded -> onEnteredVpnNeededApp(pkg)
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutoPausePrefs.ensureListsInitialized(this)
        Log.i(TAG, "Accessibility connected; autoPause=${AutoPausePrefs.isEnabled(this)}")
        // Clear sticky stuck flags from older buggy builds
        if (!AutoPausePrefs.isEnabled(this)) {
            AutoPausePrefs.setPaused(this, false)
            AutoPausePrefs.setRestoring(this, false)
        }
    }

    override fun onDestroy() {
        cancelFallback()
        cancelReturnHome()
        super.onDestroy()
    }

    private fun onEnteredBypassApp(pkg: String) {
        cancelFallback()
        cancelReturnHome()
        AutoPausePrefs.setRestoring(this, false)
        restoreClickSucceeded = false
        lastBypassPackage = pkg

        if (AutoPausePrefs.isPaused(this)) {
            Log.d(TAG, "Already paused; stay paused for $pkg")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastKickAtMs < 1500L) {
            Log.d(TAG, "Kick debounced")
            return
        }
        lastKickAtMs = now

        Log.i(TAG, "Bypass app foreground: $pkg — kicking VPN")
        AutoPausePrefs.setPaused(this, true)
        VpnKickService.start(this)
    }

    private fun onEnteredVpnNeededApp(pkg: String) {
        // After connecting, return to this app (TG / YouTube), not Home.
        AutoPausePrefs.setReturnPackage(this, pkg)

        if (isVpnTransportActive()) {
            if (AutoPausePrefs.isPaused(this)) {
                AutoPausePrefs.setPaused(this, false)
            }
            Log.d(TAG, "VPN-needed app $pkg — VPN already active")
            return
        }

        // VPN is off: restore even if paused flag was lost after a failed earlier attempt.
        AutoPausePrefs.setPaused(this, true)
        if (AutoPausePrefs.isRestoring(this)) return

        val now = System.currentTimeMillis()
        if (now - lastKickAtMs < KICK_GRACE_MS) {
            Log.d(TAG, "Restore deferred: within kick grace ($pkg)")
            handler.postDelayed({
                if (!isVpnTransportActive()) onEnteredVpnNeededApp(pkg)
            }, KICK_GRACE_MS - (now - lastKickAtMs) + 50L)
            return
        }
        if (now - lastRestoreAtMs < 2500L) {
            Log.d(TAG, "Restore debounced")
            return
        }
        lastRestoreAtMs = now

        Log.i(TAG, "VPN-needed app foreground: $pkg — silent VPN restore (vpnActive=false)")
        startSilentRestore()
    }

    private fun isVpnTransportActive(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
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
            ?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        if (launchIntent == null) {
            Log.w(TAG, "Cannot launch VPN package $vpnPkg")
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(null)
            }
            return
        }

        AutoPausePrefs.setRestoring(this, true)
        AutoPausePrefs.setPaused(this, true)
        restoreClickSucceeded = false

        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch VPN app $vpnPkg", e)
            AutoPausePrefs.setRestoring(this, false)
            // Keep paused=true so next open of TG retries
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(null)
            }
            return
        }

        listOf(500L, 1000L, 1600L, 2400L, 3500L, 5000L, 7000L, 9000L, 11000L).forEach { delay ->
            handler.postDelayed({ tryAutoClickConnect() }, delay)
        }
        // Also poll system VPN state — ЮБуст sometimes connects without a click we notice.
        listOf(2000L, 4000L, 6000L, 8000L, 10000L).forEach { delay ->
            handler.postDelayed({
                if (!restoreClickSucceeded && AutoPausePrefs.isRestoring(this) && isVpnTransportActive()) {
                    Log.i(TAG, "System VPN became active during restore")
                    onRestoreSucceeded()
                }
            }, delay)
        }

        cancelFallback()
        val fallback = Runnable {
            if (restoreClickSucceeded) return@Runnable
            Log.w(TAG, "Silent connect click timed out — keeping paused for retry")
            AutoPausePrefs.setRestoring(this, false)
            // Do NOT clear paused — next TG/YouTube open must retry
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(launchIntent)
            }
            returnToPreviousOrHome()
        }
        fallbackRunnable = fallback
        handler.postDelayed(fallback, FALLBACK_MS)
    }

    private fun tryAutoClickConnect() {
        if (!AutoPausePrefs.isRestoring(this) || restoreClickSucceeded) return
        if (isVpnTransportActive()) {
            Log.i(TAG, "VPN active before click — restore done")
            onRestoreSucceeded()
            return
        }
        val root = rootInActiveWindow ?: return
        try {
            val vpnPkg = AutoPausePrefs.vpnPackage(this)
            val rootPkg = root.packageName?.toString()
            if (vpnPkg.isNotBlank() && rootPkg != null && rootPkg != vpnPkg) {
                return
            }

            if (vpnLooksAlreadyOn(root)) {
                Log.i(TAG, "VPN UI already connected — restore done")
                onRestoreSucceeded()
                return
            }

            val clicked = clickConnectButton(root)
            if (clicked) {
                Log.i(TAG, "Auto-clicked Connect in VPN app — waiting for VPN")
                // Don't succeed until TRANSPORT_VPN appears (or UI shows disconnect).
                handler.postDelayed({
                    if (restoreClickSucceeded) return@postDelayed
                    if (isVpnTransportActive()) {
                        onRestoreSucceeded()
                    } else {
                        val again = rootInActiveWindow
                        try {
                            if (again != null && vpnLooksAlreadyOn(again)) {
                                onRestoreSucceeded()
                            }
                        } finally {
                            try {
                                again?.recycle()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }, 1200)
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

    private fun onRestoreSucceeded() {
        restoreClickSucceeded = true
        cancelFallback()
        AutoPausePrefs.setRestoring(this, false)
        AutoPausePrefs.setPaused(this, false)
        cancelReturnHome()
        val go = Runnable { returnToPreviousOrHome() }
        returnHomeRunnable = go
        handler.postDelayed(go, 900)
    }

    private fun vpnLooksAlreadyOn(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeLabel(node)
            if (text.isNotEmpty() && looksLikeAlreadyConnected(text)) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun clickConnectButton(root: AccessibilityNodeInfo): Boolean {
        // Fast path: find by visible label (ЮБуст: «ПОДКЛЮЧИТЬ»).
        for (label in CONNECT_LABELS) {
            val matches = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (node in matches) {
                val text = nodeLabel(node)
                if (looksLikeDisconnect(text)) continue
                if (connectScore(text) <= 0 && !text.contains(label.lowercase())) continue
                if (clickNodeOrAncestors(node)) {
                    Log.d(TAG, "Clicked by text '$label'")
                    return true
                }
            }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val className = node.className?.toString().orEmpty()
            val text = nodeLabel(node)

            var score = 0
            if (text.isNotEmpty() && looksLikeDisconnect(text)) {
                score = 0
            } else {
                if (text.isNotEmpty()) score = connectScore(text)
                if ((className.contains("Switch", ignoreCase = true) ||
                        className.contains("Toggle", ignoreCase = true) ||
                        className.contains("CheckBox", ignoreCase = true)) &&
                    node.isEnabled && !node.isChecked
                ) {
                    score = maxOf(score, 78)
                }
            }

            if (score > bestScore) {
                bestScore = score
                best = node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val target = best ?: return false
        if (bestScore <= 0) {
            Log.d(TAG, "No Connect target found in VPN UI")
            return false
        }
        Log.d(TAG, "Connect target score=$bestScore label='${nodeLabel(target)}'")
        return clickNodeOrAncestors(target)
    }

    private fun clickNodeOrAncestors(target: AccessibilityNodeInfo): Boolean {
        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        val toClick = clickable ?: target
        if (gestureClick(toClick)) return true
        if (toClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (toClick.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        return gestureClick(target)
    }

    private fun gestureClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "gestureClick failed", e)
            false
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return buildString {
            node.text?.let { append(it) }
            append(' ')
            node.contentDescription?.let { append(it) }
        }.trim().lowercase()
    }

    private fun connectScore(text: String): Int {
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
            text.contains("соединен") -> 65
            text == "вкл" -> 60
            else -> 0
        }
    }

    private fun looksLikeDisconnect(text: String): Boolean {
        return DISCONNECT_HINTS.any { text == it || text.contains(it) }
    }

    /** VPN already up or reconnecting — restore should finish without tapping. */
    private fun looksLikeAlreadyConnected(text: String): Boolean {
        return ALREADY_ON_HINTS.any { text == it || text.contains(it) }
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

    private fun shouldIgnoreAsNoise(pkg: String): Boolean {
        return pkg.startsWith("com.android.") ||
            pkg.startsWith("com.miui.") ||
            pkg.startsWith("com.xiaomi.") ||
            pkg in NOISE_PACKAGES
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
        private const val FALLBACK_MS = 13000L
        private const val KICK_GRACE_MS = 3000L

        const val PREFERRED_VPN_PACKAGE = "st.uboo.android.client"

        private val CONNECT_LABELS = listOf(
            "ПОДКЛЮЧИТЬ",
            "Подключить",
            "подключить",
            "CONNECT",
            "Connect",
            "Включить",
            "ENABLE"
        )

        private val DISCONNECT_HINTS = listOf(
            "отключить",
            "выключить",
            "disconnect",
            "turn off",
            "stop vpn",
            "disable"
        )

        private val ALREADY_ON_HINTS = listOf(
            "отключить",
            "выключить",
            "disconnect",
            "turn off",
            "stop vpn",
            "connected",
            "подключено",
            "соединено"
        )

        private val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.android.systemui" // recent apps / home gestures sometimes
        )

        private val NOISE_PACKAGES = setOf(
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
