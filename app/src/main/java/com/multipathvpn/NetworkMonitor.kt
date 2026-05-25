package com.multipathvpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Monitors active network interfaces (WiFi and Cellular) on the device.
 * Provides the current state of each network and helps select
 * which network to route new connections through.
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Network objects for binding sockets
    private val wifiNetwork = AtomicReference<Network?>(null)
    private val cellularNetwork = AtomicReference<Network?>(null)

    // Network capabilities cache
    private val wifiCapabilities = AtomicReference<NetworkCapabilities?>(null)
    private val cellularCapabilities = AtomicReference<NetworkCapabilities?>(null)

    // Connection state
    val isWifiConnected = AtomicBoolean(false)
    val isCellularConnected = AtomicBoolean(false)

    // Routing strategy
    private var routingStrategy = RoutingStrategy.ROUND_ROBIN
    private var roundRobinCounter = 0

    enum class RoutingStrategy {
        /** Alternate between WiFi and Cellular for each new connection */
        ROUND_ROBIN,

        /** Prefer WiFi, use Cellular only when WiFi drops */
        WIFI_PREFERRED,

        /** Prefer Cellular, use WiFi only when Cellular drops */
        CELLULAR_PREFERRED,

        /** Always use both — send UDP on Cellular, TCP on WiFi (and vice versa) */
        BALANCED
    }

    val strategyLabel: String
        get() = when (routingStrategy) {
            RoutingStrategy.ROUND_ROBIN -> "Round Robin ⚖️"
            RoutingStrategy.WIFI_PREFERRED -> "WiFi Preferred 📶"
            RoutingStrategy.CELLULAR_PREFERRED -> "Cellular Preferred 📱"
            RoutingStrategy.BALANCED -> "Balanced 🔀"
        }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    fun startMonitoring() {
        try {
            stopMonitoring()
            updateCurrentNetworkState()

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateCurrentNetworkState()
                }

                override fun onLost(network: Network) {
                    updateCurrentNetworkState()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    trackNetwork(network, networkCapabilities)
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) {
                    // Not needed for routing decisions
                }
            }

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            android.util.Log.e("NetworkMonitor", "Failed to start monitoring", e)
        }
    }

    fun stopMonitoring() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
            networkCallback = null
        }
    }

    // ──────────────────────────────────────────────
    // Network State
    // ──────────────────────────────────────────────

    private fun updateCurrentNetworkState() {
        // Iterate all available networks and track their state
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            trackNetwork(network, caps)
        }
    }

    private fun trackNetwork(network: Network, caps: NetworkCapabilities) {
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                wifiNetwork.set(network)
                wifiCapabilities.set(caps)
                isWifiConnected.set(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                cellularNetwork.set(network)
                cellularCapabilities.set(caps)
                isCellularConnected.set(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
    }

    // ──────────────────────────────────────────────
    // Network Selection
    // ──────────────────────────────────────────────

    /**
     * Select the best network for a new TCP connection.
     */
    fun selectNetworkForNewConnection(): Network? {
        val wifi = wifiNetwork.get()
        val cellular = cellularNetwork.get()
        val wifiUp = isWifiConnected.get() && wifi != null
        val cellularUp = isCellularConnected.get() && cellular != null

        return when {
            !wifiUp && !cellularUp -> null  // No internet available
            !wifiUp -> cellular
            !cellularUp -> wifi
            else -> {
                // Both are up — apply strategy
                when (routingStrategy) {
                    RoutingStrategy.WIFI_PREFERRED -> wifi
                    RoutingStrategy.CELLULAR_PREFERRED -> cellular
                    RoutingStrategy.ROUND_ROBIN -> {
                        val selected = if (roundRobinCounter % 2 == 0) wifi else cellular
                        roundRobinCounter++
                        selected
                    }
                    RoutingStrategy.BALANCED -> {
                        // Alternate based on some heuristic
                        if (roundRobinCounter++ % 2 == 0) wifi else cellular
                    }
                }
            }
        }
    }

    /**
     * Select the best network for a UDP flow.
     * For UDP (audio/video streaming), we prefer the network with better
     * observed quality. For now, round-robin.
     */
    fun selectNetworkForUdp(): Network? {
        // UDP is more sensitive to jitter/latency.
        // We'll prefer Cellular for real-time UDP (less crowded than train WiFi)
        val cellular = cellularNetwork.get()
        val wifi = wifiNetwork.get()
        val cellularUp = isCellularConnected.get() && cellular != null
        val wifiUp = isWifiConnected.get() && wifi != null

        return when {
            cellularUp && wifiUp -> {
                // For real-time traffic, prefer the network with lower expected latency
                // Cellular typically has more consistent latency on a moving train
                if (roundRobinCounter++ % 3 == 0) wifi else cellular
            }
            cellularUp -> cellular
            wifiUp -> wifi
            else -> null
        }
    }

    /**
     * Get the WiFi Network object (for binding sockets).
     */
    fun getWifiNetwork(): Network? = wifiNetwork.get()

    /**
     * Get the Cellular Network object (for binding sockets).
     */
    fun getCellularNetwork(): Network? = cellularNetwork.get()

    /**
     * Set routing strategy.
     */
    fun setStrategy(strategy: RoutingStrategy) {
        routingStrategy = strategy
    }

    /**
     * Returns the WiFi signal strength as a percentage (0-100), or -1 if unknown.
     */
    fun getWifiSignalStrength(): Int {
        return wifiManager?.let { mgr ->
            val info = mgr.connectionInfo
            if (info != null) {
                // RSSI is typically in the range -100 to -55
                val rssi = info.rssi
                if (rssi == Int.MIN_VALUE || rssi == -127) return@let -1
                // Scale -100..-55 → 0..100
                ((rssi + 100) * 100 / 45).coerceIn(0, 100)
            } else -1
        } ?: -1
    }

    /**
     * Returns the cellular signal strength as a percentage (0-100), or -1 if unknown.
     */
    fun getCellularSignalStrength(): Int {
        val caps = cellularCapabilities.get() ?: return -1
        // NetworkCapabilities doesn't expose signal strength directly
        // This would need TelephonyManager for accurate reading
        return -1
    }
}
