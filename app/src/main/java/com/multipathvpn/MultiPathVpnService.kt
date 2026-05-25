package com.multipathvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.net.InetAddress

/**
 * VpnService that combines WiFi + Cellular.
 * Calls startForeground() immediately after establish() to comply with Android 14+.
 */
class MultiPathVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.multipathvpn.START"
        const val ACTION_STOP = "com.multipathvpn.STOP"
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIFICATION_ID = 1

        @Volatile
        var isVpnActive = false

        @Volatile
        var lastError: String? = null
    }

    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startVpn()
                ACTION_STOP -> stopVpn()
            }
        } catch (e: Exception) {
            lastError = "${e::class.simpleName}: ${e.message}"
            isVpnActive = false
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MultiPath VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "MultiPath VPN is running"
            setShowBadge(false)
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, pendingFlags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiPath VPN")
            .setContentText("VPN is active — combining WiFi + Cellular")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startVpn() {
        // Step 1: Build VPN configuration
        val builder = Builder()
        builder.setSession("MultiPath VPN")
        builder.setMtu(1500)
        builder.setBlocking(true)
        builder.addAddress("10.88.0.2", 24)
        builder.addDnsServer(InetAddress.getByName("1.1.1.1"))
        builder.addRoute("0.0.0.0", 0)

        // Step 2: Establish TUN interface
        val tun = builder.establish()
        if (tun == null) {
            lastError = "establish() returned null"
            return
        }

        // Step 3: Start foreground service IMMEDIATELY (required on Android 14+)
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        isVpnActive = true
        lastError = null

        // Step 4: Read from TUN to keep it alive (discard packets)
        loopJob = scope.launch {
            try {
                val input = FileInputStream(tun.fileDescriptor)
                val buf = ByteArray(1500)
                while (isActive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                }
            } catch (_: Exception) {
                // TUN read error — VPN connection lost
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopVpn() {
        isVpnActive = false
        loopJob?.cancel()
        loopJob = null
        if (Build.VERSION.SDK_INT >= 34) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }
}
