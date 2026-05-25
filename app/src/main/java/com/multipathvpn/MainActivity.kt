package com.multipathvpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var wifiText: TextView
    private lateinit var cellText: TextView
    private lateinit var errorText: TextView
    private lateinit var errorCard: MaterialCardView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                doStartService()
            } else {
                statusText.text = "VPN permission denied"
            }
        } catch (e: Exception) {
            showError("Callback crash: ${e::class.simpleName}: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install crash reporter (catches unhandled exceptions)
        CrashReporter.install(applicationContext)

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        toggleButton = findViewById(R.id.start_stop_button)
        wifiText = findViewById(R.id.wifi_status)
        cellText = findViewById(R.id.cellular_status)
        errorText = findViewById(R.id.error_text)
        errorCard = findViewById(R.id.error_card)

        // Show any crash from a previous run
        CrashReporter.getSavedCrash(this)?.let { crash ->
            showError("Previous crash:\n$crash")
        }

        toggleButton.setOnClickListener {
            errorCard.visibility = View.GONE
            try {
                if (MultiPathVpnService.isVpnActive) {
                    statusText.text = "Stopping VPN..."
                    stopService(Intent(this, MultiPathVpnService::class.java))
                } else {
                    statusText.text = "Preparing VPN..."
                    val intent = VpnService.prepare(this)
                    if (intent != null) {
                        statusText.text = "Requesting permission..."
                        permLauncher.launch(intent)
                    } else {
                        statusText.text = "Permission already granted, starting..."
                        doStartService()
                    }
                }
            } catch (e: Exception) {
                showError("Button crash: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateJob = scope.launch {
            while (isActive) {
                updateUi()
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        updateJob?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorCard.visibility = View.VISIBLE
    }

    private fun doStartService() {
        try {
            statusText.text = "Starting service..."
            startService(Intent(this, MultiPathVpnService::class.java).apply {
                action = MultiPathVpnService.ACTION_START
            })
            statusText.text = "Service start requested"
        } catch (e: Exception) {
            showError("StartService error: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun updateUi() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val cell = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true

            wifiText.text = if (wifi) "WiFi: ✅" else "WiFi: ❌"
            cellText.text = if (cell) "Cellular: ✅" else "Cellular: ❌"

            val active = MultiPathVpnService.isVpnActive
            val error = MultiPathVpnService.lastError

            toggleButton.text = if (active) "STOP VPN" else "START VPN"

            when {
                error != null && !active -> statusText.text = "SERVICE ERROR: $error"

                active -> {
                    if (statusText.text != "VPN is ACTIVE") {
                        statusText.text = "VPN is ACTIVE"
                    }
                }
            }
        } catch (e: Exception) {
            showError("UI update error: ${e.message}")
        }
    }
}
