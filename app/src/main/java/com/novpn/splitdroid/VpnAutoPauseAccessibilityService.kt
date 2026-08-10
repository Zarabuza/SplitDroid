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
 * on leave (including Home/launcher), silently launch VPN and auto-click Connect.
 */
class VpnAutoPauseAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null
    private var lastRussianPackage: String? = null
    private var restoreClickSucceeded = false
    private var fallbackRunnable: Runnable? = null
    private var returnHomeRunnable: Runnable? = null
    private var graceCheckRunnable: Runnable? = null
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
        val isRu = RussianPackages.packages.contains(pkg)

        // HyperOS/MIUI often only emits CONTENT_CHANGED for manual icon taps;
        // adb/monkey startActivity reliably gets WINDOW_STATE_CHANGED. Accept
        // CONTENT_CHANGED for RU enter / leave-while-paused so manual opens kick.
        val isWindowSwitch = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val isContentHint = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            (isRu || (paused && (isLauncher || !isSelectedVpnPackage(pkg))))
        if (!isWindowSwitch && !isContentHint) return

        // Ignore Settings / permission UI noise, but NEVER ignore launchers when paused
        // (Home was previously ignored → restore never ran → stuck forever).
        if (pkg in NOISE_PACKAGES && !(paused && isLauncher)) return
        if (pkg == packageName) return
        if (shouldIgnoreAsNoise(pkg) && !isLauncher && !paused) return

        // Duplicate window events: skip, BUT still allow RU re-entry kick when not paused.
        // After kick-grace we revert lastForeground to the RU pkg; if VPN was restored and
        // user opens the same app again without a clean leave event, the equality check
        // would otherwise swallow the kick (manual opens often look like "same pkg").
        if (pkg == lastForegroundPackage) {
            if (!(isRu && !paused)) return
            Log.d(TAG, "Re-entry same pkg=$pkg (not paused) — allow kick")
            onEnteredRussianApp(pkg, lastForegroundPackage)
            return
        }

        val previous = lastForegroundPackage
        lastForegroundPackage = pkg
        Log.d(TAG, "foreground=$pkg ru=$isRu paused=$paused type=$type")
        when {
            isRu -> onEnteredRussianApp(pkg, previous)
            paused && !isSelectedVpnPackage(pkg) -> onLeftRussianApp(pkg)
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        cancelGraceCheck()
        super.onDestroy()
    }

    private fun onEnteredRussianApp(pkg: String, previous: String?) {
        cancelFallback()
        cancelReturnHome()
        cancelGraceCheck()
        AutoPausePrefs.setRestoring(this, false)
        restoreClickSucceeded = false
        lastRussianPackage = pkg

        val returnPkg = previous
            ?.takeIf { it.isNotBlank() && it !in NOISE_PACKAGES && it !in LAUNCHER_PACKAGES }
            ?.takeIf { !isSelectedVpnPackage(it) }
            ?.takeIf { !RussianPackages.packages.contains(it) }
        if (!returnPkg.isNullOrBlank()) {
            AutoPausePrefs.setReturnPackage(this, returnPkg)
        }

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

        Log.i(TAG, "Russian app foreground: $pkg — kicking VPN")
        AutoPausePrefs.setPaused(this, true)
        VpnKickService.start(this)
        schedulePostKickGraceCheck()
    }

    private fun onLeftRussianApp(pkg: String) {
        if (!AutoPausePrefs.isPaused(this)) return
        if (AutoPausePrefs.isRestoring(this)) return

        val now = System.currentTimeMillis()
        // Xiaomi often emits a brief launcher window right after kick/VPN revoke — ignore it.
        if (now - lastKickAtMs < KICK_GRACE_MS) {
            Log.d(TAG, "Restore skipped: within kick grace ($pkg)")
            // Keep Russian app as "current" so a real later Home still counts as a leave.
            lastRussianPackage?.let { lastForegroundPackage = it }
            return
        }
        if (now - lastRestoreAtMs < 2000L) {
            Log.d(TAG, "Restore debounced")
            return
        }
        lastRestoreAtMs = now

        Log.i(TAG, "Left Russian app; foreground=$pkg — silent VPN restore")
        // Keep paused=true until restore finishes or fails, so we don't double-fire
        startSilentRestore()
    }

    /** If user left for real during kick grace, catch up once grace ends. */
    private fun schedulePostKickGraceCheck() {
        cancelGraceCheck()
        val check = Runnable {
            if (!AutoPausePrefs.isPaused(this) || AutoPausePrefs.isRestoring(this)) return@Runnable
            val activePkg = rootInActiveWindow?.packageName?.toString()
                ?: lastForegroundPackage
                ?: return@Runnable
            if (RussianPackages.packages.contains(activePkg) || isSelectedVpnPackage(activePkg)) {
                lastForegroundPackage = activePkg
                return@Runnable
            }
            lastForegroundPackage = activePkg
            Log.i(TAG, "Post-kick grace: still away from RU ($activePkg) — restore")
            onLeftRussianApp(activePkg)
        }
        graceCheckRunnable = check
        handler.postDelayed(check, KICK_GRACE_MS + 200L)
    }

    private fun startSilentRestore() {
        val vpnPkg = AutoPausePrefs.vpnPackage(this)
        if (vpnPkg.isBlank()) {
            Log.w(TAG, "No VPN package selected for restore")
            AutoPausePrefs.setPaused(this, false)
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
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        if (launchIntent == null) {
            Log.w(TAG, "Cannot launch VPN package $vpnPkg")
            AutoPausePrefs.setPaused(this, false)
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
            AutoPausePrefs.setPaused(this, false)
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(null)
            }
            return
        }

        listOf(400L, 900L, 1500L, 2200L, 3200L, 4500L, 6500L, 8500L).forEach { delay ->
            handler.postDelayed({ tryAutoClickConnect() }, delay)
        }

        cancelFallback()
        val fallback = Runnable {
            if (restoreClickSucceeded) return@Runnable
            Log.w(TAG, "Silent connect click timed out")
            AutoPausePrefs.setRestoring(this, false)
            AutoPausePrefs.setPaused(this, false)
            if (AutoPausePrefs.isNotifyFallback(this)) {
                showRestoreNotification(launchIntent)
            } else {
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

            // ЮБуст / many VPNs: after launch they may already be ON or connecting.
            // Treat "ОТКЛЮЧИТЬ" / connected as restore success — do NOT click (would disconnect).
            if (vpnLooksAlreadyOn(root)) {
                Log.i(TAG, "VPN UI already connected/connecting — restore done")
                onRestoreSucceeded()
                return
            }

            val clicked = clickConnectButton(root)
            if (clicked) {
                Log.i(TAG, "Auto-clicked Connect in VPN app")
                onRestoreSucceeded()
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
                // Unchecked Switch / toggle in VPN apps often means "off → tap to connect"
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

        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        val toClick = clickable ?: target

        // Compose (ЮБуст) often ignores ACTION_CLICK — tap center via gesture.
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

    private fun cancelGraceCheck() {
        graceCheckRunnable?.let { handler.removeCallbacks(it) }
        graceCheckRunnable = null
    }

    companion object {
        private const val TAG = "VpnAutoPauseA11y"
        private const val CHANNEL_ID = "auto_pause_vpn"
        private const val NOTIF_RESTORE = 2002
        private const val FALLBACK_MS = 11000L
        private const val KICK_GRACE_MS = 3000L

        const val PREFERRED_VPN_PACKAGE = "st.uboo.android.client"

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
