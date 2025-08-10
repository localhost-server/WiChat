package com.wichat.android.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Scans for bitchat peers on the local WiFi network using UDP broadcast
 * This complements WiFi Direct discovery for devices on the same WiFi network
 */
class LocalNetworkScanner(
    private val context: Context,
    private val myPeerID: String,
    private val onPeerDiscovered: (String, InetAddress) -> Unit
) {
    
    companion object {
        private const val TAG = "LocalNetworkScanner"
        private const val DISCOVERY_PORT = 8889
        private const val BROADCAST_MESSAGE = "BITCHAT_DISCOVERY"
        private const val RESPONSE_MESSAGE = "BITCHAT_RESPONSE"
        private const val SOCKET_TIMEOUT = 2000
        private const val DISCOVERY_INTERVAL = 30000L // 30 seconds
    }
    
    private var isActive = false
    private val scanScope = CoroutineScope(Dispatchers.IO)
    
    fun start() {
        if (isActive) return
        
        Log.i(TAG, "=== STARTING LOCAL NETWORK SCANNER ===")
        Log.i(TAG, "My Peer ID: $myPeerID")
        
        if (!isConnectedToWifi()) {
            Log.w(TAG, "Not connected to WiFi, skipping local network scan")
            return
        }
        
        isActive = true
        Log.i(TAG, "WiFi connection detected, starting network discovery...")
        Log.i(TAG, "Discovery port: $DISCOVERY_PORT")
        
        // Start discovery server to respond to broadcasts
        startDiscoveryServer()
        
        // Start periodic broadcast discovery
        startPeriodicDiscovery()
        
        Log.i(TAG, "=== LOCAL NETWORK SCANNER STARTED ===")
    }
    
    fun stop() {
        isActive = false
        Log.i(TAG, "Stopping local network scanner")
    }
    
    private fun isConnectedToWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    private fun startDiscoveryServer() {
        scanScope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(DISCOVERY_PORT)
                socket.soTimeout = SOCKET_TIMEOUT
                Log.d(TAG, "Discovery server listening on port $DISCOVERY_PORT")
                
                val buffer = ByteArray(1024)
                
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        
                        val message = String(packet.data, 0, packet.length)
                        if (message.startsWith(BROADCAST_MESSAGE)) {
                            // Respond with our peer ID
                            val response = "$RESPONSE_MESSAGE:$myPeerID"
                            val responsePacket = DatagramPacket(
                                response.toByteArray(),
                                response.length,
                                packet.address,
                                packet.port
                            )
                            socket.send(responsePacket)
                            Log.d(TAG, "Responded to discovery from ${packet.address}")
                        } else if (message.startsWith(RESPONSE_MESSAGE)) {
                            // Another peer responded to our broadcast
                            val parts = message.split(":")
                            if (parts.size >= 2) {
                                val peerID = parts[1]
                                Log.d(TAG, "Discovered peer $peerID at ${packet.address}")
                                onPeerDiscovered(peerID, packet.address)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // Timeout is expected, continue listening
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error in discovery server", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery server", e)
            } finally {
                socket?.close()
                Log.d(TAG, "Discovery server stopped")
            }
        }
    }
    
    private fun startPeriodicDiscovery() {
        scanScope.launch {
            while (isActive) {
                try {
                    broadcastDiscovery()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic discovery", e)
                }
                
                delay(DISCOVERY_INTERVAL)
            }
        }
    }
    
    private fun broadcastDiscovery() {
        val broadcastAddresses = getBroadcastAddresses()
        
        for (broadcastAddress in broadcastAddresses) {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = SOCKET_TIMEOUT
                
                val message = "$BROADCAST_MESSAGE:$myPeerID"
                val packet = DatagramPacket(
                    message.toByteArray(),
                    message.length,
                    broadcastAddress,
                    DISCOVERY_PORT
                )
                
                socket.send(packet)
                Log.d(TAG, "Sent discovery broadcast to $broadcastAddress")
                
                // Listen for immediate responses
                val buffer = ByteArray(1024)
                val startTime = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - startTime < SOCKET_TIMEOUT && isActive) {
                    try {
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(responsePacket)
                        
                        val response = String(responsePacket.data, 0, responsePacket.length)
                        if (response.startsWith(RESPONSE_MESSAGE)) {
                            val parts = response.split(":")
                            if (parts.size >= 2) {
                                val peerID = parts[1]
                                Log.d(TAG, "Discovered peer $peerID at ${responsePacket.address}")
                                onPeerDiscovered(peerID, responsePacket.address)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        break // No more responses
                    }
                }
                
                socket.close()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast to $broadcastAddress", e)
            }
        }
    }
    
    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastAddresses = mutableListOf<InetAddress>()
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        broadcastAddresses.add(broadcast)
                        Log.d(TAG, "Found broadcast address: $broadcast")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get broadcast addresses", e)
        }
        
        // Fallback to common broadcast addresses
        if (broadcastAddresses.isEmpty()) {
            try {
                broadcastAddresses.add(InetAddress.getByName("255.255.255.255"))
                broadcastAddresses.add(InetAddress.getByName("192.168.1.255"))
                broadcastAddresses.add(InetAddress.getByName("192.168.0.255"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add fallback broadcast addresses", e)
            }
        }
        
        return broadcastAddresses
    }
}