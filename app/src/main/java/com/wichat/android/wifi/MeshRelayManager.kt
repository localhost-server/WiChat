package com.wichat.android.wifi

import android.util.Log
import com.wichat.android.model.RoutedPacket
import com.wichat.android.protocol.BitchatPacket
import com.wichat.android.protocol.MessageType
import kotlinx.coroutines.*
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages mesh relay functionality to extend network reach
 * Allows devices to relay messages for peers that are not directly connected
 * Implements intelligent routing to prevent loops and optimize paths
 */
class MeshRelayManager(
    private val myPeerID: String,
    private val onRelayPacket: (RoutedPacket) -> Unit
) {
    
    companion object {
        private const val TAG = "MeshRelayManager"
        private const val MAX_TTL = 7u
        private const val RELAY_CLEANUP_INTERVAL = 30000L // 30 seconds
        private const val PACKET_DUPLICATE_TIMEOUT = 60000L // 1 minute
        private const val MAX_RELAY_BUFFER_SIZE = 1000
        
        // Message types that should be relayed
        private val RELAYABLE_TYPES = setOf(
            MessageType.MESSAGE.value,
            MessageType.ANNOUNCE.value,
            MessageType.VOICE_CALL_OFFER.value,
            MessageType.VOICE_CALL_ANSWER.value,
            MessageType.VOICE_CALL_HANGUP.value,
            MessageType.NOISE_ENCRYPTED.value,
            MessageType.DELIVERY_ACK.value,
            MessageType.READ_RECEIPT.value
        )
    }
    
    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track recently seen packets to prevent loops and duplicates
    private val seenPackets = ConcurrentHashMap<String, Long>()
    
    // Topology map: peerID -> Set of directly connected neighbors
    private val peerConnections = ConcurrentHashMap<String, MutableSet<String>>()
    
    // Relay statistics
    private var packetsRelayed = 0L
    private var packetsDropped = 0L
    private var duplicatesFiltered = 0L
    
    private var isActive = false
    
    init {
        startRelayCleanup()
    }
    
    fun start() {
        if (isActive) {
            Log.w(TAG, "Mesh relay manager already active")
            return
        }
        
        Log.i(TAG, "Starting mesh relay manager")
        isActive = true
    }
    
    fun stop() {
        if (!isActive) return
        
        Log.i(TAG, "Stopping mesh relay manager")
        isActive = false
        
        seenPackets.clear()
        peerConnections.clear()
        
        relayScope.cancel()
    }
    
    /**
     * Update the network topology when a peer connection changes
     */
    fun updatePeerConnection(peerID: String, connectedNeighbors: Set<String>) {
        peerConnections[peerID] = connectedNeighbors.toMutableSet()
        Log.d(TAG, "Updated connections for $peerID: ${connectedNeighbors.joinToString(", ")}")
    }
    
    /**
     * Remove a peer from the topology when they disconnect
     */
    fun removePeer(peerID: String) {
        peerConnections.remove(peerID)
        
        // Remove this peer from other peers' connection lists
        peerConnections.values.forEach { connections ->
            connections.remove(peerID)
        }
        
        Log.d(TAG, "Removed peer $peerID from topology")
    }
    
    /**
     * Process a packet for potential relaying
     * Returns true if packet was handled (relayed or dropped)
     * Returns false if packet should be processed locally
     */
    fun processPacketForRelay(routedPacket: RoutedPacket): Boolean {
        val packet = routedPacket.packet
        
        // Don't relay packets from ourselves
        if (routedPacket.peerID == myPeerID) {
            return false
        }
        
        // Check if this packet should be relayed
        if (!shouldRelayPacket(packet)) {
            return false
        }
        
        // Check for duplicates and loops
        val packetId = generatePacketId(packet)
        val now = System.currentTimeMillis()
        
        if (seenPackets.containsKey(packetId)) {
            duplicatesFiltered++
            Log.v(TAG, "Dropping duplicate packet $packetId")
            return true // Packet handled (dropped)
        }
        
        // Record this packet
        seenPackets[packetId] = now
        
        // Check TTL
        if (packet.ttl <= 1u) {
            packetsDropped++
            Log.v(TAG, "Dropping packet $packetId - TTL expired")
            return true // Packet handled (dropped)
        }
        
        // Determine if we should relay this packet
        if (shouldRelayToOtherPeers(routedPacket)) {
            relayPacket(routedPacket)
            return false // Let local processing continue as well
        }
        
        return false // Let local processing handle it
    }
    
    /**
     * Get the optimal path to reach a target peer
     */
    fun findPathToPeer(targetPeerID: String, excludePeers: Set<String> = emptySet()): List<String>? {
        if (targetPeerID == myPeerID) return emptyList()
        
        // Simple breadth-first search to find shortest path
        val visited = mutableSetOf<String>()
        val queue = mutableListOf(listOf(myPeerID))
        
        while (queue.isNotEmpty()) {
            val currentPath = queue.removeFirst()
            val currentPeer = currentPath.last()
            
            if (currentPeer in visited || currentPeer in excludePeers) continue
            visited.add(currentPeer)
            
            val neighbors = peerConnections[currentPeer] ?: continue
            
            for (neighbor in neighbors) {
                if (neighbor == targetPeerID) {
                    return currentPath + neighbor
                }
                
                if (neighbor !in visited && currentPath.size < MAX_TTL.toInt()) {
                    queue.add(currentPath + neighbor)
                }
            }
        }
        
        return null // No path found
    }
    
    /**
     * Check if we can reach a target peer through relaying
     */
    fun canReachPeer(targetPeerID: String): Boolean {
        return findPathToPeer(targetPeerID) != null
    }
    
    /**
     * Get mesh network statistics
     */
    fun getRelayStats(): String {
        return buildString {
            appendLine("=== Mesh Relay Statistics ===")
            appendLine("Active: $isActive")
            appendLine("Packets Relayed: $packetsRelayed")
            appendLine("Packets Dropped: $packetsDropped")
            appendLine("Duplicates Filtered: $duplicatesFiltered")
            appendLine("Seen Packets Cache: ${seenPackets.size} entries")
            appendLine()
            appendLine("Network Topology:")
            peerConnections.forEach { (peerID, connections) ->
                appendLine("  $peerID -> ${connections.joinToString(", ")}")
            }
        }
    }
    
    /**
     * Get mesh network debug info including reachability analysis
     */
    fun getNetworkTopologyDebug(): String {
        return buildString {
            appendLine("=== Network Topology Analysis ===")
            appendLine("Total Peers: ${peerConnections.size}")
            appendLine()
            
            // Analyze connectivity
            val allPeers = peerConnections.keys + peerConnections.values.flatten()
            val uniquePeers = allPeers.toSet()
            
            appendLine("Reachability Analysis:")
            uniquePeers.forEach { peer ->
                if (peer != myPeerID) {
                    val path = findPathToPeer(peer)
                    if (path != null) {
                        appendLine("  $peer: reachable (${path.size - 1} hops) -> ${path.joinToString(" -> ")}")
                    } else {
                        appendLine("  $peer: unreachable")
                    }
                }
            }
        }
    }
    
    private fun shouldRelayPacket(packet: BitchatPacket): Boolean {
        // Only relay certain types of messages
        if (packet.type !in RELAYABLE_TYPES) {
            return false
        }
        
        // Don't relay if TTL is too low
        if (packet.ttl <= 1u) {
            return false
        }
        
        return true
    }
    
    private fun shouldRelayToOtherPeers(routedPacket: RoutedPacket): Boolean {
        val packet = routedPacket.packet
        val senderPeerID = routedPacket.peerID ?: return false
        
        // Check if the packet is destined for a specific peer
        if (packet.recipientID?.isNotEmpty() == true) {
            val recipientPeerID = packet.recipientID!!.joinToString("") { "%02x".format(it) }
            
            // If it's for us, don't relay (process locally)
            if (recipientPeerID == myPeerID) {
                return false
            }
            
            // If we have a path to the recipient, relay it
            return canReachPeer(recipientPeerID)
        }
        
        // For broadcast messages, relay to extend reach
        return true
    }
    
    private fun relayPacket(routedPacket: RoutedPacket) {
        if (!isActive) return
        
        relayScope.launch {
            try {
                // Create new packet with decremented TTL
                val originalPacket = routedPacket.packet
                val relayedPacket = BitchatPacket(
                    version = originalPacket.version,
                    type = originalPacket.type,
                    senderID = originalPacket.senderID,
                    recipientID = originalPacket.recipientID,
                    timestamp = originalPacket.timestamp,
                    payload = originalPacket.payload,
                    signature = originalPacket.signature,
                    ttl = maxOf(1u, originalPacket.ttl - 1u).toUByte() // Ensure TTL doesn't go below 1
                )
                
                val newRoutedPacket = RoutedPacket(
                    packet = relayedPacket,
                    peerID = routedPacket.peerID,
                    relayAddress = routedPacket.relayAddress
                )
                
                // Add small delay to prevent network congestion
                delay((50L + (0..100).random())) // 50-150ms random delay
                
                // Relay the packet
                onRelayPacket(newRoutedPacket)
                packetsRelayed++
                
                Log.v(TAG, "Relayed packet type ${originalPacket.type} (TTL: ${relayedPacket.ttl})")
                
            } catch (e: Exception) {
                packetsDropped++
                Log.w(TAG, "Failed to relay packet: ${e.message}")
            }
        }
    }
    
    private fun generatePacketId(packet: BitchatPacket): String {
        // Generate unique ID from packet content
        return "${packet.senderID.joinToString("") { "%02x".format(it) }}-${packet.timestamp}-${packet.type}-${packet.payload.contentHashCode()}"
    }
    
    private fun startRelayCleanup() {
        relayScope.launch {
            while (true) {
                delay(RELAY_CLEANUP_INTERVAL)
                if (isActive) {
                    cleanupSeenPackets()
                }
            }
        }
    }
    
    private fun cleanupSeenPackets() {
        val now = System.currentTimeMillis()
        val staleEntries = seenPackets.entries.filter { 
            now - it.value > PACKET_DUPLICATE_TIMEOUT 
        }
        
        staleEntries.forEach { entry ->
            seenPackets.remove(entry.key)
        }
        
        // Limit cache size
        if (seenPackets.size > MAX_RELAY_BUFFER_SIZE) {
            val sortedEntries = seenPackets.entries.sortedBy { it.value }
            val toRemove = sortedEntries.take(seenPackets.size - MAX_RELAY_BUFFER_SIZE + 100)
            toRemove.forEach { entry ->
                seenPackets.remove(entry.key)
            }
        }
        
        if (staleEntries.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${staleEntries.size} stale packet entries")
        }
    }
}