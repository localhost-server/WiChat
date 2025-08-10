package com.wichat.android.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.PrintWriter
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Simple TCP-based network discovery as a fallback to WiFi P2P
 * This should work on any WiFi network without requiring special permissions
 */
class SimpleNetworkDiscovery(
    private val context: Context,
    private val myPeerID: String,
    private val onPeerDiscovered: (String, String) -> Unit
) {
    
    companion object {
        private const val TAG = "SimpleNetworkDiscovery" 
        private const val DISCOVERY_PORT = 8890
        private const val SCAN_TIMEOUT = 3000
        private const val DISCOVERY_INTERVAL = 45000L // 45 seconds
    }
    
    private var isActive = false
    private val discoveryScope = CoroutineScope(Dispatchers.IO)
    
    fun start() {
        if (isActive) return
        
        Log.i(TAG, "=== STARTING SIMPLE NETWORK DISCOVERY ===")
        Log.i(TAG, "My Peer ID: $myPeerID")
        Log.i(TAG, "Discovery port: $DISCOVERY_PORT")
        
        if (!isConnectedToWifi()) {
            Log.w(TAG, "Not connected to WiFi, cannot start network discovery")
            return
        }
        
        isActive = true
        
        // Start TCP discovery server
        startDiscoveryServer()
        
        // Start network scanning  
        startNetworkScanning()
        
        Log.i(TAG, "=== SIMPLE NETWORK DISCOVERY STARTED ===")
    }
    
    fun stop() {
        isActive = false
        Log.i(TAG, "Simple network discovery stopped")
    }
    
    private fun isConnectedToWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    private fun startDiscoveryServer() {
        discoveryScope.launch {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(DISCOVERY_PORT)
                serverSocket.soTimeout = SCAN_TIMEOUT
                Log.i(TAG, "Discovery server listening on port $DISCOVERY_PORT")
                
                while (isActive) {
                    try {
                        val clientSocket = serverSocket.accept()
                        handleClientConnection(clientSocket)
                    } catch (e: SocketTimeoutException) {
                        // Timeout is expected, continue listening
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error in discovery server", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery server on port $DISCOVERY_PORT", e)
            } finally {
                serverSocket?.close()
                Log.d(TAG, "Discovery server stopped")
            }
        }
    }
    
    private fun handleClientConnection(clientSocket: Socket) {
        discoveryScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                
                val request = reader.readLine()
                Log.d(TAG, "Received discovery request: $request from ${clientSocket.inetAddress}")
                
                if (request?.startsWith("BITCHAT_DISCOVERY") == true) {
                    // Respond with our peer ID
                    writer.println("BITCHAT_RESPONSE:$myPeerID")
                    Log.d(TAG, "Responded to discovery from ${clientSocket.inetAddress}")
                }
                
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client connection", e)
            }
        }
    }
    
    private fun startNetworkScanning() {
        discoveryScope.launch {
            delay(2000) // Wait a bit for server to start
            
            while (isActive) {
                try {
                    scanLocalNetwork()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in network scanning", e)
                }
                
                delay(DISCOVERY_INTERVAL)
            }
        }
    }
    
    private suspend fun scanLocalNetwork() {
        Log.d(TAG, "Starting dynamic network scan...")
        
        try {
            val networkInfo = getNetworkInfo()
            if (networkInfo == null) {
                Log.w(TAG, "Could not determine network information")
                return
            }
            
            Log.i(TAG, "Network detected: ${networkInfo.networkAddress}/${networkInfo.prefixLength}")
            Log.i(TAG, "Local IP: ${networkInfo.localAddress}")
            Log.i(TAG, "Scanning ${networkInfo.addressCount} addresses efficiently...")
            
            // Use smart scanning strategy based on network size
            val addresses = if (networkInfo.addressCount <= 256) {
                // Small network (/24 or smaller) - scan all
                networkInfo.getAllAddresses()
            } else {
                // Large network (/16, /8) - use optimized scanning
                networkInfo.getOptimizedScanAddresses()
            }
            
            Log.d(TAG, "Scanning ${addresses.size} target addresses")
            
            // Scan addresses in batches to avoid overwhelming the network
            addresses.chunked(32).forEach { batch ->
                val scanTasks = batch.map { targetIp ->
                    discoveryScope.launch {
                        if (targetIp != networkInfo.localAddress) {
                            tryConnectToPeer(targetIp)
                        }
                    }
                }
                
                // Wait for this batch to complete before starting next batch
                delay(500) // Small delay between batches
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in dynamic network scan", e)
        }
    }
    
    private fun tryConnectToPeer(ipAddress: String) {
        try {
            val socket = Socket()
            socket.soTimeout = SCAN_TIMEOUT
            socket.connect(java.net.InetSocketAddress(ipAddress, DISCOVERY_PORT), SCAN_TIMEOUT)
            
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            // Send discovery request
            writer.println("BITCHAT_DISCOVERY:$myPeerID")
            
            // Wait for response
            val response = reader.readLine()
            if (response?.startsWith("BITCHAT_RESPONSE:") == true) {
                val peerID = response.substringAfter(":")
                if (peerID != myPeerID) { // Don't discover ourselves
                    Log.i(TAG, "Discovered peer: $peerID at $ipAddress")
                    onPeerDiscovered(peerID, ipAddress)
                }
            }
            
            socket.close()
            
        } catch (e: Exception) {
            // Expected for non-bitchat devices, don't log unless debug
            // Log.v(TAG, "No bitchat peer at $ipAddress: ${e.message}")
        }
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(network)
            
            linkProperties?.linkAddresses?.forEach { linkAddress ->
                val address = linkAddress.address
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
        }
        
        return null
    }
    
    /**
     * Data class to hold complete network information
     */
    data class NetworkInfo(
        val localAddress: String,
        val networkAddress: String,
        val prefixLength: Int,
        val subnetMask: String,
        val addressCount: Long
    ) {
        
        /**
         * Get all addresses in the network (for small networks)
         */
        fun getAllAddresses(): List<String> {
            return try {
                val addresses = mutableListOf<String>()
                val networkParts = networkAddress.split(".").map { it.toInt() }
                val hostBits = 32 - prefixLength
                val maxHosts = (1L shl hostBits) - 2 // Subtract network and broadcast
                
                // Generate all host addresses
                for (host in 1..maxHosts.coerceAtMost(254)) {
                    val address = when (prefixLength) {
                        24 -> "${networkParts[0]}.${networkParts[1]}.${networkParts[2]}.$host"
                        16 -> {
                            val thirdOctet = (host - 1) / 254
                            val fourthOctet = ((host - 1) % 254) + 1
                            "${networkParts[0]}.${networkParts[1]}.$thirdOctet.$fourthOctet"
                        }
                        8 -> {
                            val secondOctet = (host - 1) / (254 * 254)
                            val thirdOctet = ((host - 1) / 254) % 254
                            val fourthOctet = ((host - 1) % 254) + 1
                            "${networkParts[0]}.$secondOctet.$thirdOctet.$fourthOctet"
                        }
                        else -> continue // Skip unsupported prefix lengths
                    }
                    addresses.add(address)
                }
                addresses
            } catch (e: Exception) {
                Log.e(TAG, "Error generating all addresses", e)
                emptyList()
            }
        }
        
        /**
         * Get optimized scan addresses for large networks
         */
        fun getOptimizedScanAddresses(): List<String> {
            return try {
                val addresses = mutableListOf<String>()
                val networkParts = networkAddress.split(".").map { it.toInt() }
                val localParts = localAddress.split(".").map { it.toInt() }
                
                when (prefixLength) {
                    16 -> {
                        // For /16 networks, scan common ranges around our subnet
                        val ourThirdOctet = localParts[2]
                        val scanRanges = listOf(
                            ourThirdOctet,
                            0, 1, 10, 100, 101, 254  // Common router/server ranges
                        ).distinct()
                        
                        scanRanges.forEach { thirdOctet ->
                            // Scan common addresses in each subnet
                            val commonHosts = listOf(1, 2, 10, 11, 100, 101, 200, 254)
                            commonHosts.forEach { host ->
                                addresses.add("${networkParts[0]}.${networkParts[1]}.$thirdOctet.$host")
                            }
                        }
                    }
                    8 -> {
                        // For /8 networks, scan very selectively
                        val ourSecondOctet = localParts[1]
                        val ourThirdOctet = localParts[2]
                        
                        // Scan our immediate /16 subnet
                        val commonHosts = listOf(1, 2, 10, 11, 100, 101, 200, 254)
                        commonHosts.forEach { host ->
                            addresses.add("${networkParts[0]}.$ourSecondOctet.$ourThirdOctet.$host")
                        }
                        
                        // Scan common gateway/server ranges
                        val commonSecondOctets = listOf(0, 1, 10, 168)
                        commonSecondOctets.forEach { secondOctet ->
                            if (secondOctet != ourSecondOctet) {
                                commonHosts.forEach { host ->
                                    addresses.add("${networkParts[0]}.$secondOctet.1.$host")
                                }
                            }
                        }
                    }
                    else -> {
                        // For other prefix lengths, fall back to local subnet scan
                        return getAllAddresses().take(100) // Limit to first 100
                    }
                }
                
                addresses.distinct()
            } catch (e: Exception) {
                Log.e(TAG, "Error generating optimized addresses", e)
                emptyList()
            }
        }
    }
    
    /**
     * Get complete network information including subnet mask
     */
    private fun getNetworkInfo(): NetworkInfo? {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(network)
            
            linkProperties?.linkAddresses?.forEach { linkAddress ->
                val address = linkAddress.address
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    val localAddress = address.hostAddress!!
                    val prefixLength = linkAddress.prefixLength
                    
                    // Calculate network address
                    val addressBytes = address.address
                    val networkBytes = ByteArray(4)
                    val subnetMaskBytes = ByteArray(4)
                    
                    // Create subnet mask
                    var mask = (0xFFFFFFFFL shl (32 - prefixLength)).toInt()
                    for (i in 0..3) {
                        subnetMaskBytes[i] = ((mask ushr ((3 - i) * 8)) and 0xFF).toByte()
                        networkBytes[i] = (addressBytes[i].toInt() and subnetMaskBytes[i].toInt()).toByte()
                    }
                    
                    val networkAddress = "${networkBytes[0].toInt() and 0xFF}.${networkBytes[1].toInt() and 0xFF}.${networkBytes[2].toInt() and 0xFF}.${networkBytes[3].toInt() and 0xFF}"
                    val subnetMask = "${subnetMaskBytes[0].toInt() and 0xFF}.${subnetMaskBytes[1].toInt() and 0xFF}.${subnetMaskBytes[2].toInt() and 0xFF}.${subnetMaskBytes[3].toInt() and 0xFF}"
                    val addressCount = 1L shl (32 - prefixLength)
                    
                    return NetworkInfo(
                        localAddress = localAddress,
                        networkAddress = networkAddress,
                        prefixLength = prefixLength,
                        subnetMask = subnetMask,
                        addressCount = addressCount
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network information", e)
        }
        
        return null
    }
}