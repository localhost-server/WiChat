package com.wichat.android.voice

import android.util.Log
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Hybrid UDP Audio Transport Layer
 * 
 * This handles only the real-time audio streaming part after TCP signaling
 * establishes the voice call connection. Follows industry standard architecture:
 * 
 * TCP: Call signaling (offer/answer/hangup) - Reliable delivery
 * UDP: Audio streaming - Real-time, low latency
 */
class HybridUdpAudioTransport {
    
    companion object {
        private const val TAG = "HybridUdpAudio"
        private const val AUDIO_PORT_BASE = 9000
        private const val MAX_PACKET_SIZE = 1024
        private const val HEARTBEAT_INTERVAL_MS = 1000L
    }
    
    // UDP socket for audio streaming
    private var audioSocket: DatagramSocket? = null
    private var localAudioPort: Int = 0
    private var isActive = false
    
    // Coroutines for audio handling
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioReceiveJob: Job? = null
    private var heartbeatJob: Job? = null
    
    // Peer connection info
    private val connectedPeers = ConcurrentHashMap<String, PeerAudioInfo>()
    
    // Callbacks
    var onAudioReceived: ((audioData: ByteArray, peerID: String) -> Unit)? = null
    var onConnectionStateChanged: ((peerID: String, connected: Boolean) -> Unit)? = null
    
    private data class PeerAudioInfo(
        val peerID: String,
        val address: InetAddress,
        val port: Int,
        var lastHeartbeat: Long = System.currentTimeMillis(),
        var packetsReceived: Int = 0,
        var packetsSent: Int = 0
    )
    
    /**
     * Start UDP audio transport (called after TCP signaling succeeds)
     */
    fun startAudioTransport(): Int {
        if (isActive) {
            Log.w(TAG, "Audio transport already active on port $localAudioPort")
            return localAudioPort
        }
        
        try {
            // Create UDP socket for audio streaming - USE FIXED PORT FOR NOW
            localAudioPort = AUDIO_PORT_BASE // Fixed port 9000 for both devices
            Log.i(TAG, "🚀 Starting UDP audio transport on FIXED port $localAudioPort")
            
            audioSocket = DatagramSocket(localAudioPort)
            audioSocket?.soTimeout = 10 // Low-latency receive timeout
            
            Log.i(TAG, "✅ UDP socket created successfully: ${audioSocket?.localAddress}:${audioSocket?.localPort}")
            
            isActive = true
            
            // Start receiving audio packets
            startAudioReceiver()
            Log.i(TAG, "✅ Audio receiver started")
            
            // Start heartbeat to maintain connections
            startHeartbeat()
            Log.i(TAG, "✅ Heartbeat started")
            
            Log.i(TAG, "🎉 UDP audio transport fully started on port $localAudioPort")
            return localAudioPort
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start audio transport: ${e.message}")
            e.printStackTrace()
            return -1
        }
    }
    
    /**
     * Connect to a peer for audio streaming (called with info from TCP signaling)
     */
    fun connectToPeer(peerID: String, peerAddress: String, peerPort: Int): Boolean {
        if (!isActive) {
            Log.w(TAG, "Audio transport not active")
            return false
        }
        
        try {
            Log.i(TAG, "🔗 Connecting to peer $peerID at $peerAddress:$peerPort")
            
            val inetAddress = InetAddress.getByName(peerAddress)
            val peerInfo = PeerAudioInfo(peerID, inetAddress, peerPort)
            
            // Remove any existing connection to this peer first
            val existing = connectedPeers.remove(peerID)
            if (existing != null) {
                Log.w(TAG, "Replacing existing connection to $peerID")
            }
            
            // Add the new peer connection
            connectedPeers[peerID] = peerInfo
            Log.i(TAG, "✅ Added peer $peerID to connectedPeers map. Map size: ${connectedPeers.size}")
            Log.i(TAG, "📋 Connected peers: ${connectedPeers.keys}")
            
            // Send initial connection packet
            sendControlPacket(peerInfo, "AUDIO_CONNECT")
            
            // Test sending a quick packet to verify connection
            Log.i(TAG, "🧪 Testing connection by sending small test packet...")
            val testData = "TEST_AUDIO".toByteArray()
            val sent = sendAudio(testData, peerID)
            Log.i(TAG, "🧪 Test packet send result: $sent")
            
            onConnectionStateChanged?.invoke(peerID, true)
            Log.i(TAG, "✅ Successfully connected to peer $peerID at $peerAddress:$peerPort for audio")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to connect to peer $peerID: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Send audio data to a specific peer
     */
    fun sendAudio(audioData: ByteArray, peerID: String): Boolean {
        val peer = connectedPeers[peerID] 
        if (peer == null) {
            Log.w(TAG, "❌ No peer found for $peerID. Connected peers: ${connectedPeers.keys}")
            return false
        }
        
        val socket = audioSocket 
        if (socket == null) {
            Log.w(TAG, "❌ Audio socket is null")
            return false
        }
        
        try {
            // Create audio packet with simple header
            val packet = createAudioPacket(audioData, peer.packetsSent)
            val datagramPacket = DatagramPacket(packet, packet.size, peer.address, peer.port)
            
            Log.v(TAG, "📤 Sending ${packet.size} bytes to ${peer.address.hostAddress}:${peer.port}")
            socket.send(datagramPacket)
            peer.packetsSent++
            
            Log.v(TAG, "✅ Sent audio packet #${peer.packetsSent} to $peerID")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send audio to $peerID: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Send audio to all connected peers
     */
    fun sendAudioToAll(audioData: ByteArray): Int {
        var sentCount = 0
        connectedPeers.values.forEach { peer ->
            if (sendAudio(audioData, peer.peerID)) {
                sentCount++
            }
        }
        return sentCount
    }
    
    /**
     * Disconnect from a peer
     */
    fun disconnectPeer(peerID: String) {
        val peer = connectedPeers.remove(peerID)
        if (peer != null) {
            sendControlPacket(peer, "AUDIO_DISCONNECT")
            onConnectionStateChanged?.invoke(peerID, false)
            Log.i(TAG, "Disconnected from peer $peerID")
        }
    }
    
    /**
     * Stop audio transport
     */
    fun stopAudioTransport() {
        if (!isActive) return
        
        isActive = false
        
        // Send disconnect to all peers
        connectedPeers.values.forEach { peer ->
            sendControlPacket(peer, "AUDIO_DISCONNECT")
        }
        
        // Cleanup
        audioReceiveJob?.cancel()
        heartbeatJob?.cancel()
        audioSocket?.close()
        connectedPeers.clear()
        
        audioSocket = null
        localAudioPort = 0
        
        Log.i(TAG, "UDP audio transport stopped")
    }
    
    /**
     * Get connection statistics
     */
    fun getConnectionStats(): Map<String, Map<String, Any>> {
        return connectedPeers.mapValues { (_, peer) ->
            mapOf(
                "address" to "${peer.address.hostAddress}:${peer.port}",
                "packetsReceived" to peer.packetsReceived,
                "packetsSent" to peer.packetsSent,
                "lastHeartbeat" to peer.lastHeartbeat
            )
        }
    }
    
    // MARK: - Private Methods
    
    private fun startAudioReceiver() {
        audioReceiveJob = audioScope.launch {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            
            while (isActive) {
                try {
                    val socket = audioSocket ?: break
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    // Process received audio packet
                    processReceivedPacket(packet)
                    
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue
                    continue
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Error receiving audio: ${e.message}")
                    }
                }
            }
        }
    }
    
    private fun startHeartbeat() {
        heartbeatJob = audioScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                
                // Send heartbeat to all peers - more aggressive to maintain connections
                connectedPeers.values.forEach { peer ->
                    sendControlPacket(peer, "HEARTBEAT")
                    Log.v(TAG, "Sent heartbeat to ${peer.peerID}")
                }
                
                // Check for dead connections - much more lenient during active calls
                val now = System.currentTimeMillis()
                val deadPeers = connectedPeers.filter { (_, peer) ->
                    val timeSinceLastHeartbeat = now - peer.lastHeartbeat
                    // Use very long timeout (60 seconds) to prevent disconnections during calls
                    val isDeadConnection = timeSinceLastHeartbeat > HEARTBEAT_INTERVAL_MS * 60 // 60 seconds
                    if (timeSinceLastHeartbeat > HEARTBEAT_INTERVAL_MS * 30) {
                        Log.w(TAG, "Peer ${peer.peerID} heartbeat delay: ${timeSinceLastHeartbeat}ms")
                    }
                    isDeadConnection
                }
                
                deadPeers.forEach { (peerID, peer) ->
                    Log.w(TAG, "Peer $peerID connection timeout after ${now - peer.lastHeartbeat}ms")
                    // Only disconnect if we haven't received ANY audio recently
                    val timeSinceLastPacket = now - peer.lastHeartbeat
                    if (timeSinceLastPacket > HEARTBEAT_INTERVAL_MS * 60) {
                        Log.e(TAG, "Disconnecting peer $peerID after ${timeSinceLastPacket}ms silence")
                        disconnectPeer(peerID)
                    } else {
                        Log.i(TAG, "Keeping peer $peerID despite heartbeat delay (still receiving data)")
                    }
                }
            }
        }
    }
    
    private fun processReceivedPacket(packet: DatagramPacket) {
        try {
            val data = packet.data.copyOf(packet.length)
            if (data.size < 1) return // Invalid packet (need at least type byte)
            
            val buffer = ByteBuffer.wrap(data)
            val type = buffer.get() // Packet type
            
            // Optimized: Peer identification is now purely based on UDP source address
            // (no more wasted "AUDIO01" extraction)
            val senderAddress = packet.address
            val senderIP = senderAddress.hostAddress
            
            // Strategy 1: Exact address match
            var peer = connectedPeers.values.find { it.address == senderAddress }
            
            // Strategy 2: IP address string match (fallback)
            if (peer == null) {
                peer = connectedPeers.values.find { it.address.hostAddress == senderIP }
                if (peer != null) {
                    Log.d(TAG, "🔍 Found peer by IP match: $senderIP -> ${peer.peerID}")
                }
            }
            
            // Strategy 3: Accept any audio from connected peers (last resort)
            if (peer == null && connectedPeers.isNotEmpty()) {
                peer = connectedPeers.values.first()
                Log.w(TAG, "⚠️ Using first connected peer ${peer.peerID} for audio from $senderIP")
            }
            
            when (type.toInt()) {
                1 -> { // Audio data
                    if (peer != null) {
                        val audioData = ByteArray(data.size - 1) // Only 1-byte header now
                        buffer.get(audioData)
                        peer.packetsReceived++
                        peer.lastHeartbeat = System.currentTimeMillis()
                        
                        Log.v(TAG, "🔊 Received audio: ${audioData.size} bytes from ${peer.peerID} ($senderIP)")
                        onAudioReceived?.invoke(audioData, peer.peerID)
                    } else {
                        Log.w(TAG, "❌ Received audio from unknown peer: $senderIP (no connected peers)")
                        // Still try to play the audio even if peer is unknown
                        if (data.size > 1) { // Updated: only 1-byte header now
                            val audioData = ByteArray(data.size - 1)
                            buffer.get(audioData)
                            Log.i(TAG, "🎵 Playing unknown peer audio anyway: ${audioData.size} bytes")
                            onAudioReceived?.invoke(audioData, "unknown")
                            
                            // Try to re-establish connection for this peer
                            if (connectedPeers.isEmpty()) {
                                Log.i(TAG, "🔄 Attempting to reconnect to audio sender at $senderIP")
                                // Create temporary peer info to keep audio flowing
                                val tempPeerID = "temp_${senderIP?.replace(".", "_") ?: "unknown"}"
                                val tempPeer = PeerAudioInfo(tempPeerID, senderAddress, 9000)
                                connectedPeers[tempPeerID] = tempPeer
                                Log.i(TAG, "✅ Created temporary peer connection for $tempPeerID")
                            }
                        }
                    }
                }
                2 -> { // Control packet
                    if (peer != null) {
                        peer.lastHeartbeat = System.currentTimeMillis()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error processing packet: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun createAudioPacket(audioData: ByteArray, sequenceNumber: Int): ByteArray {
        // Optimized: Send just audio type + data (removed 7-byte "AUDIO01" waste)
        val buffer = ByteBuffer.allocate(1 + audioData.size)
        buffer.put(1.toByte()) // Audio packet type (keep for future extensibility)
        buffer.put(audioData)
        return buffer.array()
    }
    
    private fun sendControlPacket(peer: PeerAudioInfo, command: String) {
        try {
            val socket = audioSocket ?: return
            val buffer = ByteBuffer.allocate(8 + command.length)
            buffer.put(2.toByte()) // Control packet type
            buffer.put("CTRL001".toByteArray()) // 7-byte identifier
            buffer.put(command.toByteArray())
            
            // Fix: use the actual length of data written (position after writing)
            val dataLength = buffer.position()
            val packet = DatagramPacket(buffer.array(), dataLength, peer.address, peer.port)
            socket.send(packet)
            
            Log.v(TAG, "✅ Sent control packet '$command' (${dataLength} bytes) to ${peer.peerID}")
            
        } catch (e: Exception) {
            Log.w(TAG, "❌ Failed to send control packet to ${peer.peerID}: ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Hybrid UDP Audio Transport ===")
            appendLine("Active: $isActive")
            appendLine("Local Port: $localAudioPort")
            appendLine("Connected Peers: ${connectedPeers.size}")
            
            connectedPeers.forEach { (peerID, peer) ->
                appendLine("  - $peerID: ${peer.address.hostAddress}:${peer.port}")
                appendLine("    RX: ${peer.packetsReceived}, TX: ${peer.packetsSent}")
                appendLine("    Last: ${System.currentTimeMillis() - peer.lastHeartbeat}ms ago")
            }
        }
    }
}