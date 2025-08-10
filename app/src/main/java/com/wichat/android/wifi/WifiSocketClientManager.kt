package com.wichat.android.wifi

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.wichat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages Wifi P2P client operations, discovery, and client-side connections
 */
class WifiSocketClientManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: WifiConnectionTracker,
    private val permissionManager: WifiPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: WifiConnectionManagerDelegate?,
    private val wifiP2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) {

    companion object {
        private const val TAG = "WifiSocketClientManager"
        const val SERVER_PORT = 8888
        private const val CONNECTION_TIMEOUT = 5000
    }

    // State management
    private var isActive = false
    private var isDiscovering = false
    
    // Peer discovery listener
    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        handlePeerListUpdate(peerList)
    }

    fun start(): Boolean {
        Log.i(TAG, "=== STARTING WIFI CLIENT MANAGER ===")
        if (!permissionManager.hasWifiPermissions()) {
            Log.e(TAG, "Missing Wifi permissions in client manager")
            return false
        }
        isActive = true
        Log.i(TAG, "Starting WiFi P2P peer discovery...")
        startPeerDiscovery()
        Log.i(TAG, "=== WIFI CLIENT MANAGER STARTED ===")
        return true
    }

    fun stop() {
        isActive = false
        Log.i(TAG, "Wifi Socket client manager stopped")
    }

    /**
     * Handle peer discovery result and initiate connection if appropriate
     */
    fun handlePeerDiscovered(device: WifiP2pDevice) {
        val deviceAddress = device.deviceAddress

        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            return
        }

        if (!connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            Log.d(TAG, "Connection to $deviceAddress not allowed due to recent attempts")
            return
        }

        if (connectionTracker.isConnectionLimitReached()) {
            Log.d(TAG, "Connection limit reached (${powerManager.getMaxConnections()})")
            return
        }

        if (connectionTracker.addPendingConnection(deviceAddress)) {
            // The actual connection is initiated by a central manager
            // This just marks the intention
        }
    }

    /**
     * Called when connection info is available, and we are the client.
     */
    fun connectToGroupOwner(groupOwnerAddress: String, device: WifiP2pDevice) {
        if (!isActive) return

        connectionScope.launch {
            var socket: Socket? = null
            try {
                Log.d(TAG, "Client: Attempting to connect to group owner at $groupOwnerAddress")
                socket = Socket()
                socket.bind(null)
                socket.connect(InetSocketAddress(groupOwnerAddress, SERVER_PORT), CONNECTION_TIMEOUT)
                Log.i(TAG, "Client: Connection successful to $groupOwnerAddress")

                val deviceConn = WifiConnectionTracker.DeviceConnection(
                    device = device,
                    socket = socket,
                    isClient = true
                )
                connectionTracker.addDeviceConnection(device.deviceAddress, deviceConn)
                delegate?.onDeviceConnected(device)

                listenForPackets(socket, device)

            } catch (e: IOException) {
                Log.e(TAG, "Client: Connection failed for ${device.deviceAddress}", e)
                connectionTracker.cleanupDeviceConnection(device.deviceAddress)
                socket?.close()
            }
        }
    }

    private fun listenForPackets(socket: Socket, device: WifiP2pDevice) {
        connectionScope.launch {
            try {
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(1024)
                var bytes: Int

                while (isActive && socket.isConnected) {
                    bytes = inputStream.read(buffer)
                    if (bytes > 0) {
                        val receivedData = buffer.copyOf(bytes)
                        Log.i(TAG, "Client: Received packet from ${device.deviceAddress}, size: $bytes bytes")
                        val packet = BitchatPacket.fromBinaryData(receivedData)
                        if (packet != null) {
                            val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                            Log.d(TAG, "Client: Parsed packet type ${packet.type} from $peerID")
                            delegate?.onPacketReceived(packet, peerID, device)
                        } else {
                            Log.w(TAG, "Client: Failed to parse packet from ${device.deviceAddress}")
                        }
                    } else {
                        break
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Client: Error reading from socket for ${device.deviceAddress}", e)
            } finally {
                Log.i(TAG, "Client: Disconnected from ${device.deviceAddress}")
                connectionTracker.cleanupDeviceConnection(device.deviceAddress)
            }
        }
    }
    
    /**
     * Start WiFi P2P peer discovery
     */
    private fun startPeerDiscovery() {
        if (!isActive || isDiscovering) return
        
        try {
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isDiscovering = true
                    Log.d(TAG, "WiFi P2P peer discovery initiated successfully")
                    
                    // Request peer list to get current peers
                    wifiP2pManager.requestPeers(channel, peerListListener)
                    
                    // Schedule periodic rediscovery
                    schedulePeriodicDiscovery()
                }
                
                override fun onFailure(reasonCode: Int) {
                    Log.e(TAG, "WiFi P2P peer discovery failed with reason code: $reasonCode")
                    isDiscovering = false
                    
                    // Retry after a delay
                    connectionScope.launch {
                        delay(2000) // Faster retry for peer discovery
                        if (isActive) startPeerDiscovery()
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permissions for WiFi P2P discovery", e)
        }
    }
    
    /**
     * Handle peer list updates from WiFi P2P discovery
     */
    private fun handlePeerListUpdate(peerList: WifiP2pDeviceList) {
        Log.d(TAG, "Peer list updated: ${peerList.deviceList.size} peers found")
        
        for (device in peerList.deviceList) {
            Log.d(TAG, "Discovered peer: ${device.deviceName} (${device.deviceAddress})")
            handlePeerDiscovered(device)
        }
    }
    
    /**
     * Schedule periodic peer discovery to maintain peer list
     */
    private fun schedulePeriodicDiscovery() {
        connectionScope.launch {
            while (isActive) {
                delay(10000) // Rediscover every 10 seconds for faster peer detection
                if (isActive) {
                    try {
                        wifiP2pManager.requestPeers(channel, peerListListener)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Failed to request peers", e)
                    }
                }
            }
        }
    }
    
    fun restartDiscovery() {
        Log.d(TAG, "Restart discovery requested")
        isDiscovering = false
        startPeerDiscovery()
    }
    
    fun restartScanning() {
        Log.d(TAG, "Restart scanning requested")
        restartDiscovery()
    }
    
    fun onScanStateChanged(shouldScan: Boolean) {
        Log.d(TAG, "Scan state changed: $shouldScan")
        if (shouldScan && !isDiscovering) {
            startPeerDiscovery()
        }
    }
}
