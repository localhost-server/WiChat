package com.wichat.android.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.wichat.android.model.RoutedPacket
import com.wichat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles routing of messages across different network subnets for WAN/MAN support
 * Maintains routing tables and discovers peers across network segments
 */
class MultiSubnetRouter(
    private val context: Context,
    private val myPeerID: String,
    private val onRemotePeerDiscovered: (String, InetAddress, String) -> Unit // peerID, address, subnet
) {
    
    companion object {
        private const val TAG = "MultiSubnetRouter"
        private const val INTER_SUBNET_PORT = 8890
        private const val ROUTE_ADVERTISEMENT_INTERVAL = 45000L // 45 seconds
        private const val ROUTE_TIMEOUT = 180000L // 3 minutes
        private const val MAX_HOP_COUNT = 8
    }
    
    private val routerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Routing table: peerID -> RouteInfo
    private val routingTable = ConcurrentHashMap<String, RouteInfo>()
    
    // Known subnets and their gateway addresses
    private val subnetGateways = ConcurrentHashMap<String, InetAddress>()
    
    // Local subnet cache
    private var localSubnet: String? = null
    private var localAddress: InetAddress? = null
    
    private var isActive = false
    private var routeSocket: DatagramSocket? = null
    
    data class RouteInfo(
        val targetPeerID: String,
        val nextHopAddress: InetAddress,
        val subnet: String,
        val hopCount: Int,
        val lastSeen: Long
    )
    
    data class RouteAdvertisement(
        val advertisingPeerID: String,
        val targetPeerID: String,
        val subnet: String,
        val hopCount: Int,
        val timestamp: Long
    )
    
    fun start() {
        if (isActive) {
            Log.w(TAG, "Multi-subnet router already active")
            return
        }
        
        Log.i(TAG, "Starting multi-subnet router")
        isActive = true
        
        // Discover local network configuration
        discoverLocalNetwork()
        
        // Start UDP listener for route advertisements
        startRouteListener()
        
        // Start periodic route advertisement
        startRouteAdvertisement()
        
        // Start route table cleanup
        startRouteCleanup()
    }
    
    fun stop() {
        if (!isActive) return
        
        Log.i(TAG, "Stopping multi-subnet router")
        isActive = false
        
        routeSocket?.close()
        routeSocket = null
        
        routingTable.clear()
        subnetGateways.clear()
        
        routerScope.cancel()
    }
    
    /**
     * Attempt to route a packet to a peer that might be on a different subnet
     */
    fun routePacket(packet: BitchatPacket, targetPeerID: String): Boolean {
        val route = routingTable[targetPeerID]
        if (route == null) {
            Log.d(TAG, "No route found for peer $targetPeerID")
            return false
        }
        
        if (route.hopCount >= MAX_HOP_COUNT) {
            Log.w(TAG, "Dropping packet to $targetPeerID - hop limit exceeded")
            return false
        }
        
        return try {
            // Create routed packet with incremented hop count
            val routedPacket = RoutedPacket(packet, targetPeerID, route.nextHopAddress.hostAddress)
            
            // Send to next hop
            sendPacketToAddress(packet, route.nextHopAddress)
            Log.d(TAG, "Routed packet to $targetPeerID via ${route.nextHopAddress} (subnet: ${route.subnet}, hops: ${route.hopCount})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route packet to $targetPeerID: ${e.message}")
            false
        }
    }
    
    /**
     * Add a discovered peer to the routing table
     */
    fun addPeerRoute(peerID: String, address: InetAddress, subnet: String, hopCount: Int = 1) {
        val routeInfo = RouteInfo(
            targetPeerID = peerID,
            nextHopAddress = address,
            subnet = subnet,
            hopCount = hopCount,
            lastSeen = System.currentTimeMillis()
        )
        
        // Only update if this is a better route (fewer hops) or if route doesn't exist
        val existingRoute = routingTable[peerID]
        if (existingRoute == null || hopCount < existingRoute.hopCount) {
            routingTable[peerID] = routeInfo
            Log.d(TAG, "Added/updated route to $peerID via $address (subnet: $subnet, hops: $hopCount)")
            
            // Notify about new remote peer
            if (subnet != localSubnet) {
                onRemotePeerDiscovered(peerID, address, subnet)
            }
        }
    }
    
    /**
     * Get all known remote subnets
     */
    fun getKnownSubnets(): Set<String> {
        return routingTable.values.map { it.subnet }.toSet()
    }
    
    /**
     * Get peers on a specific subnet
     */
    fun getPeersOnSubnet(subnet: String): List<String> {
        return routingTable.values
            .filter { it.subnet == subnet }
            .map { it.targetPeerID }
    }
    
    /**
     * Get routing table debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Multi-Subnet Router Status ===")
            appendLine("Local Subnet: $localSubnet")
            appendLine("Local Address: $localAddress")
            appendLine("Active: $isActive")
            appendLine()
            appendLine("Routing Table (${routingTable.size} entries):")
            routingTable.values.sortedBy { it.targetPeerID }.forEach { route ->
                val ageMs = System.currentTimeMillis() - route.lastSeen
                appendLine("  ${route.targetPeerID} -> ${route.nextHopAddress} (${route.subnet}, ${route.hopCount} hops, ${ageMs/1000}s ago)")
            }
            appendLine()
            appendLine("Known Subnets: ${getKnownSubnets().joinToString(", ")}")
        }
    }
    
    private fun discoverLocalNetwork() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // Get local WiFi interface address
                val networkInterfaces = NetworkInterface.getNetworkInterfaces()
                for (netInterface in networkInterfaces) {
                    if (netInterface.isUp && !netInterface.isLoopback) {
                        for (address in netInterface.inetAddresses) {
                            if (!address.isLoopbackAddress && address.isSiteLocalAddress) {
                                localAddress = address
                                localSubnet = getSubnetFromAddress(address)
                                Log.i(TAG, "Local network: $localAddress, subnet: $localSubnet")
                                return
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to discover local network: ${e.message}")
        }
    }
    
    private fun getSubnetFromAddress(address: InetAddress): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(network)
            
            // Find the LinkAddress that matches our InetAddress
            linkProperties?.linkAddresses?.forEach { linkAddress ->
                if (linkAddress.address == address) {
                    val prefixLength = linkAddress.prefixLength
                    val addressBytes = address.address
                    
                    // Calculate network address based on actual subnet mask
                    val networkBytes = ByteArray(4)
                    var mask = (0xFFFFFFFFL shl (32 - prefixLength)).toInt()
                    
                    for (i in 0..3) {
                        val maskByte = ((mask ushr ((3 - i) * 8)) and 0xFF).toByte()
                        networkBytes[i] = (addressBytes[i].toInt() and maskByte.toInt()).toByte()
                    }
                    
                    val networkAddress = "${networkBytes[0].toInt() and 0xFF}.${networkBytes[1].toInt() and 0xFF}.${networkBytes[2].toInt() and 0xFF}.${networkBytes[3].toInt() and 0xFF}"
                    return "$networkAddress/$prefixLength"
                }
            }
            
            // Fallback: assume /24 if we can't determine the actual subnet
            val addressBytes = address.address
            val networkAddress = "${addressBytes[0].toInt() and 0xFF}.${addressBytes[1].toInt() and 0xFF}.${addressBytes[2].toInt() and 0xFF}.0"
            Log.w(TAG, "Could not determine actual subnet, assuming /24 for $networkAddress")
            return "$networkAddress/24"
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get subnet from address: ${e.message}")
            // Final fallback
            val addressBytes = address.address
            return "${addressBytes[0].toInt() and 0xFF}.${addressBytes[1].toInt() and 0xFF}.${addressBytes[2].toInt() and 0xFF}.0/24"
        }
    }
    
    private fun startRouteListener() {
        routerScope.launch {
            try {
                routeSocket = DatagramSocket(INTER_SUBNET_PORT)
                routeSocket?.soTimeout = 5000 // 5 second timeout
                
                val buffer = ByteArray(1024)
                
                while (isActive && routeSocket?.isClosed == false) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        routeSocket?.receive(packet)
                        
                        val message = String(packet.data, 0, packet.length)
                        if (message.startsWith("BITCHAT_ROUTE:")) {
                            handleRouteAdvertisement(message.substring(14), packet.address)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.d(TAG, "Route listener timeout/error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start route listener: ${e.message}")
            }
        }
    }
    
    private fun startRouteAdvertisement() {
        routerScope.launch {
            while (isActive) {
                delay(ROUTE_ADVERTISEMENT_INTERVAL)
                if (isActive) {
                    advertiseRoutes()
                }
            }
        }
    }
    
    private fun advertiseRoutes() {
        try {
            val socket = DatagramSocket()
            
            // Advertise routes to known peers
            routingTable.values.forEach { route ->
                val advertisement = "BITCHAT_ROUTE:${myPeerID}|${route.targetPeerID}|${route.subnet}|${route.hopCount + 1}"
                val packet = DatagramPacket(
                    advertisement.toByteArray(),
                    advertisement.length,
                    getBroadcastAddress(),
                    INTER_SUBNET_PORT
                )
                socket.send(packet)
            }
            
            socket.close()
            Log.d(TAG, "Advertised ${routingTable.size} routes")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to advertise routes: ${e.message}")
        }
    }
    
    private fun handleRouteAdvertisement(advertisementData: String, fromAddress: InetAddress) {
        try {
            val parts = advertisementData.split("|")
            if (parts.size >= 4) {
                val advertisingPeerID = parts[0]
                val targetPeerID = parts[1]
                val subnet = parts[2]
                val hopCount = parts[3].toIntOrNull() ?: return
                
                // Don't add routes to ourselves
                if (targetPeerID == myPeerID) return
                
                // Don't create routing loops
                if (advertisingPeerID == myPeerID) return
                
                // Add or update route
                addPeerRoute(targetPeerID, fromAddress, subnet, hopCount)
                
                Log.d(TAG, "Received route advertisement: $targetPeerID via $fromAddress (subnet: $subnet, hops: $hopCount)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle route advertisement: ${e.message}")
        }
    }
    
    private fun startRouteCleanup() {
        routerScope.launch {
            while (isActive) {
                delay(60000) // Check every minute
                if (isActive) {
                    cleanupStaleRoutes()
                }
            }
        }
    }
    
    private fun cleanupStaleRoutes() {
        val now = System.currentTimeMillis()
        val staleRoutes = routingTable.values.filter { now - it.lastSeen > ROUTE_TIMEOUT }
        
        staleRoutes.forEach { route ->
            routingTable.remove(route.targetPeerID)
            Log.d(TAG, "Removed stale route to ${route.targetPeerID}")
        }
        
        if (staleRoutes.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${staleRoutes.size} stale routes")
        }
    }
    
    private fun sendPacketToAddress(packet: BitchatPacket, address: InetAddress) {
        // This would integrate with the existing packet sending mechanism
        // For now, we'll just log the intention
        Log.d(TAG, "Would send packet type ${packet.type} to $address")
    }
    
    private fun getBroadcastAddress(): InetAddress {
        // For simplicity, use limited broadcast
        // In a real implementation, you'd calculate the subnet broadcast address
        return InetAddress.getByName("255.255.255.255")
    }
}