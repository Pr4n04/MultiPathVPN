package com.multipathvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core VPN service that captures all device traffic and routes
 * it across WiFi and cellular simultaneously with seamless failover.
 *
 * Architecture:
 *   VpnService (TUN interface)
 *        │
 *        ▼
 *   PacketForwarder — reads IP packets from TUN
 *        │
 *        ├── TCP → ConnectionTable assigns network → TcpRelay handles forwarding
 *        └── UDP → NetworkMonitor picks best network → direct UDP socket
 *        │
 *        ▼
 *   Responses written back to TUN interface
 */
class MultiPathVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.multipathvpn.START"
        const val ACTION_STOP = "com.multipathvpn.STOP"
        const val FOREGROUND_CHANNEL_ID = "multipath_vpn_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val VPN_MTU = 1500
        const val VPN_SESSION_NAME = "MultiPath VPN"

        // Static flag so MainActivity can check service state safely
        @Volatile
        var isVpnActive = false

        // DNS servers to push to VPN clients
        val DNS_SERVERS = listOf(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("8.8.8.8")
        )

        // The virtual IP range for the VPN interface (must be a private range)
        const val VPN_IP = "10.88.0.2"
        const val VPN_NETMASK = 24
        const val VPN_NETWORK = "10.88.0.0"

        // Store last error for UI to read
        @Volatile
        var lastError: String? = null
    }

    // --- State ---
    private var tunInterface: ParcelFileDescriptor? = null
    private var vpnThread: Job? = null
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("MultiPathVPN", "Unhandled coroutine crash", throwable)
        lastError = "Coroutine crash: ${throwable.message}"
        isVpnActive = false
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    val isRunning = AtomicBoolean(false)

    // Core components
    lateinit var networkMonitor: NetworkMonitor
    lateinit var connectionTable: ConnectionTable
    lateinit var packetForwarder: PacketForwarder

    // UI callback
    var onStatusChanged: ((String) -> Unit)? = null

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkMonitor = NetworkMonitor(this)
        connectionTable = ConnectionTable()
        packetForwarder = PacketForwarder(this, connectionTable, networkMonitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // VPN Start / Stop
    // ──────────────────────────────────────────────

    private fun startVpn() {
        Log.d("MultiPathVPN", "startVpn() called")
        lastError = null
        try {
            if (isRunning.get()) {
                Log.d("MultiPathVPN", "Already running, ignoring")
                return
            }

            Log.d("MultiPathVPN", "Creating VPN builder")
            val builder = Builder()

            builder.setSession(VPN_SESSION_NAME)
            builder.setMtu(VPN_MTU)
            builder.setBlocking(true)

            builder.addAddress(VPN_IP, VPN_NETMASK)
            DNS_SERVERS.forEach { builder.addDnsServer(it) }
            builder.addRoute("0.0.0.0", 0)

            Log.d("MultiPathVPN", "Calling builder.establish()")
            tunInterface = builder.establish()
            if (tunInterface == null) {
                val msg = "VPN establish returned null — permission not granted?"
                Log.e("MultiPathVPN", msg)
                lastError = msg
                onStatusChanged?.invoke("ERROR: $msg")
                return
            }

            isRunning.set(true)
            isVpnActive = true
            Log.d("MultiPathVPN", "VPN interface established OK")

            Log.d("MultiPathVPN", "Starting foreground notification")
            startForeground(FOREGROUND_NOTIFICATION_ID, createNotification())

            Log.d("MultiPathVPN", "Starting network monitor")
            networkMonitor.startMonitoring()

            Log.d("MultiPathVPN", "Starting packet loop")
            startPacketLoop()

            Log.d("MultiPathVPN", "MultiPath VPN is RUNNING")
            onStatusChanged?.invoke("MultiPath VPN is RUNNING")
            onStatusChanged?.invoke("WiFi: ${networkMonitor.isWifiConnected.get()} | Cellular: ${networkMonitor.isCellularConnected.get()}")
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val stacktrace = sw.toString()
            Log.e("MultiPathVPN", "startVpn() crashed:\n$stacktrace")
            lastError = "${e::class.simpleName}: ${e.message}"
            // Clean up
            try { if (isRunning.get()) stopVpn() } catch (_: Exception) {}
            try { tunInterface?.close() } catch (_: Exception) {}
            tunInterface = null
            isVpnActive = false
        }
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) return
        isVpnActive = false

        vpnThread?.cancel()
        vpnThread = null

        packetForwarder.shutdown()
        networkMonitor.stopMonitoring()
        connectionTable.clear()

        tunInterface?.close()
        tunInterface = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        onStatusChanged?.invoke("MultiPath VPN stopped")
    }

    // ──────────────────────────────────────────────
    // Packet Processing Loop
    // ──────────────────────────────────────────────

    private fun startPacketLoop() {
        vpnThread = scope.launch(Dispatchers.IO) {
            val tunFd = tunInterface?.fileDescriptor ?: return@launch
            val input = FileInputStream(tunFd)
            val output = FileOutputStream(tunFd)
            val buffer = ByteArray(VPN_MTU)

            try {
                while (isRunning.get() && isActive) {
                    val length = try {
                        input.read(buffer)
                    } catch (e: Exception) {
                        break
                    }

                    if (length <= 0) break

                    val packetData = buffer.copyOf(length)
                    val byteBuf = ByteBuffer.wrap(packetData)
                    val version = (byteBuf.get(0).toInt() shr 4) and 0x0F

                    if (version == 4) {
                        handleIPv4Packet(byteBuf, output)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    e.printStackTrace()
                    onStatusChanged?.invoke("Packet loop error: ${e.message}")
                }
            } finally {
                if (isRunning.get()) {
                    stopVpn()
                }
            }
        }
    }

    /**
     * Handle an IPv4 packet from the TUN interface.
     */
    private fun handleIPv4Packet(byteBuf: ByteBuffer, tunOutput: FileOutputStream) {
        // Parse IPv4 header
        val headerLength = (byteBuf.get(0).toInt() and 0x0F) * 4
        val totalLength = byteBuf.getShort(2).toInt() and 0xFFFF
        val protocol = byteBuf.get(9).toInt() and 0xFF

        // Extract source/dest IPs
        val srcIp = inet4FromBytes(byteBuf, 12)
        val dstIp = inet4FromBytes(byteBuf, 16)

        // Skip packets to/from the VPN subnet (routing loops)
        if (dstIp.startsWith("10.88.")) return
        if (srcIp.startsWith("10.88.")) return

        when (protocol) {
            6 -> { // TCP
                handleTcpPacket(byteBuf, totalLength, srcIp, dstIp, tunOutput)
            }
            17 -> { // UDP
                handleUdpPacket(byteBuf, totalLength, srcIp, dstIp, tunOutput)
            }
            else -> {
                // ICMP etc. — route through primary (WiFi if available, else cellular)
                packetForwarder.forwardRawPacket(
                    byteBuf.array().copyOf(totalLength),
                    tunOutput
                )
            }
        }
    }

    private fun handleTcpPacket(
        byteBuf: ByteBuffer,
        totalLength: Int,
        srcIp: String,
        dstIp: String,
        tunOutput: FileOutputStream
    ) {
        // Parse TCP header
        val ipHeaderLen = (byteBuf.get(0).toInt() and 0x0F) * 4
        val tcpHeaderOffset = ipHeaderLen

        val srcPort = byteBuf.getShort(tcpHeaderOffset).toInt() and 0xFFFF
        val dstPort = byteBuf.getShort(tcpHeaderOffset + 2).toInt() and 0xFFFF

        // Check if this is a SYN packet (new connection)
        val flags = byteBuf.get(tcpHeaderOffset + 13).toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        if (isSyn && !isRst) {
            // New TCP connection — assign to best available network
            val network = networkMonitor.selectNetworkForNewConnection()
            connectionTable.addConnection(
                srcIp, srcPort, dstIp, dstPort,
                protocol = 6, network = network
            )
        } else if (isFin || isRst) {
            connectionTable.removeConnection(srcIp, srcPort, dstIp, dstPort)
        }

        // Forward the TCP packet through the assigned network
        val connKey = ConnectionTable.makeKey(srcIp, srcPort, dstIp, dstPort)
        val assignedNetwork = connectionTable.getNetwork(connKey)
            ?: networkMonitor.selectNetworkForNewConnection()

        packetForwarder.forwardRawPacket(
            byteBuf.array().copyOf(totalLength),
            tunOutput,
            preferredNetwork = assignedNetwork
        )
    }

    private fun handleUdpPacket(
        byteBuf: ByteBuffer,
        totalLength: Int,
        srcIp: String,
        dstIp: String,
        tunOutput: FileOutputStream
    ) {
        val ipHeaderLen = (byteBuf.get(0).toInt() and 0x0F) * 4
        val udpHeaderOffset = ipHeaderLen

        val srcPort = byteBuf.getShort(udpHeaderOffset).toInt() and 0xFFFF
        val dstPort = byteBuf.getShort(udpHeaderOffset + 2).toInt() and 0xFFFF

        // For UDP, we use per-flow routing
        val flowKey = ConnectionTable.makeKey(srcIp, srcPort, dstIp, dstPort)

        // Check if we already assigned a network to this flow
        var network = connectionTable.getNetwork(flowKey)
        if (network == null) {
            // New UDP flow — assign to best network
            network = networkMonitor.selectNetworkForUdp()
            connectionTable.addConnection(
                srcIp, srcPort, dstIp, dstPort,
                protocol = 17, network = network,
                timeoutMs = 30_000  // UDP flows expire after 30s idle
            )
        }

        packetForwarder.forwardRawPacket(
            byteBuf.array().copyOf(totalLength),
            tunOutput,
            preferredNetwork = network
        )
    }

    // ──────────────────────────────────────────────
    // Public API (for UI to call)
    // ──────────────────────────────────────────────

    fun getStatusText(): String {
        if (!isRunning.get()) return "Stopped"
        return buildString {
            appendLine("MultiPath VPN is running")
            appendLine("WiFi: ${if (networkMonitor.isWifiConnected.get()) "✓" else "✗"}")
            appendLine("Cellular: ${if (networkMonitor.isCellularConnected.get()) "✓" else "✗"}")
            appendLine("Active connections: ${connectionTable.size()}")
            append("Strategy: ${networkMonitor.strategyLabel}")
        }
    }

    // ──────────────────────────────────────────────
    // Notifications
    // ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "MultiPath VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when MultiPath VPN is active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MultiPathVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("MultiPath VPN Active")
            .setContentText("WiFi + Cellular combined")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ──────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────

    private fun inet4FromBytes(buf: ByteBuffer, offset: Int): String {
        return "${buf.get(offset).toInt() and 0xFF}." +
                "${buf.get(offset + 1).toInt() and 0xFF}." +
                "${buf.get(offset + 2).toInt() and 0xFF}." +
                "${buf.get(offset + 3).toInt() and 0xFF}"
    }
}
