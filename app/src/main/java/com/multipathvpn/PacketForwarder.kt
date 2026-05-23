package com.multipathvpn

import android.content.Context
import android.net.Network
import java.io.FileOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles forwarding of IP packets between the TUN interface and
 * real network interfaces (WiFi and Cellular).
 *
 * For TCP: Creates real TCP connections to the destination,
 * bound to the assigned network, and relays data.
 *
 * For UDP: Creates UDP sockets bound to the assigned network.
 *
 * For other protocols (ICMP): Simply records and drops (not supported).
 */
class PacketForwarder(
    private val context: Context,
    private val connectionTable: ConnectionTable,
    private val networkMonitor: NetworkMonitor
) {

    // Active TCP relays
    private val tcpRelays = ConcurrentHashMap<String, TcpRelay>()

    // Active UDP relays
    private val udpRelays = ConcurrentHashMap<String, UdpRelay>()

    // ──────────────────────────────────────────────
    // Raw Packet Forwarding (IP-level)
    // ──────────────────────────────────────────────

    /**
     * Forward a raw IP packet. This handles the general case.
     * For TCP/UDP, we create proper relays for data forwarding.
     * The raw packet is primarily used to establish or maintain state.
     */
    fun forwardRawPacket(
        packetData: ByteArray,
        tunOutput: FileOutputStream,
        preferredNetwork: Network? = null
    ) {
        val byteBuf = ByteBuffer.wrap(packetData)
        val ipHeaderLen = (byteBuf.get(0).toInt() and 0x0F) * 4
        val totalLength = byteBuf.getShort(2).toInt() and 0xFFFF
        val protocol = byteBuf.get(9).toInt() and 0xFF

        val srcIp = inet4FromBytes(byteBuf, 12)
        val dstIp = inet4FromBytes(byteBuf, 16)

        if (ipHeaderLen + (if (protocol == 6) 20 else 8) > totalLength) {
            // Malformed packet
            return
        }

        when (protocol) {
            6 -> { // TCP
                val srcPort = byteBuf.getShort(ipHeaderLen).toInt() and 0xFFFF
                val dstPort = byteBuf.getShort(ipHeaderLen + 2).toInt() and 0xFFFF
                val flags = byteBuf.get(ipHeaderLen + 13).toInt() and 0xFF
                val isSyn = (flags and 0x02) != 0
                val isRst = (flags and 0x04) != 0
                val isFin = (flags and 0x01) != 0

                val connKey = ConnectionTable.makeKey(srcIp, srcPort, dstIp, dstPort)

                if (isSyn && !isRst) {
                    // New connection — start a TCP relay
                    val network = preferredNetwork ?: networkMonitor.selectNetworkForNewConnection()
                    if (network != null) {
                        startTcpRelay(connKey, srcIp, srcPort, dstIp, dstPort, network, tunOutput)
                    }
                } else if (isFin || isRst) {
                    // Connection closing — stop relay
                    stopTcpRelay(connKey)
                } else {
                    // Existing connection — ensure relay is active
                    val relay = tcpRelays[connKey]
                    if (relay == null && preferredNetwork != null) {
                        // SYN-ACK or data packet for an existing connection we missed
                        // This can happen if the app started before the VPN
                        startTcpRelay(connKey, srcIp, srcPort, dstIp, dstPort, preferredNetwork, tunOutput)
                    }
                }
            }
            17 -> { // UDP
                val srcPort = byteBuf.getShort(ipHeaderLen).toInt() and 0xFFFF
                val dstPort = byteBuf.getShort(ipHeaderLen + 2).toInt() and 0xFFFF
                val connKey = ConnectionTable.makeKey(srcIp, srcPort, dstIp, dstPort, 17)

                val network = preferredNetwork ?: networkMonitor.selectNetworkForUdp()
                if (network != null) {
                    forwardUdpPacket(connKey, packetData, srcIp, srcPort, dstIp, dstPort, network, tunOutput)
                }
            }
            else -> {
                // ICMP or other — drop (we don't forward these)
                // ICMP echo requests would need raw sockets which require root
            }
        }
    }

    // ──────────────────────────────────────────────
    // TCP Relay
    // ──────────────────────────────────────────────

    /**
     * Start a TCP relay: creates a real TCP connection to the destination
     * server, bound to the specified network, and relays data in both directions.
     */
    private fun startTcpRelay(
        connKey: String,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        network: Network,
        tunOutput: FileOutputStream
    ) {
        if (tcpRelays.containsKey(connKey)) return

        val relay = TcpRelay(
            connKey = connKey,
            srcIp = srcIp, srcPort = srcPort,
            dstIp = dstIp, dstPort = dstPort,
            network = network,
            tunOutput = tunOutput,
            onStopped = { key -> tcpRelays.remove(key) }
        )

        tcpRelays[connKey] = relay
        relay.start()
    }

    private fun stopTcpRelay(connKey: String) {
        tcpRelays.remove(connKey)?.stop()
        connectionTable.removeConnection(
            connKey.split(":")[0], connKey.split(":")[1].toInt(),
            connKey.split(":")[2], connKey.split(":")[3].toInt()
        )
    }

    // ──────────────────────────────────────────────
    // UDP Forwarding
    // ──────────────────────────────────────────────

    /**
     * Forward a UDP packet.
     * For UDP, we create a DatagramSocket bound to the specified network
     * and send/receive packets.
     */
    private fun forwardUdpPacket(
        connKey: String,
        packetData: ByteArray,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        network: Network,
        tunOutput: FileOutputStream
    ) {
        var relay = udpRelays[connKey]
        if (relay == null) {
            relay = UdpRelay(
                connKey = connKey,
                network = network,
                tunOutput = tunOutput,
                onStopped = { key -> udpRelays.remove(key) }
            )
            udpRelays[connKey] = relay
        }

        relay.sendPacket(packetData, srcIp, srcPort, dstIp, dstPort)
    }

    // ──────────────────────────────────────────────
    // Shutdown
    // ──────────────────────────────────────────────

    fun shutdown() {
        tcpRelays.values.forEach { it.stop() }
        tcpRelays.clear()
        udpRelays.values.forEach { it.stop() }
        udpRelays.clear()
    }

    // ──────────────────────────────────────────────
    // TCP Relay Inner Class
    // ──────────────────────────────────────────────

    class TcpRelay(
        private val connKey: String,
        private val srcIp: String,
        private val srcPort: Int,
        private val dstIp: String,
        private val dstPort: Int,
        private val network: Network,
        private val tunOutput: FileOutputStream,
        private val onStopped: (String) -> Unit
    ) {
        private var thread: Thread? = null
        private var running = false

        fun start() {
            running = true
            thread = Thread({
                try {
                    val destAddr = InetAddress.getByName(dstIp)

                    // Create a socket bound to the specified network
                    val socket = Socket()
                    network.bindSocket(socket)

                    // Set timeouts so we don't hang forever
                    socket.connect(InetSocketAddress(destAddr, dstPort), 10000)
                    socket.soTimeout = 30000

                    val remoteInput = socket.getInputStream()
                    val remoteOutput = socket.getOutputStream()

                    // Read from remote, write to TUN
                    val readBuf = ByteArray(4096)
                    while (running) {
                        val len = try {
                            remoteInput.read(readBuf)
                        } catch (e: SocketTimeoutException) {
                            continue
                        } catch (e: Exception) {
                            break
                        }

                        if (len <= 0) break

                        // Wrap data in IP header and write to TUN
                        val responsePacket = buildIpPacket(
                            dstIp = srcIp,
                            srcIp = dstIp,
                            dstPort = srcPort,
                            srcPort = dstPort,
                            payload = readBuf.copyOf(len),
                            protocol = 6
                        )

                        try {
                            tunOutput.write(responsePacket)
                            tunOutput.flush()
                        } catch (e: Exception) {
                            break
                        }
                    }

                    socket.close()
                } catch (e: Exception) {
                    // Connection failed or was interrupted — normal during failover
                } finally {
                    running = false
                    onStopped(connKey)
                }
            }, "TCP-Relay-$connKey")
            thread?.start()
        }

        fun stop() {
            running = false
            thread?.interrupt()
            thread = null
        }
    }

    // ──────────────────────────────────────────────
    // UDP Relay Inner Class
    // ──────────────────────────────────────────────

    class UdpRelay(
        private val connKey: String,
        private val network: Network,
        private val tunOutput: FileOutputStream,
        private val onStopped: (String) -> Unit
    ) {
        private var socket: DatagramSocket? = null
        private var thread: Thread? = null
        private var running = false
        private var lastActivity = System.currentTimeMillis()
        private val timeout = 30_000L  // 30 seconds idle timeout

        fun sendPacket(
            packetData: ByteArray,
            srcIp: String, srcPort: Int,
            dstIp: String, dstPort: Int
        ) {
            try {
                if (socket == null) {
                    socket = DatagramSocket()
                    network.bindSocket(socket!!)
                    socket!!.soTimeout = 5000
                    startReceiver(srcIp, srcPort)
                }

                lastActivity = System.currentTimeMillis()
                val destAddr = InetAddress.getByName(dstIp)
                val payload = extractUdpPayload(packetData)
                if (payload != null) {
                    val packet = DatagramPacket(payload, payload.size, destAddr, dstPort)
                    socket!!.send(packet)
                }
            } catch (e: Exception) {
                // Socket error
            }
        }

        private fun startReceiver(srcIp: String, srcPort: Int) {
            running = true
            thread = Thread({
                val buf = ByteArray(4096)
                while (running) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket!!.receive(packet)

                        // Build IP packet and write to TUN
                        val responsePacket = buildIpPacket(
                            dstIp = srcIp,
                            srcIp = packet.address.hostAddress ?: "0.0.0.0",
                            dstPort = srcPort,
                            srcPort = packet.port,
                            payload = packet.data.copyOf(packet.length),
                            protocol = 17
                        )

                        try {
                            tunOutput.write(responsePacket)
                            tunOutput.flush()
                        } catch (e: Exception) {
                            break
                        }

                        lastActivity = System.currentTimeMillis()
                    } catch (e: SocketTimeoutException) {
                        // Check for idle timeout
                        if (System.currentTimeMillis() - lastActivity > timeout) {
                            break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
                stop()
            }, "UDP-Relay-$connKey")
            thread?.start()
        }

        fun stop() {
            running = false
            socket?.close()
            socket = null
            thread?.interrupt()
            thread = null
            onStopped(connKey)
        }
    }

    // ──────────────────────────────────────────────
    // Packet Construction
    // ──────────────────────────────────────────────

    companion object PacketBuilder {
        fun inet4FromBytes(buf: ByteBuffer, offset: Int): String {
            return "${buf.get(offset).toInt() and 0xFF}." +
                    "${buf.get(offset + 1).toInt() and 0xFF}." +
                    "${buf.get(offset + 2).toInt() and 0xFF}." +
                    "${buf.get(offset + 3).toInt() and 0xFF}"
        }

        /**
         * Build a minimal IPv4 + TCP/UDP response packet to write to the TUN interface.
         * This creates a response packet from the destination back to the original source.
         */
        fun buildIpPacket(
            dstIp: String,
            srcIp: String,
            dstPort: Int,
            srcPort: Int,
            payload: ByteArray,
            protocol: Int
        ): ByteArray {
            val ipHeaderLen = 20
            val transportHeaderLen = if (protocol == 6) 20 else 8
            val totalLen = ipHeaderLen + transportHeaderLen + payload.size
            val buf = ByteBuffer.allocate(totalLen)

            // IPv4 header
            buf.put(0x45.toByte())  // Version 4, IHL 5
            buf.put(0x00.toByte())  // DSCP + ECN
            buf.putShort(totalLen.toShort())  // Total length
            buf.putInt(0)  // ID + flags + fragment offset
            buf.put(64.toByte())  // TTL
            buf.put(protocol.toByte())  // Protocol (6=TCP, 17=UDP)
            buf.putShort(0)  // Header checksum (will fill later)
            buf.put(inet4ToBytes(srcIp))  // Source IP
            buf.put(inet4ToBytes(dstIp))  // Destination IP

            // Calculate IP checksum
            val ipChecksum = calculateChecksum(buf.array(), 0, ipHeaderLen)
            buf.putShort(10, ipChecksum)

            if (protocol == 6) {
                // TCP header (minimal)
                buf.putShort(dstPort.toShort())  // Source port (from destination)
                buf.putShort(srcPort.toShort())  // Dest port (original source)
                buf.putInt(0)  // SEQ (simplified)
                buf.putInt(0)  // ACK (simplified)
                buf.put(0x50.toByte())  // Data offset 5, reserved
                buf.put(0x18.toByte())  // Flags: PSH + ACK
                buf.putShort(65535.toShort())  // Window size
                buf.putShort(0)  // Checksum (will fill later)
                buf.putShort(0)  // Urgent pointer
            } else {
                // UDP header
                buf.putShort(dstPort.toShort())
                buf.putShort(srcPort.toShort())
                val udpLen = 8 + payload.size
                buf.putShort(udpLen.toShort())
                buf.putShort(0)  // UDP checksum (optional, set to 0)
            }

            // Payload
            buf.put(payload)

            // Calculate TCP checksum (pseudo-header + TCP segment)
            if (protocol == 6) {
                val tcpChecksum = calculateTcpChecksum(
                    buf.array(), ipHeaderLen, totalLen,
                    inet4ToBytes(srcIp), inet4ToBytes(dstIp)
                )
                buf.putShort(ipHeaderLen + 16, tcpChecksum)
            }

            return buf.array()
        }

        private fun inet4ToBytes(ip: String): ByteArray {
            val parts = ip.split(".")
            return byteArrayOf(
                parts[0].toInt().toByte(),
                parts[1].toInt().toByte(),
                parts[2].toInt().toByte(),
                parts[3].toInt().toByte()
            )
        }

        fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Short {
            var sum = 0L
            var i = offset
            while (i < offset + length - 1) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < offset + length) {
                sum += (data[i].toInt() and 0xFF) shl 8
            }
            while (sum shr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFF).toShort()
        }

        private fun calculateTcpChecksum(
            packet: ByteArray,
            tcpOffset: Int,
            totalLen: Int,
            srcIp: ByteArray,
            dstIp: ByteArray
        ): Short {
            val tcpLen = totalLen - tcpOffset
            val pseudoLen = 12 + tcpLen
            val pseudo = ByteArray(pseudoLen)

            // Pseudo header
            System.arraycopy(srcIp, 0, pseudo, 0, 4)
            System.arraycopy(dstIp, 0, pseudo, 4, 4)
            pseudo[8] = 0
            pseudo[9] = 6  // TCP protocol
            pseudo[10] = ((tcpLen shr 8) and 0xFF).toByte()
            pseudo[11] = (tcpLen and 0xFF).toByte()
            System.arraycopy(packet, tcpOffset, pseudo, 12, tcpLen)

            return calculateChecksum(pseudo, 0, pseudoLen)
        }

        /**
         * Extract UDP payload from a raw IP packet.
         */
        fun extractUdpPayload(packetData: ByteArray): ByteArray? {
            val byteBuf = ByteBuffer.wrap(packetData)
            val ipHeaderLen = (byteBuf.get(0).toInt() and 0x0F) * 4
            val totalLength = byteBuf.getShort(2).toInt() and 0xFFFF

            if (totalLength <= ipHeaderLen + 8) return null

            val udpHeaderLen = 8
            val payloadStart = ipHeaderLen + udpHeaderLen
            val payloadLen = totalLength - payloadStart
            return packetData.copyOfRange(payloadStart, totalLength)
        }
    }
}
