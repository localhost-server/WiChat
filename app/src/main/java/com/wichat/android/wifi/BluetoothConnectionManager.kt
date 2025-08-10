package com.wichat.android.wifi

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import com.wichat.android.model.RoutedPacket
import com.wichat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import java.net.SocketTimeoutException

/**
 * Power-optimized Wifi connection manager with comprehensive memory management
 * Integrates with PowerManager for adaptive power consumption
 * Coordinates smaller, focused components for better maintainability
 */
class WifiConnectionManager(
    private val context: Context, 
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) : PowerManagerDelegate {
    
    companion object {
        private const val TAG = "WifiConnectionManager"
    }
    
    // Core Wifi components
    
    // Power management
    private val powerManager = PowerManager(context)
    
    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Component managers
    private val permissionManager = WifiPermissionManager(context)
    private val connectionTracker = WifiConnectionTracker(connectionScope, powerManager)
    private val packetBroadcaster = WifiPacketBroadcaster(connectionScope, connectionTracker, fragmentManager)
    
    // Local network scanner for same WiFi network discovery
    private val localNetworkScanner = LocalNetworkScanner(context, myPeerID) { peerID, address ->
        Log.d(TAG, "Local network peer discovered: $peerID at $address")
        // Directly connect to discovered peer instead of just marking as discovered
        val ipAddress = address.hostAddress ?: address.toString()
        connectToDiscoveredPeer(peerID, ipAddress)
    }
    
    // Simple network discovery as fallback
    private val simpleNetworkDiscovery = SimpleNetworkDiscovery(context, myPeerID) { peerID, ipAddress ->
        Log.d(TAG, "Simple network peer discovered: $peerID at $ipAddress")
        // Directly connect to discovered peer
        connectToDiscoveredPeer(peerID, ipAddress)
    }
    
    // Delegate for component managers to call back to main manager
    private val componentDelegate = object : WifiConnectionManagerDelegate {
        override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: WifiP2pDevice?) {
            Log.d(TAG, "onPacketReceived: Packet received from ${device?.deviceAddress} ($peerID)")
            device?.let { wifiDevice ->
                // if connection does not have a peerID yet, we assume that the first package
                // we receive from that connection is from the peer
                if (!connectionTracker.addressPeerMap.containsKey(device.deviceAddress)) {
                    Log.d(TAG, "First packet received from new device: ${wifiDevice.deviceAddress}, assuming peerID: $peerID")
                    connectionTracker.addressPeerMap[device.deviceAddress] = peerID
                }
                // Get current RSSI for this device and update if available
                val currentRSSI = connectionTracker.getBestRSSI(wifiDevice.deviceAddress)
                if (currentRSSI != null) {
                    delegate?.onRSSIUpdated(wifiDevice.deviceAddress, currentRSSI)
                }
            }

            if (peerID == myPeerID) return // Ignore messages from self

            delegate?.onPacketReceived(packet, peerID, device)
        }
        
        override fun onDeviceConnected(device: WifiP2pDevice) {
            delegate?.onDeviceConnected(device)
        }
        
        override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
            delegate?.onRSSIUpdated(deviceAddress, rssi)
        }
    }
    
    // WiFi P2P components
    private val wifiP2pManager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private val channel: WifiP2pManager.Channel by lazy {
        wifiP2pManager.initialize(context, context.mainLooper, null)
    }
    
    private val serverManager = WifiSocketServerManager(
        context, connectionScope, connectionTracker, permissionManager, componentDelegate
    )
    private val clientManager = WifiSocketClientManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, wifiP2pManager, channel
    )
    
    // Service state
    private var isActive = false
    
    // Delegate for callbacks
    var delegate: WifiConnectionManagerDelegate? = null
    
    // Public property for address-peer mapping
    val addressPeerMap get() = connectionTracker.addressPeerMap
    
    init {
        powerManager.delegate = this
    }
    
    /**
     * Start all Wifi services with power optimization
     */
    fun startServices(): Boolean {
        Log.i(TAG, "=== STARTING WIFI CONNECTION MANAGER ===")
        Log.i(TAG, "Checking WiFi permissions...")
        
        if (!permissionManager.hasWifiPermissions()) {
            Log.e(TAG, "=== MISSING WIFI PERMISSIONS ===")
            Log.e(TAG, "Required: ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, INTERNET, ACCESS_FINE_LOCATION")
            return false
        }
        
        Log.i(TAG, "WiFi permissions OK, starting services...")
        
        try {
            isActive = true

        // set the adapter's name to our 8-character peerID for iOS privacy, TODO: Make this configurable
        // try {
        //     if (wifiAdapter?.name != myPeerID) {
        //         wifiAdapter?.name = myPeerID
        //         Log.d(TAG, "Set Wifi adapter name to peerID: $myPeerID for iOS compatibility.")
        //     }
        // } catch (se: SecurityException) {
        //     Log.e(TAG, "Missing WIFI_CONNECT permission to set adapter name.", se)
        // }

            // Start all component managers
            connectionScope.launch {
                // Start connection tracker first
                connectionTracker.start()
                
                // Start power manager
                powerManager.start()
                
                // Start server manager
                if (!serverManager.start()) {
                    Log.e(TAG, "Failed to start server manager")
                    this@WifiConnectionManager.isActive = false
                    return@launch
                }
                
                // Start client manager
                if (!clientManager.start()) {
                    Log.e(TAG, "Failed to start client manager")
                    this@WifiConnectionManager.isActive = false
                    return@launch
                }
                
                // Start local network scanner
                localNetworkScanner.start()
                
                // Start simple network discovery as fallback
                simpleNetworkDiscovery.start()
                
                Log.i(TAG, "(WiFi P2P + UDP + TCP discovery active)")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Wifi services: ${e.message}")
            isActive = false
            return false
        }
    }
    
    /**
     * Stop all Wifi services with proper cleanup
     */
    fun stopServices() {
        Log.i(TAG, "Stopping power-optimized Wifi services")
        
        isActive = false
        
        connectionScope.launch {
            // Stop component managers
            clientManager.stop()
            serverManager.stop()
            localNetworkScanner.stop()
            simpleNetworkDiscovery.stop()
            
            // Stop power manager
            powerManager.stop()
            
            // Stop connection tracker
            connectionTracker.stop()
            
            // Cancel the coroutine scope
            connectionScope.cancel()
            
            Log.i(TAG, "All Wifi services stopped")
        }
    }
    
    /**
     * Set app background state for power optimization
     */
    fun setAppBackgroundState(inBackground: Boolean) {
        powerManager.setAppBackgroundState(inBackground)
    }

    /**
     * Broadcast packet to connected devices with connection limit enforcement
     * Automatically fragments large packets to fit within Wifi MTU limits
     */
    fun broadcastPacket(routed: RoutedPacket) {
        if (!isActive) return
        
        packetBroadcaster.broadcastPacket(routed)
    }
    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectionTracker.getConnectedDeviceCount()
    
    /**
     * Get debug information including power management
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Wifi Connection Manager ===")
            appendLine("Active: $isActive")
            appendLine("Has Permissions: ${permissionManager.hasWifiPermissions()}")
            appendLine("Socket Server Active: ${serverManager.getSocketServer() != null}")
            appendLine()
            appendLine(powerManager.getPowerInfo())
            appendLine()
            appendLine(connectionTracker.getDebugInfo())
        }
    }
    
    // MARK: - PowerManagerDelegate Implementation
    
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")
        
        connectionScope.launch {
            // Avoid rapid scan restarts by checking if we need to change scan behavior
            val wasUsingDutyCycle = powerManager.shouldUseDutyCycle()
            
            // Update advertising with new power settings
            serverManager.restartAdvertising()
            
            // Only restart scanning if the duty cycle behavior changed
            val nowUsingDutyCycle = powerManager.shouldUseDutyCycle()
            if (wasUsingDutyCycle != nowUsingDutyCycle) {
                Log.d(TAG, "Duty cycle behavior changed (${wasUsingDutyCycle} -> ${nowUsingDutyCycle}), restarting scan")
                clientManager.restartScanning()
            } else {
                Log.d(TAG, "Duty cycle behavior unchanged, keeping existing scan state")
            }
            
            // Enforce connection limits
            connectionTracker.enforceConnectionLimits()
        }
    }
    
    override fun onScanStateChanged(shouldScan: Boolean) {
        clientManager.onScanStateChanged(shouldScan)
    }
    
    /**
     * Create a mock WiFi P2P device for local network discovered peers
     */
    private fun createMockWifiP2pDevice(peerID: String, ipAddress: String): WifiP2pDevice {
        val device = WifiP2pDevice()
        // Use reflection to set the fields since WifiP2pDevice constructor is package-private
        try {
            val deviceNameField = WifiP2pDevice::class.java.getDeclaredField("deviceName")
            deviceNameField.isAccessible = true
            deviceNameField.set(device, "bitchat-$peerID")
            
            val deviceAddressField = WifiP2pDevice::class.java.getDeclaredField("deviceAddress")
            deviceAddressField.isAccessible = true
            deviceAddressField.set(device, ipAddress)
            
            val statusField = WifiP2pDevice::class.java.getDeclaredField("status")
            statusField.isAccessible = true
            statusField.set(device, WifiP2pDevice.AVAILABLE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set mock device fields", e)
        }
        return device
    }
    
    /**
     * Connect to a discovered peer by establishing a direct socket connection
     */
    private fun connectToDiscoveredPeer(peerID: String, ipAddress: String) {
        Log.d(TAG, "Attempting to connect to discovered peer: $peerID at $ipAddress")
        
        if (!isActive) {
            Log.w(TAG, "Connection manager not active, cannot connect to peer")
            return
        }
        
        connectionScope.launch {
            try {
                // Create a mock WiFi P2P device for the discovered peer
                val mockDevice = createMockWifiP2pDevice(peerID, ipAddress)
                
                // Check if we're already connected to this peer
                if (connectionTracker.isDeviceConnected(ipAddress)) {
                    Log.d(TAG, "Already connected to peer $peerID at $ipAddress")
                    return@launch
                }
                
                // Establish direct socket connection
                val socket = java.net.Socket()
                socket.soTimeout = 10000 // 10 second timeout
                
                try {
                    // Try to connect to the peer's server on port 8888
                    socket.connect(java.net.InetSocketAddress(ipAddress, 8888), 10000)
                    Log.i(TAG, "Successfully connected to peer $peerID at $ipAddress")
                    
                    // Create device connection and add to tracker
                    val deviceConnection = WifiConnectionTracker.DeviceConnection(
                        device = mockDevice,
                        socket = socket,
                        isClient = true
                    )
                    
                    connectionTracker.addDeviceConnection(ipAddress, deviceConnection)
                    
                    // Notify delegate of connection
                    delegate?.onDeviceConnected(mockDevice)
                    
                    // Start listening for packets from this peer
                    startListeningForPackets(socket, mockDevice, peerID)
                    
                    // Send initial handshake/announcement
                    sendInitialHandshake(socket, peerID)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to peer $peerID at $ipAddress: ${e.message}")
                    socket.close()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in connectToDiscoveredPeer for $peerID: ${e.message}")
            }
        }
    }
    
    /**
     * Start listening for packets from a connected peer
     */
    private fun startListeningForPackets(socket: java.net.Socket, device: WifiP2pDevice, peerID: String) {
        connectionScope.launch {
            try {
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(4096)
                
                while (isActive && socket.isConnected && !socket.isClosed) {
                    try {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            val receivedData = buffer.copyOf(bytesRead)
                            Log.d(TAG, "Received ${bytesRead} bytes from peer $peerID")
                            
                            // Try to parse as BitchatPacket
                            val packet = com.wichat.android.protocol.BitchatPacket.fromBinaryData(receivedData)
                            if (packet != null) {
                                Log.d(TAG, "Parsed packet type ${packet.type} from $peerID")
                                delegate?.onPacketReceived(packet, peerID, device)
                            } else {
                                Log.w(TAG, "Failed to parse packet from $peerID")
                            }
                        } else if (bytesRead == -1) {
                            Log.i(TAG, "Peer $peerID disconnected (end of stream)")
                            break
                        }
                    } catch (e: SocketTimeoutException) {
                        // Timeout is expected, continue
                        continue
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading from peer $peerID: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in packet listener for peer $peerID: ${e.message}")
            } finally {
                Log.i(TAG, "Stopped listening to peer $peerID")
                connectionTracker.cleanupDeviceConnection(device.deviceAddress)
            }
        }
    }
    
    /**
     * Send initial handshake/announcement to newly connected peer
     */
    private fun sendInitialHandshake(socket: java.net.Socket, peerID: String) {
        connectionScope.launch {
            try {
                // Create a proper BitchatPacket announce message
                val announcePacket = com.wichat.android.protocol.BitchatPacket(
                    version = 1u,
                    type = com.wichat.android.protocol.MessageType.ANNOUNCE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = com.wichat.android.protocol.SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = myPeerID.toByteArray(), // Send our peer ID as payload
                    ttl = 7u
                )
                
                val packetData = announcePacket.toBinaryData()
                if (packetData != null) {
                    socket.getOutputStream().write(packetData)
                    socket.getOutputStream().flush()
                    Log.d(TAG, "Sent BitchatPacket announce to peer $peerID (${packetData.size} bytes)")
                } else {
                    Log.e(TAG, "Failed to serialize announce packet for $peerID")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send initial handshake to $peerID: ${e.message}")
            }
        }
    }
    
    /**
     * Get the IP address of a connected peer
     */
    fun getPeerAddress(peerID: String): String? {
        return connectionTracker.getPeerAddress(peerID)
    }
    
    /**
     * Convert hex string to byte array (helper method)
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        return result
    }
    
    // MARK: - Private Implementation - All moved to component managers
}

/**
 * Delegate interface for Wifi connection manager callbacks
 */
interface WifiConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: WifiP2pDevice?)
    fun onDeviceConnected(device: WifiP2pDevice)
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
}
