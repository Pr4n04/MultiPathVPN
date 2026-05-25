package com.multipathvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var wifiStatusText: TextView
    private lateinit var cellularStatusText: TextView
    private lateinit var connectionCountText: TextView
    private lateinit var strategySwitch: SwitchMaterial

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusUpdateJob: Job? = null

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            statusText.text = "VPN permission denied"
        }
    }

    // Status update receiver
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateUi()
        }
    }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        statusText = findViewById(R.id.status_text)
        startStopButton = findViewById(R.id.start_stop_button)
        wifiStatusText = findViewById(R.id.wifi_status)
        cellularStatusText = findViewById(R.id.cellular_status)
        connectionCountText = findViewById(R.id.connection_count)
        strategySwitch = findViewById(R.id.strategy_switch)

        // Setup UI
        strategySwitch.text = "Round Robin"
        strategySwitch.isChecked = true

        startStopButton.setOnClickListener {
            if (isServiceRunning()) {
                stopVpn()
            } else {
                requestVpnAndStart()
            }
        }

        strategySwitch.setOnCheckedChangeListener { _, isChecked ->
            val strategy = if (isChecked) {
                NetworkMonitor.RoutingStrategy.ROUND_ROBIN
            } else {
                NetworkMonitor.RoutingStrategy.WIFI_PREFERRED
            }
            // Update strategy via the service
            updateStrategy(strategy)
        }

        // Register status receiver
        registerReceiver(
            statusReceiver,
            IntentFilter("com.multipathvpn.STATUS_UPDATE"),
            ContextCompat.RECEIVER_EXPORTED
        )

        // Initial UI update
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        startStatusUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopStatusUpdates()
    }

    override fun onDestroy() {
        stopStatusUpdates()
        unregisterReceiver(statusReceiver)
        scope.cancel()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // VPN Start/Stop
    // ──────────────────────────────────────────────

    private fun requestVpnAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Need to request VPN permission from user
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already have permission
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, MultiPathVpnService::class.java).apply {
            action = MultiPathVpnService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Android 14+ may block foreground service start
            // Fallback to regular startService
            startService(intent)
        }
        statusText.text = "Starting..."
    }

    private fun stopVpn() {
        val intent = Intent(this, MultiPathVpnService::class.java).apply {
            action = MultiPathVpnService.ACTION_STOP
        }
        startService(intent)
        statusText.text = "Stopping..."
    }

    private fun isVpnRunning(): Boolean {
        return MultiPathVpnService.isVpnActive
    }

    // ──────────────────────────────────────────────
    // UI Updates
    // ──────────────────────────────────────────────

    private fun startStatusUpdates() {
        statusUpdateJob = scope.launch {
            while (isActive) {
                updateUi()
                delay(1000)  // Update every second
            }
        }
    }

    private fun stopStatusUpdates() {
        statusUpdateJob?.cancel()
        statusUpdateJob = null
    }

    private fun updateUi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Check current network state
        val activeNetwork = cm.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }

        val wifiConnected = caps?.hasTransport(
            android.net.NetworkCapabilities.TRANSPORT_WIFI
        ) == true

        val cellularConnected = caps?.hasTransport(
            android.net.NetworkCapabilities.TRANSPORT_CELLULAR
        ) == true

        wifiStatusText.text = if (wifiConnected) "WiFi: ✅ Connected" else "WiFi: ❌ Not connected"
        cellularStatusText.text = if (cellularConnected) "Cellular: ✅ Connected" else "Cellular: ❌ Not connected"

        val running = isServiceRunning()
        val lastError = MultiPathVpnService.lastError

        startStopButton.text = if (running) "STOP VPN" else "START VPN"

        statusText.text = when {
            lastError != null && !running -> {
                "MultiPath VPN ERROR\n$lastError\nTap START to retry"
            }
            running -> {
                "MultiPath VPN is ACTIVE\n" +
                "Using: ${if (wifiConnected) "WiFi" else ""}" +
                "${if (wifiConnected && cellularConnected) " + " else ""}" +
                "${if (cellularConnected) "Cellular" else ""}"
            }
            else -> {
                "MultiPath VPN is stopped\nTap START to begin"
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        return MultiPathVpnService.isVpnActive
    }

    private fun updateStrategy(strategy: NetworkMonitor.RoutingStrategy) {
        // This would communicate with the service via a bound service or broadcast
        val intent = Intent("com.multipathvpn.UPDATE_STRATEGY").apply {
            putExtra("strategy", strategy.name)
        }
        sendBroadcast(intent)
    }
}
