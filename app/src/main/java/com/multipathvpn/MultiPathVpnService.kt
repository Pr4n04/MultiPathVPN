package com.multipathvpn

import android.content.Intent
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.net.InetAddress

/**
 * ABSOLUTE MINIMAL VpnService — no notifications, no packet processing.
 * Just establishes the VPN tunnel to test if the device supports it.
 */
class MultiPathVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.multipathvpn.START"
        const val ACTION_STOP = "com.multipathvpn.STOP"

        @Volatile
        var isVpnActive = false

        @Volatile
        var lastError: String? = null
    }

    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startVpn()
                ACTION_STOP -> stopVpn()
            }
        } catch (e: Exception) {
            Log.e("MultiPathVPN", "CRASH", e)
            lastError = "${e::class.simpleName}: ${e.message}"
            isVpnActive = false
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        Log.d("MultiPathVPN", "startVpn()")

        // STEP 1: Build the VPN interface
        val builder = Builder()
        builder.setSession("MultiPath VPN")
        builder.setMtu(1500)
        builder.setBlocking(true)
        builder.addAddress("10.88.0.2", 24)
        builder.addDnsServer(InetAddress.getByName("1.1.1.1"))
        builder.addRoute("0.0.0.0", 0)

        // STEP 2: Establish (this creates the TUN interface)
        Log.d("MultiPathVPN", "Calling establish()...")
        val tun = builder.establish()
        if (tun == null) {
            Log.e("MultiPathVPN", "establish() returned null")
            lastError = "establish() returned null"
            return
        }
        Log.d("MultiPathVPN", "establish() SUCCESS")

        isVpnActive = true
        lastError = null
        Log.d("MultiPathVPN", "VPN is RUNNING")

        // STEP 3: Read from TUN to keep it alive (discard packets)
        loopJob = scope.launch {
            val input = FileInputStream(tun.fileDescriptor)
            val buf = ByteArray(1500)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                }
            } catch (_: Exception) {}
        }
    }

    private fun stopVpn() {
        isVpnActive = false
        loopJob?.cancel()
        loopJob = null
        // ParcelFileDescriptor auto-closes when garbage collected
        stopSelf()
    }
}
