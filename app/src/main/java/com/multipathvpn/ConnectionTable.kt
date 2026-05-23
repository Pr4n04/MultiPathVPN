package com.multipathvpn

import android.net.Network
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks all active connections and which network (WiFi or Cellular)
 * they are assigned to.
 *
 * Each connection is identified by a 5-tuple key:
 *   srcIp:srcPort:dstIp:dstPort:protocol
 */
class ConnectionTable {

    companion object {
        fun makeKey(
            srcIp: String, srcPort: Int,
            dstIp: String, dstPort: Int,
            protocol: Int = 6
        ): String = "$srcIp:$srcPort:$dstIp:$dstPort:$protocol"

        private fun makeReverseKey(
            dstIp: String, dstPort: Int,
            srcIp: String, srcPort: Int,
            protocol: Int = 6
        ): String = makeKey(dstIp, dstPort, srcIp, srcPort, protocol)
    }

    data class ConnectionEntry(
        val key: String,
        val reverseKey: String,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int,
        val network: Network,
        val timestamp: Long = System.currentTimeMillis(),
        val timeoutMs: Long = 0  // 0 = no timeout (TCP), >0 = UDP timeout
    ) {
        fun isExpired(): Boolean {
            if (timeoutMs <= 0) return false
            return (System.currentTimeMillis() - timestamp) > timeoutMs
        }
    }

    private val connections = ConcurrentHashMap<String, ConnectionEntry>()

    /**
     * Add a new connection to the table.
     */
    fun addConnection(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        protocol: Int,
        network: Network?,
        timeoutMs: Long = 0
    ): String {
        val key = makeKey(srcIp, srcPort, dstIp, dstPort, protocol)
        val revKey = makeReverseKey(srcIp, srcPort, dstIp, dstPort, protocol)

        if (network == null) return key

        connections[key] = ConnectionEntry(
            key = key,
            reverseKey = revKey,
            srcIp = srcIp,
            srcPort = srcPort,
            dstIp = dstIp,
            dstPort = dstPort,
            protocol = protocol,
            network = network,
            timeoutMs = timeoutMs
        )

        // Also store reverse mapping for response packets
        connections[revKey] = ConnectionEntry(
            key = revKey,
            reverseKey = key,
            srcIp = dstIp,
            srcPort = dstPort,
            dstIp = srcIp,
            dstPort = srcPort,
            protocol = protocol,
            network = network,
            timeoutMs = timeoutMs
        )

        return key
    }

    /**
     * Remove a connection from the table.
     */
    fun removeConnection(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        protocol: Int = 6
    ) {
        val key = makeKey(srcIp, srcPort, dstIp, dstPort, protocol)
        val revKey = makeReverseKey(srcIp, srcPort, dstIp, dstPort, protocol)
        connections.remove(key)
        connections.remove(revKey)
    }

    /**
     * Get the assigned network for a connection by key.
     */
    fun getNetwork(key: String): Network? {
        val entry = connections[key] ?: return null
        if (entry.isExpired()) {
            connections.remove(key)
            connections.remove(entry.reverseKey)
            return null
        }
        return entry.network
    }

    /**
     * Get the full connection entry by key.
     */
    fun getEntry(key: String): ConnectionEntry? {
        val entry = connections[key] ?: return null
        if (entry.isExpired()) {
            connections.remove(key)
            connections.remove(entry.reverseKey)
            return null
        }
        return entry
    }

    /**
     * Move all connections from one network to another (for failover).
     * When WiFi drops, all WiFi connections move to Cellular.
     */
    fun migrateConnections(fromNetwork: Network?, toNetwork: Network?) {
        if (toNetwork == null) return
        val iterator = connections.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.network == fromNetwork) {
                entry.setValue(entry.value.copy(network = toNetwork))
            }
        }
    }

    /**
     * Remove all expired entries.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val iterator = connections.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
            }
        }
    }

    /**
     * Clear all connections.
     */
    fun clear() {
        connections.clear()
    }

    /**
     * Get the number of active connections.
     */
    fun size(): Int {
        cleanup()
        return connections.size / 2  // Divide by 2 because we store forward + reverse
    }

    /**
     * Get all active connections.
     */
    fun getAllEntries(): List<ConnectionEntry> {
        cleanup()
        val seen = mutableSetOf<String>()
        return connections.values.filter { entry ->
            // Only return forward entries (avoid duplicates)
            val isForward = !seen.contains(entry.reverseKey)
            seen.add(entry.key)
            isForward
        }
    }
}
