package com.wichat.android.wifi

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.provider.Settings
import android.util.Log
import com.wichat.android.crypto.EncryptionService
import com.wichat.android.model.*
import com.wichat.android.protocol.BitchatPacket
import com.wichat.android.protocol.MessageType
import com.wichat.android.protocol.SpecialRecipients
import com.wichat.android.util.toHexString
import com.wichat.android.voice.VoiceCallManager
import com.wichat.android.voice.VoiceCallManagerDelegate
import com.wichat.android.voice.VoiceCallMeshDelegate
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.*
import kotlin.random.Random

/**
 * Wifi mesh service - orchestrates the Wi-Fi P2P components.
 */
class WifiMeshService(private val context: Context) {

    companion object {
        private const val TAG = "WifiMeshService"
        private const val MAX_TTL: UByte = 7u
    }

    val myPeerID: String = generateCompatiblePeerID().also { peerID ->
        Log.i(TAG, "=== DEVICE IDENTITY INITIALIZED ===")
        Log.i(TAG, "My Peer ID: $peerID")
        Log.i(TAG, "This ID will remain consistent across app restarts")
        Log.i(TAG, "Thread: ${Thread.currentThread().name}")
        Log.i(TAG, "Timestamp: ${System.currentTimeMillis()}")
        Log.i(TAG, "===================================")
        
        // Add stack trace to see where this is being called from
        Log.i(TAG, "Called from:", Exception("Stack trace"))
    }

    // Core components
    private val encryptionService = EncryptionService(context)
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID)
    internal val connectionManager = WifiConnectionManager(context, myPeerID, fragmentManager)
    private val packetProcessor = PacketProcessor(myPeerID)
    
    // Mesh relay and routing components
    private val meshRelayManager = MeshRelayManager(myPeerID) { routedPacket ->
        connectionManager.broadcastPacket(routedPacket)
    }
    private val multiSubnetRouter = MultiSubnetRouter(context, myPeerID) { peerID, address, subnet ->
        // Handle remote peer discovery from different subnets
        Log.d(TAG, "Discovered remote peer $peerID at $address on subnet $subnet")
        peerManager.addOrUpdatePeer(peerID, peerID) // Use peerID as nickname for now
    }
    
    // Voice call manager - using hybrid TCP signaling + UDP media approach
    private val voiceCallManager by lazy { 
        VoiceCallManager(context, myPeerID, delegate?.getNickname() ?: myPeerID)
    }

    private var isActive = false
    var delegate: WifiMeshDelegate? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        setupDelegates()
        setupVoiceCallManager()
        messageHandler.packetProcessor = packetProcessor
        startPeriodicDebugLogging()
    }
    
    /**
     * Get the encryption service for background service access
     * This avoids reflection and ProGuard obfuscation issues
     */
    fun getEncryptionService(): EncryptionService {
        return encryptionService
    }

    private fun startPeriodicDebugLogging() {
        serviceScope.launch {
            while (isActive) {
                delay(10000)
                if (isActive) {
                    val debugInfo = getDebugStatus()
                    Log.d(TAG, "=== PERIODIC DEBUG STATUS ===\n$debugInfo\n=== END DEBUG STATUS ===")
                }
            }
        }
    }

    private fun sendPeriodicBroadcastAnnounce() {
        serviceScope.launch {
            while (isActive) {
                delay(30000)
                sendBroadcastAnnounce()
                broadcastNoiseIdentityAnnouncement()
            }
        }
    }

    private fun setupDelegates() {
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerConnected(nickname: String) {
                delegate?.didConnectToPeer(nickname)
            }

            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }

            override fun onPeerListUpdated(peerIDs: List<String>) {
                delegate?.didUpdatePeerList(peerIDs)
            }
        }

        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {
                serviceScope.launch {
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }

            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {
                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE_RESP.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = MAX_TTL
                )
                connectionManager.broadcastPacket(RoutedPacket(responsePacket))
                Log.d(TAG, "Sent Noise handshake response to $peerID (${response.size} bytes)")
            }
        }

        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return delegate?.isFavorite(peerID) ?: false
            }

            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }

            override fun sendPacket(packet: BitchatPacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
        }

        messageHandler.delegate = object : MessageHandlerDelegate {
            override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
                return peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun removePeer(peerID: String) {
                peerManager.removePeer(peerID)
            }

            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getMyNickname(): String? {
                return delegate?.getNickname()
            }

            override fun sendPacket(packet: BitchatPacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }

            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }

            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }

            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }

            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
            }

            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }

            override fun initiateNoiseHandshake(peerID: String) {
                try {
                    val handshakeData = encryptionService.initiateHandshake(peerID)
                    if (handshakeData != null) {
                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.NOISE_HANDSHAKE_INIT.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(peerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = handshakeData,
                            ttl = MAX_TTL
                        )
                        connectionManager.broadcastPacket(RoutedPacket(packet))
                        Log.d(TAG, "Initiated Noise handshake with $peerID (${handshakeData.size} bytes)")
                    } else {
                        Log.w(TAG, "Failed to generate Noise handshake data for $peerID")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate Noise handshake with $peerID: ${e.message}")
                }
            }

            override fun updatePeerIDBinding(newPeerID: String, nickname: String, publicKey: ByteArray, previousPeerID: String?) {
                Log.d(TAG, "Updating peer ID binding: $newPeerID (was: $previousPeerID) with nickname: $nickname and public key: ${publicKey.toHexString().take(16)}...")
                peerManager.addOrUpdatePeer(newPeerID, nickname)
                val fingerprint = peerManager.storeFingerprintForPeer(newPeerID, publicKey)
                previousPeerID?.let { oldPeerID ->
                    peerManager.removePeer(oldPeerID)
                }
                Log.d(TAG, "Updated peer ID binding: $newPeerID (was: $previousPeerID), fingerprint: ${fingerprint.take(16)}...")
            }

            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }

            override fun sendDeliveryAck(message: BitchatMessage, senderPeerID: String) {
                this@WifiMeshService.sendDeliveryAck(message, senderPeerID)
            }

            override fun onMessageReceived(message: BitchatMessage) {
                delegate?.didReceiveMessage(message)
            }

            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }

            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }

            override fun onDeliveryAckReceived(ack: DeliveryAck) {
                delegate?.didReceiveDeliveryAck(ack)
            }

            override fun onReadReceiptReceived(receipt: ReadReceipt) {
                delegate?.didReceiveReadReceipt(receipt)
            }
        }

        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.validatePacket(packet, peerID)
            }

            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun handleNoiseHandshake(routed: RoutedPacket, step: Int): Boolean {
                return runBlocking { securityManager.handleNoiseHandshake(routed, step) }
            }

            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }

            override fun handleNoiseIdentityAnnouncement(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseIdentityAnnouncement(routed) }
            }

            override fun handleAnnounce(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleAnnounce(routed) }
            }

            override fun handleMessage(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleMessage(routed) }
            }

            override fun handleLeave(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleLeave(routed) }
            }

            override fun handleFragment(packet: BitchatPacket): BitchatPacket? {
                return fragmentManager.handleFragment(packet)
            }

            override fun handleReadReceipt(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleReadReceipt(routed) }
            }

            override fun sendAnnouncementToPeer(peerID: String) {
                this@WifiMeshService.sendAnnouncementToPeer(peerID)
            }

            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }

            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }
            
            // Voice call handlers
            override fun handleVoiceCallOffer(routed: RoutedPacket) {
                voiceCallManager.handleVoiceCallOffer(routed.packet, routed.peerID ?: "unknown")
            }
            
            override fun handleVoiceCallAnswer(routed: RoutedPacket) {
                voiceCallManager.handleVoiceCallAnswer(routed.packet, routed.peerID ?: "unknown")
            }
            
            override fun handleVoiceCallReject(routed: RoutedPacket) {
                voiceCallManager.handleVoiceCallHangup(routed.packet, routed.peerID ?: "unknown")
            }
            
            override fun handleVoiceCallHangup(routed: RoutedPacket) {
                voiceCallManager.handleVoiceCallHangup(routed.packet, routed.peerID ?: "unknown")
            }
            
            override fun handleVoiceAudioData(routed: RoutedPacket) {
                // UDP voice calls handle audio data directly via UDP transport
                // TCP signaling packets are handled by handleVoiceCallOffer/Answer/Hangup
                Log.d(TAG, "Voice audio data packet received - UDP handles audio directly")
            }
        }

        connectionManager.delegate = object : WifiConnectionManagerDelegate {
            override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: WifiP2pDevice?) {
                // Update address mapping when we receive packets from peers
                device?.deviceAddress?.let { deviceAddress ->
                    connectionManager.addressPeerMap[deviceAddress] = peerID
                    Log.v(TAG, "📍 Updated address mapping: $deviceAddress -> $peerID")
                }
                
                packetProcessor.processPacket(RoutedPacket(packet, peerID, device?.deviceAddress))
            }

            override fun onDeviceConnected(device: WifiP2pDevice) {
                serviceScope.launch {
                    delay(200)
                    sendBroadcastAnnounce()
                }
                serviceScope.launch {
                    delay(400)
                    broadcastNoiseIdentityAnnouncement()
                }
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                connectionManager.addressPeerMap[deviceAddress]?.let { peerID ->
                    peerManager.updatePeerRSSI(peerID, rssi)
                }
            }
        }
    }

    fun startServices() {
        if (isActive) {
            Log.w(TAG, "Mesh service already active, ignoring duplicate start request")
            return
        }
        Log.i(TAG, "=== STARTING WIFI MESH SERVICE ===")
        Log.i(TAG, "Peer ID: $myPeerID")
        Log.i(TAG, "Starting connection manager...")
        
        if (connectionManager.startServices()) {
            isActive = true
            
            // Start mesh relay and routing services
            meshRelayManager.start()
            multiSubnetRouter.start()
            
            sendPeriodicBroadcastAnnounce()
            Log.i(TAG, "=== WIFI MESH SERVICE STARTED SUCCESSFULLY ===")
            Log.i(TAG, "Services active: $isActive")
            Log.i(TAG, "Mesh relay active: ${meshRelayManager.getRelayStats().contains("Active: true")}")
            Log.i(TAG, "Multi-subnet router active: ${multiSubnetRouter.getDebugInfo().contains("Active: true")}")
            Log.i(TAG, "Delegate set: ${delegate != null}")
        } else {
            Log.e(TAG, "=== FAILED TO START WIFI SERVICES ===")
        }
    }

    fun stopServices() {
        if (!isActive) {
            Log.w(TAG, "Mesh service not active, ignoring stop request")
            return
        }
        Log.i(TAG, "Stopping Wifi mesh service")
        isActive = false
        sendLeaveAnnouncement()
        serviceScope.launch {
            delay(200)
            connectionManager.stopServices()
            
            // Stop mesh relay and routing services
            meshRelayManager.stop()
            multiSubnetRouter.stop()
            
            peerManager.shutdown()
            fragmentManager.shutdown()
            securityManager.shutdown()
            storeForwardManager.shutdown()
            messageHandler.shutdown()
            packetProcessor.shutdown()
            serviceScope.cancel()
        }
    }

    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            val message = BitchatMessage(
                sender = nickname,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = myPeerID,
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = channel
            )
            message.toBinaryPayload()?.let { messageData ->
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.MESSAGE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = messageData,
                    signature = null,
                    ttl = MAX_TTL
                )
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
        }
    }

    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty() || recipientNickname.isEmpty()) return
        val nickname = delegate?.getNickname() ?: myPeerID
        val message = BitchatMessage(
            id = messageID ?: UUID.randomUUID().toString(),
            sender = nickname,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = myPeerID
        )
        message.toBinaryPayload()?.let { messageData ->
            try {
                val innerPacket = BitchatPacket(
                    type = MessageType.MESSAGE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = messageData,
                    signature = null,
                    ttl = MAX_TTL
                )
                if (storeForwardManager.shouldCacheForPeer(recipientPeerID)) {
                    storeForwardManager.cacheMessage(innerPacket, messageID ?: message.id)
                }
                encryptAndBroadcastNoisePacket(innerPacket, recipientPeerID)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send private message: ${e.message}")
            }
        }
    }

    fun sendDeliveryAck(message: BitchatMessage, senderPeerID: String) {
        val nickname = delegate?.getNickname() ?: myPeerID
        val ack = DeliveryAck(
            originalMessageID = message.id,
            recipientID = myPeerID,
            recipientNickname = nickname,
            hopCount = 0u.toUByte()
        )
        try {
            val ackData = ack.encode() ?: return
            val typeMarker = MessageType.DELIVERY_ACK.value.toByte()
            val payloadWithMarker = byteArrayOf(typeMarker) + ackData
            val encryptedPayload = securityManager.encryptForPeer(payloadWithMarker, senderPeerID)
            if (encryptedPayload == null) {
                Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
                return
            }
            val packet = BitchatPacket(
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = hexStringToByteArray(senderPeerID),
                timestamp = System.currentTimeMillis().toULong(),
                payload = encryptedPayload,
                signature = null,
                ttl = 3u
            )
            connectionManager.broadcastPacket(RoutedPacket(packet))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery ACK: ${e.message}")
        }
    }

    // Read receipts disabled - function removed

    private fun encryptAndBroadcastNoisePacket(innerPacket: BitchatPacket, recipientPeerID: String) {
        serviceScope.launch {
            try {
                val innerPacketData = innerPacket.toBinaryData()
                if (innerPacketData == null) {
                    Log.e(TAG, "Failed to serialize inner packet for encryption")
                    return@launch
                }
                val encryptedPayload = securityManager.encryptForPeer(innerPacketData, recipientPeerID)
                if (encryptedPayload != null) {
                    val outerPacket = BitchatPacket(
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encryptedPayload,
                        signature = null,
                        ttl = MAX_TTL
                    )
                    connectionManager.broadcastPacket(RoutedPacket(outerPacket))
                    Log.d(TAG, "Encrypted and sent packet type ${innerPacket.type} to $recipientPeerID (${encryptedPayload.size} bytes encrypted)")
                } else {
                    Log.w(TAG, "Failed to encrypt packet for $recipientPeerID - no session available")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt and broadcast Noise packet to $recipientPeerID: ${e.message}")
            }
        }
    }

    fun sendBroadcastAnnounce() {
        Log.d(TAG, "Sending broadcast announce")
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = MAX_TTL,
                senderID = myPeerID,
                payload = nickname.toByteArray()
            )
            connectionManager.broadcastPacket(RoutedPacket(announcePacket))
        }
    }

    private fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        connectionManager.broadcastPacket(RoutedPacket(packet))
        peerManager.markPeerAsAnnouncedTo(peerID)
    }

    fun broadcastNoiseIdentityAnnouncement() {
        serviceScope.launch {
            try {
                val nickname = delegate?.getNickname() ?: myPeerID
                val announcement = createNoiseIdentityAnnouncement(nickname, null)
                if (announcement != null) {
                    val announcementData = announcement.toBinaryData()
                    val packet = BitchatPacket(
                        type = MessageType.NOISE_IDENTITY_ANNOUNCE.value,
                        ttl = MAX_TTL,
                        senderID = myPeerID,
                        payload = announcementData,
                    )
                    connectionManager.broadcastPacket(RoutedPacket(packet))
                    Log.d(TAG, "Sent NoiseIdentityAnnouncement (${announcementData.size} bytes)")
                } else {
                    Log.e(TAG, "Failed to create NoiseIdentityAnnouncement")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send NoiseIdentityAnnouncement: ${e.message}")
            }
        }
    }

    fun sendHandshakeRequest(targetPeerID: String, pendingCount: UByte) {
        serviceScope.launch {
            try {
                val request = HandshakeRequest(
                    requesterID = myPeerID,
                    requesterNickname = delegate?.getNickname() ?: myPeerID,
                    targetID = targetPeerID,
                    pendingMessageCount = pendingCount
                )
                val requestData = request.toBinaryData()
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.HANDSHAKE_REQUEST.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(targetPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = requestData,
                    ttl = 6u
                )
                connectionManager.broadcastPacket(RoutedPacket(packet))
                Log.d(TAG, "Sent handshake request to $targetPeerID (pending: $pendingCount, ${requestData.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send handshake request to $targetPeerID: ${e.message}")
            }
        }
    }

    private fun createNoiseIdentityAnnouncement(nickname: String, previousPeerID: String?): NoiseIdentityAnnouncement? {
        return try {
            val staticKey = encryptionService.getStaticPublicKey()
            if (staticKey == null) {
                Log.e(TAG, "No static public key available for identity announcement")
                return null
            }
            val signingKey = encryptionService.getSigningPublicKey()
            if (signingKey == null) {
                Log.e(TAG, "No signing public key available for identity announcement")
                return null
            }
            val now = Date()
            val timestampMs = now.time
            val bindingData = myPeerID.toByteArray(Charsets.UTF_8) + staticKey + timestampMs.toString().toByteArray(Charsets.UTF_8)
            val signature = encryptionService.signData(bindingData) ?: ByteArray(0)
            NoiseIdentityAnnouncement(
                peerID = myPeerID,
                publicKey = staticKey,
                signingPublicKey = signingKey,
                nickname = nickname,
                timestamp = now,
                previousPeerID = previousPeerID,
                signature = signature
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create NoiseIdentityAnnouncement: ${e.message}")
            null
        }
    }

    private fun sendLeaveAnnouncement() {
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        connectionManager.broadcastPacket(RoutedPacket(packet))
    }

    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()

    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()

    fun hasEstablishedSession(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }

    fun getSessionState(peerID: String): com.wichat.android.noise.NoiseSession.NoiseSessionState {
        return encryptionService.getSessionState(peerID)
    }

    fun initiateNoiseHandshake(peerID: String) {
        Log.d(TAG, "🤝 Initiating Noise handshake with $peerID")
        try {
            val handshakeData = encryptionService.initiateHandshake(peerID)
            if (handshakeData != null) {
                Log.d(TAG, "Initiated Noise handshake with $peerID (${handshakeData.size} bytes)")
                
                // Create and broadcast the handshake message
                val handshakePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE_INIT.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = handshakeData,
                    ttl = MAX_TTL
                )
                connectionManager.broadcastPacket(RoutedPacket(handshakePacket))
                Log.d(TAG, "Sent Noise handshake initiation to $peerID (${handshakeData.size} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate handshake with $peerID: ${e.message}", e)
        }
    }

    fun getPeerFingerprint(peerID: String): String? {
        return peerManager.getFingerprintForPeer(peerID)
    }

    fun getIdentityFingerprint(): String {
        return encryptionService.getIdentityFingerprint()
    }

    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }

    fun getEncryptedPeers(): List<String> {
        return emptyList()
    }

    fun getDeviceAddressForPeer(peerID: String): String? {
        return connectionManager.addressPeerMap.entries.find { it.value == peerID }?.key
    }

    fun getDeviceAddressToPeerMapping(): Map<String, String> {
        return connectionManager.addressPeerMap.toMap()
    }

    fun printDeviceAddressesForPeers(): String {
        return peerManager.getDebugInfoWithDeviceAddresses(connectionManager.addressPeerMap)
    }

    fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Wifi Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine("Peer ID Source: ${getPeerIDGenerationInfo()}")
            appendLine()
            appendLine(connectionManager.getDebugInfo())
            appendLine()
            appendLine(peerManager.getDebugInfo(connectionManager.addressPeerMap))
            appendLine()
            appendLine(peerManager.getFingerprintDebugInfo())
            appendLine()
            appendLine(fragmentManager.getDebugInfo())
            appendLine()
            appendLine(securityManager.getDebugInfo())
            appendLine()
            appendLine(storeForwardManager.getDebugInfo())
            appendLine()
            appendLine(messageHandler.getDebugInfo())
            appendLine()
            appendLine(packetProcessor.getDebugInfo())
            appendLine()
            appendLine(meshRelayManager.getRelayStats())
            appendLine()
            appendLine(meshRelayManager.getNetworkTopologyDebug())
            appendLine()
            appendLine(multiSubnetRouter.getDebugInfo())
        }
    }
    
    /**
     * Get debug information about how the peer ID was generated
     */
    private fun getPeerIDGenerationInfo(): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (androidId != null && androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
                "Android ID (${androidId.take(8)}...)"
            } else {
                "Fallback (stored in SharedPreferences)"
            }
        } catch (e: Exception) {
            "Fallback (error accessing Android ID: ${e.message})"
        }
    }

    /**
     * Generate a consistent peer ID based on device Android ID
     * This ensures the same device always has the same peer ID across app restarts
     */
    private fun generateCompatiblePeerID(): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            
            Log.i(TAG, "==== PEER ID GENERATION DEBUG ====")
            Log.i(TAG, "Raw Android ID: $androidId")
            Log.i(TAG, "Android ID length: ${androidId?.length}")
            Log.i(TAG, "Android ID is null: ${androidId == null}")
            Log.i(TAG, "Android ID is empty: ${androidId?.isEmpty()}")
            Log.i(TAG, "Android ID is problematic value: ${androidId == "9774d56d682e549c"}")
            
            // Check for valid Android ID (avoid known problematic values)
            if (androidId != null && androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
                // Hash Android ID to get consistent 8-byte peer ID
                val hash = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
                val peerIdBytes = hash.take(8).toByteArray()
                val peerIdHex = peerIdBytes.joinToString("") { "%02x".format(it) }
                
                Log.i(TAG, "SHA-256 hash (full): ${hash.joinToString("") { "%02x".format(it) }}")
                Log.i(TAG, "First 8 bytes: ${peerIdBytes.joinToString("") { "%02x".format(it) }}")
                Log.i(TAG, "Generated consistent peer ID from Android ID: $peerIdHex")
                Log.i(TAG, "==== END PEER ID GENERATION DEBUG ====")
                
                peerIdHex
            } else {
                // Fallback to stored random ID for edge cases
                Log.w(TAG, "Android ID unavailable or invalid, using fallback method")
                Log.i(TAG, "==== END PEER ID GENERATION DEBUG ====")
                generateAndStoreFallbackPeerID()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating peer ID from Android ID: ${e.message}", e)
            Log.i(TAG, "==== END PEER ID GENERATION DEBUG ====")
            generateAndStoreFallbackPeerID()
        }
    }
    
    /**
     * Fallback method: Generate and store a random peer ID in SharedPreferences
     * This ensures consistency until app data is cleared
     */
    private fun generateAndStoreFallbackPeerID(): String {
        val prefs = context.getSharedPreferences("bitchat_device_id", Context.MODE_PRIVATE)
        val storedPeerID = prefs.getString("peer_id", null)
        
        return if (storedPeerID != null && storedPeerID.length == 16) {
            Log.i(TAG, "Using stored fallback peer ID: $storedPeerID")
            storedPeerID
        } else {
            // Generate new random ID and store it
            val randomBytes = ByteArray(8)
            Random.nextBytes(randomBytes)
            val newPeerID = randomBytes.joinToString("") { "%02x".format(it) }
            
            prefs.edit().putString("peer_id", newPeerID).apply()
            Log.i(TAG, "Generated and stored new fallback peer ID: $newPeerID")
            newPeerID
        }
    }

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

    fun clearAllInternalData() {
        Log.w(TAG, "🚨 Clearing all mesh service internal data")
        try {
            fragmentManager.clearAllFragments()
            storeForwardManager.clearAllCache()
            securityManager.clearAllData()
            peerManager.clearAllPeers()
            peerManager.clearAllFingerprints()
            Log.d(TAG, "✅ Cleared all mesh service internal data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service internal data: ${e.message}")
        }
    }

    fun clearAllEncryptionData() {
        Log.w(TAG, "🚨 Clearing all encryption data")
        try {
            encryptionService.clearPersistentIdentity()
            Log.d(TAG, "✅ Cleared all encryption data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing encryption data: ${e.message}")
        }
    }
    
    // MARK: - Voice Call Management
    
    private fun setupVoiceCallManager() {
        voiceCallManager.delegate = object : VoiceCallManagerDelegate {
            override fun onIncomingCall(callerNickname: String, callerPeerID: String, callId: String) {
                // Resolve actual nickname from peer nicknames, fallback to callerNickname if not found
                val actualNickname = getPeerNicknames()[callerPeerID] ?: callerNickname
                Log.d(TAG, "Incoming call from $callerPeerID - resolved nickname: $actualNickname (original: $callerNickname)")
                delegate?.onIncomingVoiceCall(actualNickname, callerPeerID, callId)
            }
            
            override fun onCallStateChanged(state: VoiceCallState, callInfo: Map<String, Any>?) {
                delegate?.onVoiceCallStateChanged(state, callInfo)
            }
            
            override fun onSpeakerphoneToggled(isOn: Boolean) {
                delegate?.onSpeakerphoneToggled(isOn)
            }
            
            override fun onMuteToggled(isMuted: Boolean) {
                delegate?.onMuteToggled(isMuted)
            }
        }
        
        voiceCallManager.meshServiceDelegate = object : VoiceCallMeshDelegate {
            override fun sendPacket(packet: BitchatPacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
            
            override fun getPeerAddress(peerID: String): String? {
                // Get the IP address of a connected peer for UDP audio transport
                return connectionManager.getPeerAddress(peerID)
            }
        }
    }
    
    fun initiateVoiceCall(targetPeerID: String, targetNickname: String): Boolean {
        Log.i(TAG, "=== Initiating TCP Signaling Voice Call ===")
        Log.i(TAG, "Target: $targetNickname ($targetPeerID)")
        return voiceCallManager.initiateCall(targetPeerID, targetNickname)
    }
    
    fun answerVoiceCall(): Boolean {
        return voiceCallManager.answerCall()
    }
    
    fun rejectVoiceCall(): Boolean {
        return voiceCallManager.rejectCall()
    }
    
    fun endVoiceCall() {
        voiceCallManager.endCall()
    }
    
    fun toggleSpeakerphone() {
        voiceCallManager.toggleSpeakerphone()
    }
    
    fun isSpeakerphoneOn(): Boolean {
        return voiceCallManager.isSpeakerphoneOn()
    }
    
    fun toggleMute() {
        voiceCallManager.toggleMute()
    }
    
    fun isMuted(): Boolean {
        return voiceCallManager.isMuted()
    }
    
    fun getCurrentVoiceCallState(): VoiceCallState {
        return voiceCallManager.getCurrentCallState()
    }
    
    fun getCurrentVoiceCall(): Map<String, Any>? {
        return voiceCallManager.getCurrentCall()
    }
    
    fun getMicrophoneLevel(): Float {
        return voiceCallManager.getMicrophoneLevel()
    }
}

interface WifiMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didConnectToPeer(peerID: String)
    fun didDisconnectFromPeer(peerID: String)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(ack: DeliveryAck)
    fun didReceiveReadReceipt(receipt: ReadReceipt)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    
    // Voice call delegate methods
    fun onIncomingVoiceCall(callerNickname: String, callerPeerID: String, callId: String)
    fun onVoiceCallStateChanged(state: VoiceCallState, callInfo: Map<String, Any>?)
    fun onSpeakerphoneToggled(isOn: Boolean)
    fun onMuteToggled(isMuted: Boolean)
}
