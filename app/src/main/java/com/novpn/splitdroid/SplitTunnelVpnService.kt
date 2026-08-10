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
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SplitTunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private val writeLock = Any()

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

        // DNS-only VPN: do NOT route 0.0.0.0/0 (that blackholes all traffic).
        // Only DNS to our fake resolver goes through the TUN.
        // Russian bank/gov apps are addDisallowedApplication so they bypass this
        // VpnService entirely (underlay network, typically without TRANSPORT_VPN).
        val builder = Builder()
            .setSession(SESSION_NAME)
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            .setMtu(1500)
            .setBlocking(true)
            .allowFamily(OsConstants.AF_INET)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val excluded = applyDisallowedRussianApps(builder)
        Log.i(TAG, "Excluded ${excluded.size} Russian apps from VPN: $excluded")

        val established = builder.establish()
        if (established == null) {
            Log.e(TAG, "Failed to establish VPN interface (another VPN may be active)")
            saveRunningFlag(false)
            isRunning.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
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

    /**
     * Bypass this VpnService for known Russian apps that are installed.
     * Disallowed apps use the underlay network (typically without TRANSPORT_VPN),
     * which is what bank/gov apps check for internet + VPN detection.
     */
    private fun applyDisallowedRussianApps(builder: Builder): List<String> {
        val excluded = mutableListOf<String>()
        for (pkg in RussianPackages.packages) {
            try {
                builder.addDisallowedApplication(pkg)
                excluded.add(pkg)
            } catch (_: Exception) {
                // Not installed or not visible — skip
            }
        }
        // Never trap ourselves into the tunnel either
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        return excluded
    }

    private fun packetLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)
        val scope = serviceScope ?: return

        try {
            while (scope.isActive && isRunning.get()) {
                val length = try {
                    input.read(buffer)
                } catch (e: Exception) {
                    break
                }
                if (length <= 0) continue

                val packet = buffer.copyOf(length)
                if (!DnsParser.isDnsQuery(packet)) {
                    // Ignore non-DNS (should be rare with DNS-only routes)
                    continue
                }

                scope.launch {
                    val response = handleDnsQuery(packet)
                    if (response != null) {
                        synchronized(writeLock) {
                            try {
                                output.write(response)
                                output.flush()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to write DNS response", e)
                            }
                        }
                    }
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

    private fun handleDnsQuery(packet: ByteArray): ByteArray? {
        val queryName = DnsParser.parseDnsQueryName(packet)
        val dnsPayload = DnsParser.extractDnsPayload(packet) ?: return null

        return if (queryName != null && RussianServicesList.matchesRussianDomain(queryName)) {
            // Resolve Russian domains outside the VPN tunnel, then answer locally.
            val ipv4 = resolveIpv4Direct(queryName)
            if (ipv4 != null) {
                Log.i(TAG, "RU direct: $queryName -> ${ipv4.joinToString(".") { (it.toInt() and 0xFF).toString() }}")
                DnsParser.buildDnsResponse(packet, ipv4)
            } else {
                // Fallback: upstream Russian-friendly DNS
                val upstream = queryUpstreamDns(dnsPayload, RU_DNS)
                upstream?.let { DnsParser.wrapDnsPayloadAsResponse(packet, it) }
            }
        } else {
            // Everyone else: forward DNS to public resolver outside the tunnel
            val upstream = queryUpstreamDns(dnsPayload, PUBLIC_DNS)
            upstream?.let { DnsParser.wrapDnsPayloadAsResponse(packet, it) }
        }
    }

    /**
     * Resolves [host] without looping into our own VPN DNS:
     * sends a minimal A-query over a protected UDP socket.
     */
    private fun resolveIpv4Direct(host: String): ByteArray? {
        val query = buildDnsAQuery(host) ?: return null
        val response = queryUpstreamDns(query, RU_DNS) ?: queryUpstreamDns(query, PUBLIC_DNS) ?: return null
        return parseFirstARecord(response)
    }

    private fun queryUpstreamDns(dnsQuery: ByteArray, serverIp: String): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 4000
            val server = InetAddress.getByName(serverIp) // numeric IP, no DNS lookup
            socket.send(DatagramPacket(dnsQuery, dnsQuery.size, server, 53))
            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            buf.copyOf(packet.length)
        } catch (e: Exception) {
            Log.w(TAG, "Upstream DNS $serverIp failed", e)
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private val dnsTxId = AtomicInteger(1)

    private fun buildDnsAQuery(host: String): ByteArray? {
        val labels = host.trim('.').split('.').filter { it.isNotEmpty() }
        if (labels.isEmpty()) return null
        val nameBytes = labels.sumOf { 1 + it.length } + 1
        val packet = ByteArray(12 + nameBytes + 4)
        val id = dnsTxId.getAndIncrement() and 0xFFFF
        packet[0] = (id shr 8).toByte()
        packet[1] = (id and 0xFF).toByte()
        packet[2] = 0x01 // recursion desired
        packet[5] = 0x01 // QDCOUNT = 1
        var pos = 12
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.size > 63) return null
            packet[pos++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, packet, pos, bytes.size)
            pos += bytes.size
        }
        packet[pos++] = 0
        packet[pos++] = 0
        packet[pos++] = 1 // TYPE A
        packet[pos++] = 0
        packet[pos] = 1 // CLASS IN
        return packet
    }

    private fun parseFirstARecord(dns: ByteArray): ByteArray? {
        if (dns.size < 12) return null
        val qdCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
        val anCount = ((dns[6].toInt() and 0xFF) shl 8) or (dns[7].toInt() and 0xFF)
        if (anCount < 1) return null

        var pos = 12
        // skip questions
        repeat(qdCount) {
            pos = skipName(dns, pos) ?: return null
            pos += 4
            if (pos > dns.size) return null
        }

        repeat(anCount) {
            pos = skipName(dns, pos) ?: return null
            if (pos + 10 > dns.size) return null
            val type = ((dns[pos].toInt() and 0xFF) shl 8) or (dns[pos + 1].toInt() and 0xFF)
            val rdLength = ((dns[pos + 8].toInt() and 0xFF) shl 8) or (dns[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > dns.size) return null
            if (type == 1 && rdLength == 4) {
                return dns.copyOfRange(pos, pos + 4)
            }
            pos += rdLength
        }
        return null
    }

    private fun skipName(dns: ByteArray, start: Int): Int? {
        var pos = start
        var jumps = 0
        while (pos < dns.size && jumps < 16) {
            val label = dns[pos].toInt() and 0xFF
            when {
                label == 0 -> return pos + 1
                (label and 0xC0) == 0xC0 -> return pos + 2
                else -> {
                    pos += 1 + label
                    jumps++
                }
            }
        }
        return null
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
            .setContentText("Банки и Госуслуги — в обход VPN")
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
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_DNS = "10.0.0.1"
        private const val PUBLIC_DNS = "1.1.1.1"
        private const val RU_DNS = "77.88.8.8"

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
