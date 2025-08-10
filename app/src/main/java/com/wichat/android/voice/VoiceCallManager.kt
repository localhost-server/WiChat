package com.wichat.android.voice

import android.content.Context
import android.util.Log
import com.wichat.android.audio.AudioEngine
import com.wichat.android.model.*
import com.wichat.android.protocol.BitchatPacket
import com.wichat.android.protocol.MessageType
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages voice call states, signaling, and audio streaming
 */
class VoiceCallManager(
    private val context: Context,
    private val myPeerID: String,
    private val myNickname: String
) {
    
    companion object {
        private const val TAG = "VoiceCallManager"
        private const val CALL_TIMEOUT_MS = 30000L // 30 seconds
        private const val CALL_RING_TIMEOUT_MS = 60000L // 60 seconds
        private const val AUDIO_PACKET_INTERVAL_MS = 20L // 20ms for 50fps audio
    }
    
    // Audio engine
    private val audioEngine = AudioEngine(context)
    
    // Hybrid UDP audio transport for real-time media streaming
    private val udpAudioTransport = HybridUdpAudioTransport()
    
    // Call state management
    private var currentCallState = VoiceCallState.IDLE
    private var currentCall: VoiceCall? = null
    private val activeAudioStreams = ConcurrentHashMap<String, AudioStreamInfo>()
    
    // Coroutines
    private val callScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var callTimeoutJob: Job? = null
    private var audioTransmissionJob: Job? = null
    
    // Delegates
    var delegate: VoiceCallManagerDelegate? = null
    var meshServiceDelegate: VoiceCallMeshDelegate? = null
    
    init {
        setupUdpAudioTransport()
    }
    
    // Audio stream tracking
    private data class AudioStreamInfo(
        val callId: String,
        val peerID: String,
        var lastSequenceNumber: UShort = 0u,
        var lastReceivedTime: Long = System.currentTimeMillis()
    )
    
    // Current call information
    private data class VoiceCall(
        val callId: String,
        val initiatorPeerID: String,
        val receiverPeerID: String,
        val initiatorNickname: String,
        val receiverNickname: String,
        val startTime: Long = System.currentTimeMillis(),
        var isIncoming: Boolean
    )
    
    init {
        setupUdpAudioTransport()
    }
    
    /**
     * Initiate an outgoing call to a peer
     */
    fun initiateCall(targetPeerID: String, targetNickname: String): Boolean {
        if (currentCallState != VoiceCallState.IDLE) {
            Log.w(TAG, "Cannot initiate call - already in call state: $currentCallState")
            return false
        }
        
        val callId = UUID.randomUUID().toString()
        val offer = VoiceCallOffer(
            callId = callId,
            callerPeerID = myPeerID,
            callerNickname = myNickname
        )
        
        currentCall = VoiceCall(
            callId = callId,
            initiatorPeerID = myPeerID,
            receiverPeerID = targetPeerID,
            initiatorNickname = myNickname,
            receiverNickname = targetNickname,
            isIncoming = false
        )
        
        currentCallState = VoiceCallState.CALLING
        
        // Send offer packet
        val packet = BitchatPacket(
            type = MessageType.VOICE_CALL_OFFER.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(targetPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = offer.toBinaryData(),
            ttl = 3u // Lower TTL for faster voice call packet processing
        )
        
        // Send voice call offer with immediate retry for reliability
        meshServiceDelegate?.sendPacket(packet)
        
        // Send duplicate immediately for voice call reliability
        callScope.launch {
            delay(100) // 100ms retry
            meshServiceDelegate?.sendPacket(packet)
        }
        
        // Send third attempt after a longer delay if call hasn't been answered
        callScope.launch {
            delay(2000) // 2 second retry  
            if (currentCallState == VoiceCallState.CALLING) {
                meshServiceDelegate?.sendPacket(packet)
                Log.d(TAG, "Sent voice call offer retry for reliability")
            }
        }
        
        // Start call timeout
        startCallTimeout(CALL_RING_TIMEOUT_MS) {
            handleCallTimeout("No answer from recipient")
        }
        
        delegate?.onCallStateChanged(currentCallState, currentCall?.let {
            mapOf(
                "callId" to it.callId,
                "peerID" to targetPeerID,
                "nickname" to targetNickname,
                "isIncoming" to false,
                "startTime" to it.startTime
            )
        })
        
        Log.i(TAG, "Initiated call to $targetNickname ($targetPeerID)")
        return true
    }
    
    /**
     * Answer an incoming call
     */
    fun answerCall(): Boolean {
        val call = currentCall
        if (currentCallState != VoiceCallState.RINGING || call == null) {
            Log.w(TAG, "Cannot answer call - not in ringing state")
            return false
        }
        
        currentCallState = VoiceCallState.CONNECTING
        
        val answer = VoiceCallAnswer(
            callId = call.callId,
            answererPeerID = myPeerID,
            answererNickname = myNickname,
            accepted = true
        )
        
        val packet = BitchatPacket(
            type = MessageType.VOICE_CALL_ANSWER.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(call.initiatorPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = answer.toBinaryData(),
            ttl = 3u // Lower TTL for faster voice call packet processing
        )
        
        // Send answer with reliability retry
        meshServiceDelegate?.sendPacket(packet)
        
        // Immediate retry for answer reliability
        callScope.launch {
            delay(50) // Faster retry for answer
            meshServiceDelegate?.sendPacket(packet)
        }
        
        // Start audio
        startAudioSession()
        
        delegate?.onCallStateChanged(currentCallState, mapOf(
            "callId" to call.callId,
            "peerID" to call.initiatorPeerID,
            "nickname" to call.initiatorNickname,
            "isIncoming" to true,
            "startTime" to call.startTime
        ))
        
        Log.i(TAG, "Answered call from ${call.initiatorNickname}")
        return true
    }
    
    /**
     * Reject an incoming call
     */
    fun rejectCall(): Boolean {
        val call = currentCall
        if (currentCallState != VoiceCallState.RINGING || call == null) {
            Log.w(TAG, "Cannot reject call - not in ringing state")
            return false
        }
        
        val answer = VoiceCallAnswer(
            callId = call.callId,
            answererPeerID = myPeerID,
            answererNickname = myNickname,
            accepted = false
        )
        
        val packet = BitchatPacket(
            type = MessageType.VOICE_CALL_REJECT.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(call.initiatorPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = answer.toBinaryData(),
            ttl = 3u // Lower TTL for faster voice call packet processing
        )
        
        meshServiceDelegate?.sendPacket(packet)
        
        endCall("Call rejected")
        Log.i(TAG, "Rejected call from ${call.initiatorNickname}")
        return true
    }
    
    /**
     * End the current call
     */
    fun endCall(reason: String = "User ended call") {
        val call = currentCall
        if (currentCallState == VoiceCallState.IDLE || call == null) {
            return
        }
        
        currentCallState = VoiceCallState.ENDING
        
        val hangup = VoiceCallHangup(
            callId = call.callId,
            senderPeerID = myPeerID,
            reason = reason
        )
        
        val targetPeerID = if (call.isIncoming) call.initiatorPeerID else call.receiverPeerID
        
        val packet = BitchatPacket(
            type = MessageType.VOICE_CALL_HANGUP.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(targetPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = hangup.toBinaryData(),
            ttl = 3u // Lower TTL for faster voice call packet processing
        )
        
        // Send hangup with reliability retry
        meshServiceDelegate?.sendPacket(packet)
        
        // Immediate retry for hangup reliability
        callScope.launch {
            delay(50)
            meshServiceDelegate?.sendPacket(packet)
        }
        
        stopAudioSession()
        cleanupCall()
        
        Log.i(TAG, "Ended call: $reason")
    }
    
    /**
     * Toggle speakerphone
     */
    fun toggleSpeakerphone() {
        audioEngine.toggleSpeakerphone()
        delegate?.onSpeakerphoneToggled(audioEngine.isSpeakerphoneOn())
    }
    
    /**
     * Check if speakerphone is enabled
     */
    fun isSpeakerphoneOn(): Boolean {
        return audioEngine.isSpeakerphoneOn()
    }
    
    /**
     * Toggle microphone mute
     */
    fun toggleMute() {
        audioEngine.toggleMute()
        delegate?.onMuteToggled(audioEngine.isMuted())
    }
    
    /**
     * Check if microphone is muted
     */
    fun isMuted(): Boolean {
        return audioEngine.isMuted()
    }
    
    /**
     * Set microphone mute state
     */
    fun setMuted(muted: Boolean) {
        audioEngine.setMuted(muted)
        delegate?.onMuteToggled(muted)
    }
    
    /**
     * Get current microphone level (0.0 to 1.0)
     */
    fun getMicrophoneLevel(): Float {
        return audioEngine.getMicrophoneLevel()
    }
    
    
    // MARK: - Incoming packet handlers
    
    fun handleVoiceCallOffer(packet: BitchatPacket, senderPeerID: String) {
        if (currentCallState != VoiceCallState.IDLE) {
            Log.w(TAG, "Received call offer but already in call")
            // Send busy signal or reject
            return
        }
        
        val offer = VoiceCallOffer.fromBinaryData(packet.payload)
        if (offer == null) {
            Log.e(TAG, "Failed to parse voice call offer")
            return
        }
        
        currentCall = VoiceCall(
            callId = offer.callId,
            initiatorPeerID = offer.callerPeerID,
            receiverPeerID = myPeerID,
            initiatorNickname = offer.callerNickname,
            receiverNickname = myNickname,
            isIncoming = true
        )
        
        currentCallState = VoiceCallState.RINGING
        
        // Start ring timeout
        startCallTimeout(CALL_RING_TIMEOUT_MS) {
            handleCallTimeout("Call not answered")
        }
        
        // Pass peer ID as nickname - delegate will resolve the actual username
        delegate?.onIncomingCall(offer.callerPeerID, offer.callerPeerID, offer.callId)
        
        Log.i(TAG, "Received incoming call from ${offer.callerNickname}")
    }
    
    fun handleVoiceCallAnswer(packet: BitchatPacket, senderPeerID: String) {
        val call = currentCall
        if (currentCallState != VoiceCallState.CALLING || call == null) {
            Log.w(TAG, "Received call answer but not in calling state")
            return
        }
        
        val answer = VoiceCallAnswer.fromBinaryData(packet.payload)
        if (answer == null) {
            Log.e(TAG, "Failed to parse voice call answer")
            return
        }
        
        if (answer.accepted) {
            currentCallState = VoiceCallState.CONNECTING
            startAudioSession()
            
            delegate?.onCallStateChanged(currentCallState, mapOf(
                "callId" to call.callId,
                "peerID" to call.receiverPeerID,
                "nickname" to call.receiverNickname,
                "isIncoming" to false,
                "startTime" to call.startTime
            ))
            
            Log.i(TAG, "Call accepted by ${answer.answererNickname}")
        } else {
            endCall("Call rejected by recipient")
        }
    }
    
    fun handleVoiceCallHangup(packet: BitchatPacket, senderPeerID: String) {
        val hangup = VoiceCallHangup.fromBinaryData(packet.payload)
        if (hangup == null) {
            Log.e(TAG, "Failed to parse voice call hangup")
            return
        }
        
        endCall("Call ended by peer: ${hangup.reason}")
    }
    
    fun handleVoiceAudioData(packet: BitchatPacket, senderPeerID: String) {
        if (currentCallState != VoiceCallState.ACTIVE) {
            return
        }
        
        val audioPacket = VoiceAudioPacket.fromBinaryData(packet.payload)
        if (audioPacket == null) {
            Log.w(TAG, "Failed to parse voice audio packet")
            return
        }
        
        // Update stream info
        val streamInfo = activeAudioStreams.getOrPut(senderPeerID) {
            AudioStreamInfo(audioPacket.callId, senderPeerID)
        }
        
        streamInfo.lastSequenceNumber = audioPacket.sequenceNumber
        streamInfo.lastReceivedTime = System.currentTimeMillis()
        
        // Queue audio for playback (AudioEngine will handle Opus decoding automatically)
        Log.v(TAG, "📥 Received ${audioPacket.compressionType} audio: ${audioPacket.audioData.size} bytes")
        audioEngine.queueAudioForPlayback(audioPacket.audioData)
    }
    
    // MARK: - Hybrid UDP Audio Transport Setup
    
    private fun setupUdpAudioTransport() {
        // Set up UDP audio callbacks
        udpAudioTransport.onAudioReceived = { audioData, peerID ->
            // Queue received audio for playback
            audioEngine.queueAudioForPlayback(audioData)
            Log.v(TAG, "Received UDP audio from $peerID: ${audioData.size} bytes")
        }
        
        udpAudioTransport.onConnectionStateChanged = { peerID, connected ->
            Log.d(TAG, "UDP audio connection to $peerID: ${if (connected) "connected" else "disconnected"}")
        }
    }
    
    // MARK: - Private methods
    
    private fun startAudioSession() {
        Log.i(TAG, "=== Starting Hybrid Audio Session (TCP signaling + UDP media) ===")
        
        // Cancel any pending timeouts since call is now active
        callTimeoutJob?.cancel()
        
        currentCallState = VoiceCallState.ACTIVE
        
        // Always notify state change first
        delegate?.onCallStateChanged(currentCallState, currentCall?.let {
            mapOf(
                "callId" to it.callId,
                "peerID" to if (it.isIncoming) it.initiatorPeerID else it.receiverPeerID,
                "nickname" to if (it.isIncoming) it.initiatorNickname else it.receiverNickname,
                "isIncoming" to it.isIncoming,
                "startTime" to it.startTime
            )
        })
        
        // Start UDP audio transport for real-time media streaming
        val localAudioPort = udpAudioTransport.startAudioTransport()
        if (localAudioPort == -1) {
            Log.e(TAG, "Failed to start UDP audio transport")
            return
        }
        
        Log.i(TAG, "UDP audio transport started on port $localAudioPort")
        
        // Connect UDP audio to peer using real address resolution
        currentCall?.let { call ->
            val targetPeerID = if (call.isIncoming) call.initiatorPeerID else call.receiverPeerID
            
            Log.i(TAG, "=== UDP AUDIO CONNECTION ===")
            Log.i(TAG, "Target Peer ID: $targetPeerID")
            Log.i(TAG, "Call is incoming: ${call.isIncoming}")
            
            // Get peer address from connection tracker
            val peerAddress = meshServiceDelegate?.getPeerAddress(targetPeerID)
            val peerAudioPort = 9000 // Fixed port for now, should be negotiated via signaling
            
            if (peerAddress != null) {
                Log.i(TAG, "🔍 Found peer address: $targetPeerID -> $peerAddress")
                val connected = udpAudioTransport.connectToPeer(targetPeerID, peerAddress, peerAudioPort)
                if (connected) {
                    Log.i(TAG, "✅ Connected UDP audio to peer $targetPeerID at $peerAddress:$peerAudioPort")
                } else {
                    Log.e(TAG, "❌ Failed to connect UDP audio to $targetPeerID at $peerAddress:$peerAudioPort")
                }
            } else {
                Log.e(TAG, "❌ Could not resolve address for peer $targetPeerID - voice call requires proper mesh connection")
                Log.w(TAG, "💡 Voice calls only work when devices are properly connected via mesh network")
                
                // End the call since we can't establish audio connection
                endCall("Unable to resolve peer address for audio connection")
                return
            }
        }
        
        // Set up audio callback for UDP transmission (instead of TCP packets)
        audioEngine.onAudioRecorded = { audioData, sequenceNumber ->
            Log.d(TAG, "🎤 Audio recorded: ${audioData.size} bytes, seq: $sequenceNumber")
            currentCall?.let { call ->
                if (currentCallState == VoiceCallState.ACTIVE) {
                    val targetPeerID = if (call.isIncoming) call.initiatorPeerID else call.receiverPeerID
                    
                    // Send audio via UDP for real-time streaming
                    val sent = udpAudioTransport.sendAudio(audioData, targetPeerID)
                    Log.d(TAG, "📡 UDP send result: $sent, target: $targetPeerID, size: ${audioData.size}")
                } else {
                    Log.w(TAG, "⚠️ Audio recorded but call not active: $currentCallState")
                }
            } ?: Log.w(TAG, "⚠️ Audio recorded but no current call")
        }
        
        // Start audio recording and playback
        if (audioEngine.startRecording() && audioEngine.startPlayback()) {
            Log.i(TAG, "Audio session started successfully")
        } else {
            Log.e(TAG, "Failed to start audio session - continuing call without audio")
            // Don't end call immediately, let user decide
        }
    }
    
    
    private fun stopAudioSession() {
        Log.i(TAG, "=== Stopping Hybrid Audio Session ===")
        
        audioEngine.onAudioRecorded = null
        audioEngine.stopRecording()
        audioEngine.stopPlayback()
        audioTransmissionJob?.cancel()
        
        // Stop UDP audio transport
        udpAudioTransport.stopAudioTransport()
        
        activeAudioStreams.clear()
        Log.i(TAG, "Audio session stopped")
    }
    
    
    private fun startCallTimeout(timeoutMs: Long, onTimeout: () -> Unit) {
        callTimeoutJob?.cancel()
        callTimeoutJob = callScope.launch {
            delay(timeoutMs)
            onTimeout()
        }
    }
    
    private fun handleCallTimeout(reason: String) {
        Log.w(TAG, "Call timeout: $reason")
        endCall(reason)
    }
    
    private fun cleanupCall() {
        callTimeoutJob?.cancel()
        currentCall = null
        currentCallState = VoiceCallState.IDLE
        activeAudioStreams.clear()
        
        delegate?.onCallStateChanged(currentCallState, null)
    }
    
    fun getCurrentCallState(): VoiceCallState = currentCallState
    
    fun getCurrentCall(): Map<String, Any>? {
        return currentCall?.let {
            mapOf(
                "callId" to it.callId,
                "initiatorPeerID" to it.initiatorPeerID,
                "receiverPeerID" to it.receiverPeerID,
                "initiatorNickname" to it.initiatorNickname,
                "receiverNickname" to it.receiverNickname,
                "isIncoming" to it.isIncoming,
                "startTime" to it.startTime
            )
        }
    }
    
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== VoiceCallManager Debug Info ===")
            appendLine("Current State: $currentCallState")
            appendLine("Current Call: ${currentCall?.callId ?: "None"}")
            appendLine("Active Audio Streams: ${activeAudioStreams.size}")
            activeAudioStreams.forEach { (peerID, streamInfo) ->
                appendLine("  - $peerID: seq=${streamInfo.lastSequenceNumber}, last=${System.currentTimeMillis() - streamInfo.lastReceivedTime}ms ago")
            }
            appendLine()
            appendLine(audioEngine.getDebugInfo())
        }
    }
    
    fun shutdown() {
        Log.i(TAG, "Shutting down VoiceCallManager")
        
        if (currentCallState != VoiceCallState.IDLE) {
            endCall("Application shutting down")
        }
        
        audioEngine.shutdown()
        callScope.cancel()
    }
    
    /**
     * Convert hex string peer ID to binary data (8 bytes) - same as WifiMeshService
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
}

/**
 * Delegate interface for voice call events
 */
interface VoiceCallManagerDelegate {
    fun onIncomingCall(callerNickname: String, callerPeerID: String, callId: String)
    fun onCallStateChanged(state: VoiceCallState, callInfo: Map<String, Any>?)
    fun onSpeakerphoneToggled(isOn: Boolean)
    fun onMuteToggled(isMuted: Boolean)
}

/**
 * Delegate interface for mesh service integration
 */
interface VoiceCallMeshDelegate {
    fun sendPacket(packet: BitchatPacket)
    fun getPeerAddress(peerID: String): String?
}