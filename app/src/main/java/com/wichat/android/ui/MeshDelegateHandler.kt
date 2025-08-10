package com.wichat.android.ui

import androidx.lifecycle.LifecycleCoroutineScope
import com.wichat.android.wifi.WifiMeshDelegate
import com.wichat.android.wifi.WifiMeshService
import com.wichat.android.model.BitchatMessage
import com.wichat.android.model.DeliveryAck
import com.wichat.android.model.DeliveryStatus
import com.wichat.android.model.ReadReceipt
import com.wichat.android.model.VoiceCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.*

/**
 * Handles all WifiMeshDelegate callbacks and routes them to appropriate managers
 */
class MeshDelegateHandler(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val coroutineScope: CoroutineScope,
    private val onHapticFeedback: () -> Unit,
    private val getMyPeerID: () -> String,
    private val getMeshService: () -> WifiMeshService
) : WifiMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        coroutineScope.launch {
            // FIXED: Deduplicate messages from dual connection paths
            val messageKey = messageManager.generateMessageKey(message)
            if (messageManager.isMessageProcessed(messageKey)) {
                return@launch // Duplicate message, ignore
            }
            messageManager.markMessageProcessed(messageKey)
            
            // Check if sender is blocked
            message.senderPeerID?.let { senderPeerID ->
                if (privateChatManager.isPeerBlocked(senderPeerID)) {
                    return@launch
                }
            }
            
            // Trigger haptic feedback
            onHapticFeedback()

            if (message.isPrivate) {
                // Private message
                privateChatManager.handleIncomingPrivateMessage(message)
                
                // Read receipts disabled - only delivery status used
                
                // Show notification with enhanced information - now includes senderPeerID 
                message.senderPeerID?.let { senderPeerID ->
                    // Use nickname if available, fall back to sender or senderPeerID
                    val senderNickname = message.sender.takeIf { it != senderPeerID } ?: senderPeerID
                    notificationManager.showPrivateMessageNotification(
                        senderPeerID = senderPeerID, 
                        senderNickname = senderNickname, 
                        messageContent = message.content
                    )
                }
            } else if (message.channel != null) {
                // Channel message
                if (state.getJoinedChannelsValue().contains(message.channel)) {
                    channelManager.addChannelMessage(message.channel, message, message.senderPeerID)
                }
            } else {
                // Public message
                messageManager.addMessage(message)
            }
            
            // Periodic cleanup
            if (messageManager.isMessageProcessed("cleanup_check_${System.currentTimeMillis()/30000}")) {
                messageManager.cleanupDeduplicationCaches()
            }
        }
    }
    
    override fun didConnectToPeer(peerID: String) {
        coroutineScope.launch {
            // FIXED: Deduplicate connection events from dual connection paths
            if (messageManager.isDuplicateSystemEvent("connect", peerID)) {
                return@launch
            }
            
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$peerID connected",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    override fun didDisconnectFromPeer(peerID: String) {
        coroutineScope.launch {
            // FIXED: Deduplicate disconnection events from dual connection paths
            if (messageManager.isDuplicateSystemEvent("disconnect", peerID)) {
                return@launch
            }
            
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$peerID disconnected",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScope.launch {
            state.setConnectedPeers(peers)
            state.setIsConnected(peers.isNotEmpty())
            
            // Clean up channel members who disconnected
            channelManager.cleanupDisconnectedMembers(peers, getMyPeerID())
            
            // Exit private chat if peer disconnected
            state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
                if (!peers.contains(currentPeer)) {
                    privateChatManager.cleanupDisconnectedPeer(currentPeer)
                }
            }
        }
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        coroutineScope.launch {
            channelManager.removeChannelMember(channel, fromPeer)
        }
    }
    
    override fun didReceiveDeliveryAck(ack: DeliveryAck) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(ack.originalMessageID, DeliveryStatus.Delivered(ack.recipientNickname, ack.timestamp))
        }
    }
    
    override fun didReceiveReadReceipt(receipt: ReadReceipt) {
        // Read receipts disabled - no status updates
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return channelManager.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? = state.getNicknameValue()
    
    override fun isFavorite(peerID: String): Boolean {
        return privateChatManager.isFavorite(peerID)
    }
    
    // Read receipts disabled - function removed
    
    // Voice call delegate methods
    override fun onIncomingVoiceCall(callerNickname: String, callerPeerID: String, callId: String) {
        android.util.Log.d("MeshDelegateHandler", "Incoming voice call from $callerNickname ($callerPeerID)")
        // Set incoming call info in state
        state.setIncomingCallInfo(Triple(callerNickname, callerPeerID, callId))
        
        // Show notification
        notificationManager.showIncomingCallNotification(callerNickname, callerPeerID)
        
        // Trigger haptic feedback
        onHapticFeedback()
    }
    
    override fun onVoiceCallStateChanged(voiceCallState: VoiceCallState, callInfo: Map<String, Any>?) {
        android.util.Log.d("MeshDelegateHandler", "Voice call state changed: $voiceCallState")
        // Update state
        state.setCurrentVoiceCallState(voiceCallState)
        state.setCurrentVoiceCall(callInfo)
        
        // Handle specific state changes
        when (voiceCallState) {
            VoiceCallState.ACTIVE -> {
                // Start monitoring microphone level
                // This will be handled by the ViewModel's monitoring loop
            }
            VoiceCallState.IDLE -> {
                // Clear call info
                state.setIncomingCallInfo(null)
                state.setCurrentVoiceCall(null)
            }
            VoiceCallState.RINGING -> {
                // Incoming call notification already handled in onIncomingVoiceCall
            }
            else -> {
                // Other states don't need special handling here
            }
        }
    }
    
    override fun onSpeakerphoneToggled(isOn: Boolean) {
        android.util.Log.d("MeshDelegateHandler", "Speakerphone toggled: $isOn")
        // Update state
        state.setIsSpeakerphoneOn(isOn)
    }
    
    override fun onMuteToggled(isMuted: Boolean) {
        android.util.Log.d("MeshDelegateHandler", "Microphone muted: $isMuted")
        // Update state
        state.setIsMuted(isMuted)
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
}
