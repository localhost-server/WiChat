package com.wichat.android.wifi

import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks all Wifi connections and handles cleanup
 */
class WifiConnectionTracker(
    private val connectionScope: CoroutineScope,
    private val powerManager: PowerManager
) {

    companion object {
        private const val TAG = "WifiConnectionTracker"
        private const val CONNECTION_RETRY_DELAY = 5000L
        private const val MAX_CONNECTION_ATTEMPTS = 3
        private const val CLEANUP_DELAY = 500L
        private const val CLEANUP_INTERVAL = 30000L // 30 seconds
    }

    // Connection tracking
    private val connectedDevices = ConcurrentHashMap<String, DeviceConnection>()
    val addressPeerMap = ConcurrentHashMap<String, String>()

    // RSSI tracking from scan results
    private val scanRSSI = ConcurrentHashMap<String, Int>()

    // Connection attempt tracking
    private val pendingConnections = ConcurrentHashMap<String, ConnectionAttempt>()

    // State management
    private var isActive = false

    /**
     * Consolidated device connection information
     */
    data class DeviceConnection(
        val device: WifiP2pDevice,
        val socket: Socket? = null, // WiFi socket connection
        val rssi: Int = Int.MIN_VALUE,
        val isClient: Boolean = false,
        val connectedAt: Long = System.currentTimeMillis()
    )

    /**
     * Connection attempt tracking
     */
    data class ConnectionAttempt(
        val attempts: Int,
        val lastAttempt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY * 2

        fun shouldRetry(): Boolean =
            attempts < MAX_CONNECTION_ATTEMPTS &&
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY
    }

    fun start() {
        isActive = true
        startPeriodicCleanup()
    }

    fun stop() {
        isActive = false
        cleanupAllConnections()
        clearAllConnections()
    }

    fun addDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        Log.d(TAG, "Tracker: Adding device connection for $deviceAddress (isClient: ${deviceConn.isClient}")
        connectedDevices[deviceAddress] = deviceConn
        pendingConnections.remove(deviceAddress)
    }

    fun getDeviceConnection(deviceAddress: String): DeviceConnection? {
        return connectedDevices[deviceAddress]
    }

    fun getConnectedDevices(): Map<String, DeviceConnection> {
        return connectedDevices.toMap()
    }

    fun getDeviceRSSI(deviceAddress: String): Int? {
        return connectedDevices[deviceAddress]?.rssi?.takeIf { it != Int.MIN_VALUE }
    }

    fun updateScanRSSI(deviceAddress: String, rssi: Int) {
        scanRSSI[deviceAddress] = rssi
    }

    fun getBestRSSI(deviceAddress: String): Int? {
        connectedDevices[deviceAddress]?.rssi?.takeIf { it != Int.MIN_VALUE }?.let { return it }
        return scanRSSI[deviceAddress]
    }

    fun isDeviceConnected(deviceAddress: String): Boolean {
        return connectedDevices.containsKey(deviceAddress)
    }

    fun isConnectionAttemptAllowed(deviceAddress: String): Boolean {
        val existingAttempt = pendingConnections[deviceAddress]
        return existingAttempt?.let {
            it.isExpired() || it.shouldRetry()
        } ?: true
    }

    fun addPendingConnection(deviceAddress: String): Boolean {
        Log.d(TAG, "Tracker: Adding pending connection for $deviceAddress")
        synchronized(pendingConnections) {
            val currentAttempt = pendingConnections[deviceAddress]
            if (currentAttempt != null && !currentAttempt.isExpired() && !currentAttempt.shouldRetry()) {
                Log.d(TAG, "Tracker: Connection attempt already in progress for $deviceAddress")
                return false
            }
            val attempts = (currentAttempt?.attempts ?: 0) + 1
            pendingConnections[deviceAddress] = ConnectionAttempt(attempts)
            Log.d(TAG, "Tracker: Added pending connection for $deviceAddress (attempts: $attempts)")
            return true
        }
    }

    fun removePendingConnection(deviceAddress: String) {
        pendingConnections.remove(deviceAddress)
    }

    fun getConnectedDeviceCount(): Int = connectedDevices.size

    fun isConnectionLimitReached(): Boolean {
        return connectedDevices.size >= powerManager.getMaxConnections()
    }

    fun enforceConnectionLimits() {
        val maxConnections = powerManager.getMaxConnections()
        if (connectedDevices.size > maxConnections) {
            Log.i(TAG, "Enforcing connection limit: ${connectedDevices.size} > $maxConnections")
            val sortedConnections = connectedDevices.values
                .filter { it.isClient }
                .sortedBy { it.connectedAt }
            val toDisconnect = sortedConnections.take(connectedDevices.size - maxConnections)
            toDisconnect.forEach { deviceConn ->
                Log.d(TAG, "Disconnecting ${deviceConn.device.deviceAddress} due to connection limit")
                deviceConn.socket?.close()
            }
        }
    }

    fun cleanupDeviceConnection(deviceAddress: String) {
        connectedDevices.remove(deviceAddress)?.let { deviceConn ->
            addressPeerMap.remove(deviceAddress)
            deviceConn.socket?.close()
        }
        pendingConnections.remove(deviceAddress)
        Log.d(TAG, "Cleaned up device connection for $deviceAddress")
    }

    private fun cleanupAllConnections() {
        connectedDevices.values.forEach { deviceConn ->
            try {
                deviceConn.socket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket during cleanup: ${e.message}")
            }
        }
    }

    private fun clearAllConnections() {
        connectedDevices.clear()
        addressPeerMap.clear()
        pendingConnections.clear()
        scanRSSI.clear()
    }

    private fun startPeriodicCleanup() {
        connectionScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                if (!isActive) break
                try {
                    val expiredConnections = pendingConnections.filter { it.value.isExpired() }
                    expiredConnections.keys.forEach { pendingConnections.remove(it) }
                    if (expiredConnections.isNotEmpty()) {
                        Log.d(TAG, "Cleaned up ${expiredConnections.size} expired connection attempts")
                    }
                    Log.d(TAG, "Periodic cleanup: ${connectedDevices.size} connections, ${pendingConnections.size} pending")
                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic cleanup: ${e.message}")
                }
            }
        }
    }

    /**
     * Get the IP address of a connected peer
     */
    fun getPeerAddress(peerID: String): String? {
        // Look through connected devices to find the one with matching peerID
        connectedDevices.forEach { (deviceAddress, deviceConnection) ->
            if (addressPeerMap[deviceAddress] == peerID) {
                // Return actual remote IP address from socket connection, not device address
                val socket = deviceConnection.socket
                val remoteAddress = socket?.remoteSocketAddress
                if (remoteAddress != null) {
                    // Extract IP address from SocketAddress (format: /192.168.1.13:port)
                    val ipAddress = remoteAddress.toString().substringAfter("/").substringBefore(":")
                    Log.d(TAG, "Resolved peer $peerID -> Remote IP: $ipAddress (device: $deviceAddress, socket: $remoteAddress)")
                    return ipAddress
                } else {
                    Log.w(TAG, "No remote socket address available for peer $peerID (device: $deviceAddress)")
                    return null
                }
            }
        }
        return null
    }
    
    fun getDebugInfo(): String {
        return buildString {
            appendLine("Connected Devices: ${connectedDevices.size} / ${powerManager.getMaxConnections()}")
            connectedDevices.forEach { (address, deviceConn) ->
                val age = (System.currentTimeMillis() - deviceConn.connectedAt) / 1000
                appendLine("  - $address (we're ${if (deviceConn.isClient) "client" else "server"}, ${age}s, RSSI: ${deviceConn.rssi})")
            }
            appendLine()
            appendLine("Pending Connections: ${pendingConnections.size}")
            val now = System.currentTimeMillis()
            pendingConnections.forEach { (address, attempt) ->
                val elapsed = (now - attempt.lastAttempt) / 1000
                appendLine("  - $address: ${attempt.attempts} attempts, last ${elapsed}s ago")
            }
            appendLine()
            appendLine("Scan RSSI Cache: ${scanRSSI.size}")
            scanRSSI.forEach { (address, rssi) ->
                appendLine("  - $address: $rssi dBm")
            }
        }
    }
}