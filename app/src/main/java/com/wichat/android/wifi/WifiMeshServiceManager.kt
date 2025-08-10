package com.wichat.android.wifi

import android.content.Context
import android.util.Log
import com.wichat.android.model.*

/**
 * Singleton manager for WifiMeshService to ensure background service and UI share the same instance
 * This fixes the issue where background service and UI have separate mesh service instances
 */
class WifiMeshServiceManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiMeshServiceManager" 
        
        @Volatile
        private var INSTANCE: WifiMeshServiceManager? = null
        
        fun getInstance(context: Context): WifiMeshServiceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WifiMeshServiceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var meshService: WifiMeshService? = null
    private val delegates = mutableSetOf<WifiMeshDelegate>()
    
    /**
     * Get or create the shared mesh service instance
     */
    fun getMeshService(): WifiMeshService {
        if (meshService == null) {
            meshService = WifiMeshService(context).apply {
                // Set up a delegating delegate that forwards to all registered delegates
                delegate = object : WifiMeshDelegate {
                    override fun didReceiveMessage(message: BitchatMessage) {
                        delegates.forEach { it.didReceiveMessage(message) }
                    }
                    
                    override fun didConnectToPeer(peerID: String) {
                        delegates.forEach { it.didConnectToPeer(peerID) }
                    }
                    
                    override fun didDisconnectFromPeer(peerID: String) {
                        delegates.forEach { it.didDisconnectFromPeer(peerID) }
                    }
                    
                    override fun didUpdatePeerList(peers: List<String>) {
                        delegates.forEach { it.didUpdatePeerList(peers) }
                    }
                    
                    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
                        delegates.forEach { it.didReceiveChannelLeave(channel, fromPeer) }
                    }
                    
                    override fun didReceiveDeliveryAck(ack: DeliveryAck) {
                        delegates.forEach { it.didReceiveDeliveryAck(ack) }
                    }
                    
                    override fun didReceiveReadReceipt(receipt: ReadReceipt) {
                        delegates.forEach { it.didReceiveReadReceipt(receipt) }
                    }
                    
                    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                        // Use the first delegate that can decrypt, or return null
                        delegates.forEach { delegate ->
                            val result = delegate.decryptChannelMessage(encryptedContent, channel)
                            if (result != null) return result
                        }
                        return null
                    }
                    
                    override fun getNickname(): String? {
                        // Use the first non-null nickname from delegates
                        delegates.forEach { delegate ->
                            val nickname = delegate.getNickname()
                            if (nickname != null) return nickname
                        }
                        return null
                    }
                    
                    override fun isFavorite(peerID: String): Boolean {
                        // Return true if any delegate considers the peer a favorite
                        return delegates.any { it.isFavorite(peerID) }
                    }
                    
                    // Voice call methods
                    override fun onIncomingVoiceCall(callerNickname: String, callerPeerID: String, callId: String) {
                        delegates.forEach { it.onIncomingVoiceCall(callerNickname, callerPeerID, callId) }
                    }
                    
                    override fun onVoiceCallStateChanged(state: VoiceCallState, callInfo: Map<String, Any>?) {
                        delegates.forEach { it.onVoiceCallStateChanged(state, callInfo) }
                    }
                    
                    override fun onSpeakerphoneToggled(isOn: Boolean) {
                        delegates.forEach { it.onSpeakerphoneToggled(isOn) }
                    }
                    
                    override fun onMuteToggled(isMuted: Boolean) {
                        delegates.forEach { it.onMuteToggled(isMuted) }
                    }
                }
            }
            Log.i(TAG, "Created shared WifiMeshService instance")
        }
        return meshService!!
    }
    
    /**
     * Add a delegate to receive mesh service events
     */
    fun addDelegate(delegate: WifiMeshDelegate) {
        delegates.add(delegate)
        Log.d(TAG, "Added delegate - total delegates: ${delegates.size}")
    }
    
    /**
     * Remove a delegate
     */
    fun removeDelegate(delegate: WifiMeshDelegate) {
        delegates.remove(delegate)
        Log.d(TAG, "Removed delegate - total delegates: ${delegates.size}")
    }
    
    /**
     * Start mesh services if not already started
     */
    fun startServices(): Boolean {
        val service = getMeshService()
        service.startServices()
        Log.i(TAG, "Started mesh services through singleton manager")
        return true
    }
    
    /**
     * Stop mesh services (called when app is completely shutting down)
     */
    fun stopServices() {
        meshService?.stopServices()
        Log.i(TAG, "Stopped mesh services through singleton manager")
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== WifiMeshServiceManager Status ===")
            appendLine("Mesh Service Created: ${meshService != null}")
            appendLine("Active Delegates: ${delegates.size}")
            appendLine()
            if (meshService != null) {
                appendLine(meshService!!.getDebugStatus())
            } else {
                appendLine("No mesh service instance created yet")
            }
        }
    }
}