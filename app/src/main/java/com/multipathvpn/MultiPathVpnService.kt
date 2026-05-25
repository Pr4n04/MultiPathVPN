package com.multipathvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class MultiPathVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.multipathvpn.START"
        const val ACTION_STOP = "com.multipathvpn.STOP"
        const val CHANNEL_ID = "multipath_vpn_channel"
        const val NOTIF_ID = 1

        @Volatile
        var isVpnActive = false

        @Volatile
        var lastError: String? = null
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
        Log.e("MultiPathVPN", "Coroutine crashed", t)
        lastError = "Crash: ${t.message}"
        isVpnActive = false
    })

    private val isRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Log.d("MultiPathVPN", "Service created")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MultiPathVPN", "onStartCommand: action=${intent?.action}")
        try {
            when (intent?.action) {
                ACTION_START -> startVpn()
                ACTION_STOP -> stopVpn()
            }
        } catch (e: Exception) {
            Log.e("MultiPathVPN", "onStartCommand crashed", e)
            lastError = "${e::class.simpleName}: ${e.message}"
            isVpnActive = false
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ─── Minimal VPN Start ────────────────────────

    private fun startVpn() {
        Log.d("MultiPathVPN", "startVpn() entered")
        if (isRunning.getAndSet(true)) {
            Log.d("MultiPathVPN", "Already running")
            return
        }

        try {
            val builder = Builder()
            builder.setSession("MultiPath VPN")
            builder.setMtu(1500)
            builder.setBlocking(true)
            builder.addAddress("10.88.0.2", 24)
            builder.addDnsServer(InetAddress.getByName("1.1.1.1"))
            builder.addRoute("0.0.0.0", 0)

            Log.d("MultiPathVPN", "Calling establish()...")
            tunInterface = builder.establish()
            if (tunInterface == null) {
                val msg = "establish() returned null — permission?"
                Log.e("MultiPathVPN", msg)
                lastError = msg
                isRunning.set(false)
                return
            }
            Log.d("MultiPathVPN", "establish() OK")

            // Foreground notification
            try {
                startForeground(NOTIF_ID, buildNotif())
                Log.d("MultiPathVPN", "startForeground() OK")
            } catch (e: Exception) {
                Log.e("MultiPathVPN", "startForeground() failed", e)
                // Continue anyway — VPN can work without foreground on some devices
            }

            isVpnActive = true
            lastError = null
            Log.d("MultiPathVPN", "VPN started successfully!")

            // Start minimal packet reading loop
            startMinimalLoop()

        } catch (e: Exception) {
            Log.e("MultiPathVPN", "startVpn() exception", e)
            lastError = "${e::class.simpleName}: ${e.message}"
            isRunning.set(false)
            isVpnActive = false
            try { tunInterface?.close() } catch (_: Exception) {}
            tunInterface = null
        }
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false) && !isVpnActive) return
        isVpnActive = false
        Log.d("MultiPathVPN", "Stopping VPN")
        loopJob?.cancel()
        loopJob = null
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    // ─── Minimal packet loop (reads and discards) ──

    private fun startMinimalLoop() {
        loopJob = scope.launch {
            val fd = tunInterface?.fileDescriptor ?: return@launch
            val input = FileInputStream(fd)
            val buf = ByteArray(1500)
            Log.d("MultiPathVPN", "Packet loop started")
            while (isActive && isRunning.get()) {
                try {
                    val n = input.read(buf)
                    if (n <= 0) break
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    // ─── Notification ─────────────────────────────

    private fun createChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID, "MultiPath VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN status"
                setShowBadge(false)
            }
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        } catch (_: Exception) {}
    }

    private fun buildNotif(): Notification {
        val stopIntent = Intent(this, MultiPathVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiPath VPN")
            .setContentText("WiFi + Cellular combined")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
