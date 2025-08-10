package com.wichat.android.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wichat.android.model.*
import com.wichat.android.wifi.WifiMeshDelegate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Connector class to bridge UI and background service
 * Provides interface for UI to send messages and receive events from background service
 */
class BackgroundServiceConnector private constructor(private val context: Context) : WifiMeshDelegate {
    
    companion object {
        private const val TAG = "BackgroundServiceConnector"
        
        @Volatile
        private var INSTANCE: BackgroundServiceConnector? = null
        
        fun getInstance(context: Context): BackgroundServiceConnector {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackgroundServiceConnector(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
    
    // Flows for UI to observe background service events
    private val _messageFlow = MutableSharedFlow<BitchatMessage>(extraBufferCapacity = 100)
    val messageFlow: SharedFlow<BitchatMessage> = _messageFlow.asSharedFlow()
    
    private val _peerConnectionFlow = MutableSharedFlow<PeerConnectionEvent>(extraBufferCapacity = 50)
    val peerConnectionFlow: SharedFlow<PeerConnectionEvent> = _peerConnectionFlow.asSharedFlow()
    
    private val _voiceCallFlow = MutableSharedFlow<VoiceCallEvent>(extraBufferCapacity = 20)
    val voiceCallFlow: SharedFlow<VoiceCallEvent> = _voiceCallFlow.asSharedFlow()
    
    private val _deliveryAckFlow = MutableSharedFlow<DeliveryAck>(extraBufferCapacity = 100)
    val deliveryAckFlow: SharedFlow<DeliveryAck> = _deliveryAckFlow.asSharedFlow()
    
    private val _readReceiptFlow = MutableSharedFlow<ReadReceipt>(extraBufferCapacity = 100)
    val readReceiptFlow: SharedFlow<ReadReceipt> = _readReceiptFlow.asSharedFlow()
    
    private val _channelLeaveFlow = MutableSharedFlow<ChannelLeaveEvent>(extraBufferCapacity = 50)
    val channelLeaveFlow: SharedFlow<ChannelLeaveEvent> = _channelLeaveFlow.asSharedFlow()
    
    // Current user settings
    private var currentNickname: String? = null
    private val favoriteChannels = mutableSetOf<String>()
    private val favoritePeers = mutableSetOf<String>()
    
    init {
        loadSettings()
    }
    
    // MARK: - Settings Management
    
    private fun loadSettings() {
        currentNickname = prefs.getString("user_nickname", null)
        
        // Load favorite channels
        val favoriteChannelsSet = prefs.getStringSet("favorite_channels", emptySet()) ?: emptySet()
        favoriteChannels.clear()
        favoriteChannels.addAll(favoriteChannelsSet)
        
        // Load favorite peers
        val favoritePeersSet = prefs.getStringSet("favorite_peers", emptySet()) ?: emptySet()
        favoritePeers.clear()
        favoritePeers.addAll(favoritePeersSet)
    }
    
    fun updateNickname(nickname: String) {
        currentNickname = nickname
        prefs.edit().putString("user_nickname", nickname).apply()
        Log.d(TAG, "Updated nickname to: $nickname")
    }
    
    fun addFavoritePeer(peerID: String) {
        if (favoritePeers.add(peerID)) {
            prefs.edit().putStringSet("favorite_peers", favoritePeers).apply()
            Log.d(TAG, "Added favorite peer: $peerID")
        }
    }
    
    fun removeFavoritePeer(peerID: String) {
        if (favoritePeers.remove(peerID)) {
            prefs.edit().putStringSet("favorite_peers", favoritePeers).apply()
            Log.d(TAG, "Removed favorite peer: $peerID")
        }
    }
    
    fun addFavoriteChannel(channel: String) {
        if (favoriteChannels.add(channel)) {
            prefs.edit().putStringSet("favorite_channels", favoriteChannels).apply()
            Log.d(TAG, "Added favorite channel: $channel")
        }
    }
    
    fun removeFavoriteChannel(channel: String) {
        if (favoriteChannels.remove(channel)) {
            prefs.edit().putStringSet("favorite_channels", favoriteChannels).apply()
            Log.d(TAG, "Removed favorite channel: $channel")
        }
    }
    
    // MARK: - Message Sending (Interface to Background Service)
    
    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        // For now, we'll use a broadcast intent to communicate with background service
        // In a more sophisticated implementation, you might use a bound service or IPC
        Log.d(TAG, "Sending message: $content${channel?.let { " to channel $it" } ?: ""}")
        
        // TODO: Implement actual communication with background service
        // This could be done via:
        // 1. Broadcast intents
        // 2. Bound service with AIDL
        // 3. Shared database/file storage
        // 4. Local socket communication
    }
    
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String) {
        Log.d(TAG, "Sending private message to $recipientNickname: $content")
        // TODO: Implement communication with background service
    }
    
    // MARK: - WifiMeshDelegate Implementation (Receives events from background service)
    
    override fun didReceiveMessage(message: BitchatMessage) {
        Log.d(TAG, "Received message from ${message.sender}: ${message.content}")
        _messageFlow.tryEmit(message)
    }
    
    override fun didConnectToPeer(peerID: String) {
        Log.d(TAG, "Peer connected: $peerID")
        _peerConnectionFlow.tryEmit(PeerConnectionEvent.Connected(peerID))
    }
    
    override fun didDisconnectFromPeer(peerID: String) {
        Log.d(TAG, "Peer disconnected: $peerID")
        _peerConnectionFlow.tryEmit(PeerConnectionEvent.Disconnected(peerID))
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        Log.d(TAG, "Peer list updated: ${peers.size} peers")
        _peerConnectionFlow.tryEmit(PeerConnectionEvent.PeerListUpdated(peers))
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        Log.d(TAG, "Peer $fromPeer left channel $channel")
        _channelLeaveFlow.tryEmit(ChannelLeaveEvent(channel, fromPeer))
    }
    
    override fun didReceiveDeliveryAck(ack: DeliveryAck) {
        Log.d(TAG, "Delivery acknowledgment received for message ${ack.originalMessageID}")
        _deliveryAckFlow.tryEmit(ack)
    }
    
    override fun didReceiveReadReceipt(receipt: ReadReceipt) {
        Log.d(TAG, "Read receipt received for message ${receipt.originalMessageID}")
        _readReceiptFlow.tryEmit(receipt)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        // TODO: Implement channel message decryption if needed
        return null
    }
    
    override fun getNickname(): String? {
        return currentNickname
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return favoritePeers.contains(peerID)
    }
    
    // Voice call delegate methods
    override fun onIncomingVoiceCall(callerNickname: String, callerPeerID: String, callId: String) {
        Log.d(TAG, "Incoming voice call from $callerNickname")
        _voiceCallFlow.tryEmit(VoiceCallEvent.IncomingCall(callerNickname, callerPeerID, callId))
    }
    
    override fun onVoiceCallStateChanged(state: VoiceCallState, callInfo: Map<String, Any>?) {
        Log.d(TAG, "Voice call state changed: $state")
        _voiceCallFlow.tryEmit(VoiceCallEvent.StateChanged(state, callInfo))
    }
    
    override fun onSpeakerphoneToggled(isOn: Boolean) {
        Log.d(TAG, "Speakerphone toggled: $isOn")
        _voiceCallFlow.tryEmit(VoiceCallEvent.SpeakerphoneToggled(isOn))
    }
    
    override fun onMuteToggled(isMuted: Boolean) {
        Log.d(TAG, "Mute toggled: $isMuted")
        _voiceCallFlow.tryEmit(VoiceCallEvent.MuteToggled(isMuted))
    }
}

// Event data classes for UI observation
sealed class PeerConnectionEvent {
    data class Connected(val peerID: String) : PeerConnectionEvent()
    data class Disconnected(val peerID: String) : PeerConnectionEvent()
    data class PeerListUpdated(val peers: List<String>) : PeerConnectionEvent()
}

data class ChannelLeaveEvent(val channel: String, val fromPeer: String)

sealed class VoiceCallEvent {
    data class IncomingCall(val callerNickname: String, val callerPeerID: String, val callId: String) : VoiceCallEvent()
    data class StateChanged(val state: VoiceCallState, val callInfo: Map<String, Any>?) : VoiceCallEvent()
    data class SpeakerphoneToggled(val isOn: Boolean) : VoiceCallEvent()
    data class MuteToggled(val isMuted: Boolean) : VoiceCallEvent()
}