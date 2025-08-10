package com.wichat.android.core

import android.content.Context
import android.util.Log
import com.wichat.android.wifi.WifiMeshService
import com.wichat.android.wifi.WifiMeshServiceManager
import com.wichat.android.ui.MessageManager
import com.wichat.android.ui.ChannelManager
import com.wichat.android.ui.PrivateChatManager
import com.wichat.android.ui.NotificationManager
import com.wichat.android.ui.DataManager
import com.wichat.android.ui.ChatState
import com.wichat.android.ui.NoiseSessionDelegate
import com.wichat.android.audio.AudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.lang.ref.WeakReference

/**
 * Centralized service container for memory-efficient resource sharing
 * Implements singleton pattern with proper lifecycle management
 */
class BitchatServiceContainer private constructor() {
    
    companion object {
        private const val TAG = "BitchatServiceContainer"
        
        @Volatile
        private var INSTANCE: BitchatServiceContainer? = null
        private val contextRef = WeakReference<Context>(null)
        
        fun getInstance(context: Context): BitchatServiceContainer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BitchatServiceContainer().also { 
                    INSTANCE = it 
                    it.initialize(context.applicationContext)
                }
            }
        }
        
        fun cleanup() {
            synchronized(this) {
                INSTANCE?.onDestroy()
                INSTANCE = null
            }
        }
    }
    
    // Shared instances - created only once
    private var meshServiceManager: WifiMeshServiceManager? = null
    private var meshService: WifiMeshService? = null
    private var chatState: ChatState? = null
    private var dataManager: DataManager? = null
    private var messageManager: MessageManager? = null
    private var channelManager: ChannelManager? = null
    private var privateChatManager: PrivateChatManager? = null
    private var notificationManager: NotificationManager? = null
    private var audioEngine: AudioEngine? = null
    
    // Shared coroutine scope for all operations
    private val sharedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private fun initialize(context: Context) {
        Log.i(TAG, "🏗️ Initializing shared service container")
        
        // Create shared state (only once)
        chatState = ChatState()
        
        // Create shared data manager (only once)  
        dataManager = DataManager(context)
        
        // Create shared mesh service (only once)
        meshServiceManager = WifiMeshServiceManager.getInstance(context)
        meshService = meshServiceManager?.getMeshService()
        
        // Create shared notification manager (only once)
        notificationManager = NotificationManager(context)
        
        Log.i(TAG, "✅ Shared service container initialized")
    }
    
    /**
     * Get shared mesh service - same instance for UI and background
     */
    fun getMeshService(): WifiMeshService {
        return meshService ?: throw IllegalStateException("Service container not initialized")
    }
    
    /**
     * Get shared mesh service manager - same instance for UI and background
     */
    fun getMeshServiceManager(): WifiMeshServiceManager {
        return meshServiceManager ?: throw IllegalStateException("Service container not initialized")
    }
    
    /**
     * Get shared chat state - same instance for UI and background
     */
    fun getChatState(): ChatState {
        return chatState ?: throw IllegalStateException("Service container not initialized")
    }
    
    /**
     * Get shared data manager - same instance for UI and background
     */
    fun getDataManager(): DataManager {
        return dataManager ?: throw IllegalStateException("Service container not initialized")
    }
    
    /**
     * Get shared message manager - created lazily, reused
     */
    fun getMessageManager(): MessageManager {
        if (messageManager == null) {
            messageManager = MessageManager(getChatState())
            Log.d(TAG, "📝 Created shared MessageManager")
        }
        return messageManager!!
    }
    
    /**
     * Get shared channel manager - created lazily, reused
     */
    fun getChannelManager(): ChannelManager {
        if (channelManager == null) {
            channelManager = ChannelManager(
                getChatState(), 
                getMessageManager(), 
                getDataManager(), 
                sharedScope
            )
            Log.d(TAG, "📺 Created shared ChannelManager")
        }
        return channelManager!!
    }
    
    /**
     * Get shared private chat manager - created lazily, reused
     */
    fun getPrivateChatManager(): PrivateChatManager {
        if (privateChatManager == null) {
            val noiseSessionDelegate = object : NoiseSessionDelegate {
                override fun hasEstablishedSession(peerID: String): Boolean = getMeshService().hasEstablishedSession(peerID)
                override fun initiateHandshake(peerID: String) = getMeshService().initiateNoiseHandshake(peerID)
                override fun broadcastNoiseIdentityAnnouncement() = getMeshService().broadcastNoiseIdentityAnnouncement()
                override fun sendHandshakeRequest(targetPeerID: String, pendingCount: UByte) = getMeshService().sendHandshakeRequest(targetPeerID, pendingCount)
                override fun getMyPeerID(): String = getMeshService().myPeerID
            }
            
            privateChatManager = PrivateChatManager(
                getChatState(),
                getMessageManager(), 
                getDataManager(), 
                noiseSessionDelegate
            )
            Log.d(TAG, "💬 Created shared PrivateChatManager")
        }
        return privateChatManager!!
    }
    
    /**
     * Get shared notification manager - same instance for UI and background
     */
    fun getNotificationManager(): NotificationManager {
        return notificationManager ?: throw IllegalStateException("Service container not initialized")
    }
    
    /**
     * Get shared audio engine - created lazily, reused for all calls
     */
    fun getAudioEngine(context: Context): AudioEngine {
        if (audioEngine == null) {
            audioEngine = AudioEngine(context)
            Log.d(TAG, "🎵 Created shared AudioEngine")
        }
        return audioEngine!!
    }
    
    /**
     * Get shared coroutine scope - same scope for all background operations
     */
    fun getSharedScope(): CoroutineScope = sharedScope
    
    /**
     * Get memory usage statistics
     */
    fun getMemoryStats(): String {
        return buildString {
            appendLine("=== BitchatServiceContainer Memory Stats ===")
            appendLine("Mesh Service: ${if (meshService != null) "✅ Shared" else "❌ Missing"}")
            appendLine("Chat State: ${if (chatState != null) "✅ Shared" else "❌ Missing"}")
            appendLine("Data Manager: ${if (dataManager != null) "✅ Shared" else "❌ Missing"}")
            appendLine("Message Manager: ${if (messageManager != null) "✅ Shared" else "⏳ Lazy"}")
            appendLine("Channel Manager: ${if (channelManager != null) "✅ Shared" else "⏳ Lazy"}")
            appendLine("Private Chat Manager: ${if (privateChatManager != null) "✅ Shared" else "⏳ Lazy"}")
            appendLine("Notification Manager: ${if (notificationManager != null) "✅ Shared" else "❌ Missing"}")
            appendLine("Audio Engine: ${if (audioEngine != null) "✅ Shared" else "⏳ Lazy"}")
            appendLine("Shared Scope: ✅ Active")
            
            // Memory usage estimation
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            appendLine("Used Memory: ${usedMemory / 1024 / 1024} MB")
            appendLine("Total Memory: ${runtime.totalMemory() / 1024 / 1024} MB")
            appendLine("Free Memory: ${runtime.freeMemory() / 1024 / 1024} MB")
        }
    }
    
    /**
     * Cleanup resources on app termination
     */
    private fun onDestroy() {
        Log.i(TAG, "🧹 Cleaning up service container")
        
        try {
            audioEngine?.shutdown()
            audioEngine = null
            
            sharedScope.cancel()
            
            // Clear managers
            messageManager = null
            channelManager = null
            privateChatManager = null
            
            // Clear core services
            meshService = null
            meshServiceManager = null
            chatState = null
            dataManager = null
            notificationManager = null
            
            Log.i(TAG, "✅ Service container cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup: ${e.message}")
        }
    }
}