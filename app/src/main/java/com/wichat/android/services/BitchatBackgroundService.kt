package com.wichat.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wichat.android.MainActivity
import com.wichat.android.R
import com.wichat.android.model.*
import com.wichat.android.wifi.WifiMeshService
import com.wichat.android.wifi.WifiMeshDelegate
import com.wichat.android.wifi.WifiMeshServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Background foreground service that keeps WiChat running for offline network messaging
 * Maintains network discovery and message reception when app UI is closed
 */
class BitchatBackgroundService : Service(), WifiMeshDelegate {
    
    companion object {
        private const val TAG = "BitchatBackgroundService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "bitchat_background"
        
        const val ACTION_START_SERVICE = "com.wichat.android.START_BACKGROUND_SERVICE"
        const val ACTION_STOP_SERVICE = "com.wichat.android.STOP_BACKGROUND_SERVICE"
        
        fun startService(context: Context) {
            val intent = Intent(context, BitchatBackgroundService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            // Use startForegroundService for API 26+ to prevent system kills
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, BitchatBackgroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
    
    private lateinit var meshServiceManager: WifiMeshServiceManager
    private lateinit var meshService: WifiMeshService
    private lateinit var notificationManager: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isServiceActive = false
    private var connectedPeerCount = 0
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BitchatBackgroundService created")
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Create notification channel
        createNotificationChannel()
        
        // Acquire wake lock for network operations
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BitChat::NetworkWakeLock"
        )
        
        // Use shared service container for memory efficiency
        val serviceContainer = com.wichat.android.core.BitchatServiceContainer.getInstance(applicationContext)
        meshServiceManager = serviceContainer.getMeshServiceManager()
        meshService = serviceContainer.getMeshService()
        meshServiceManager.addDelegate(this@BitchatBackgroundService)
        
        // Setup network monitoring
        setupNetworkMonitoring()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> startForegroundOperation()
            ACTION_STOP_SERVICE -> stopForegroundOperation()
        }
        
        // Return START_STICKY to restart service if killed by system
        // This helps maintain background messaging capability
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BitchatBackgroundService destroyed")
        stopForegroundOperation()
    }
    
    private fun startForegroundOperation() {
        if (isServiceActive) {
            Log.w(TAG, "Service already active")
            return
        }
        
        Log.i(TAG, "Starting foreground service for message delivery")
        
        // Run as foreground service to prevent system kills
        startForeground(NOTIFICATION_ID, createMinimalNotification())
        
        // Acquire wake lock for longer duration
        if (!wakeLock.isHeld) {
            wakeLock.acquire(60*60*1000L /*60 minutes*/)
            Log.d(TAG, "Acquired partial wake lock for 60 minutes")
        }
        
        // Start mesh services
        try {
            meshServiceManager.startServices()
            
            // CRITICAL: Restore Noise encryption sessions for background message delivery
            restoreEncryptionSessions()
            
            isServiceActive = true
            Log.i(TAG, "Foreground service started - message delivery enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh services: ${e.message}")
            stopSelf()
        }
    }
    
    private fun stopForegroundOperation() {
        if (!isServiceActive) return
        
        Log.i(TAG, "Stopping background service")
        
        isServiceActive = false
        
        // CRITICAL: Save Noise encryption sessions before stopping
        saveEncryptionSessions()
        
        // Remove delegate but don't stop services (other components may still need them)
        try {
            meshServiceManager.removeDelegate(this@BitchatBackgroundService)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing delegate: ${e.message}")
        }
        
        // Release wake lock
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "Released wake lock")
        }
        
        // Cleanup network monitoring
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
        }
        
        // Cancel coroutines
        serviceScope.cancel()
        
        // Stop foreground service and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun setupNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available: $network")
                // Trigger peer discovery when network becomes available
                if (isServiceActive) {
                    meshService.sendBroadcastAnnounce()
                    // Don't update notification for network changes
                }
            }
            
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost: $network")
                if (isServiceActive) {
                    connectedPeerCount = 0
                    // Don't update notification for network changes
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                Log.d(TAG, "Network capabilities changed - WiFi: $isWifi, Internet: $hasInternet")
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Registered network callback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BitChat Messages",
                NotificationManager.IMPORTANCE_DEFAULT // Normal importance for messages/calls
            ).apply {
                description = "Notifications for BitChat messages and calls"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createPersistentNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BitChat - Background messaging")
            .setContentText("Ready to receive messages and calls")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun createMinimalNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BitChat Active")
            .setContentText("Ready for offline messages")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)  // Minimal visibility
            .setShowWhen(false)  // Don't show timestamp
            .setSilent(true)     // No sound/vibration
            .build()
    }
    
    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(title: String, content: String) {
        if (isServiceActive) {
            val notification = createNotification(title, content)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
    
    // MARK: - WifiMeshDelegate Implementation
    
    override fun didReceiveMessage(message: BitchatMessage) {
        Log.d(TAG, "Background service received message from ${message.sender}")
        
        // Show notification for new message
        val title = if (message.isPrivate) "Private message from ${message.sender}" else "New message in chat"
        val content = message.content.take(50) + if (message.content.length > 50) "..." else ""
        
        val notificationId = message.senderPeerID.hashCode()
        val messageNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(notificationId, messageNotification)
    }
    
    override fun didConnectToPeer(peerID: String) {
        connectedPeerCount++
        Log.d(TAG, "Peer connected: $peerID (total: $connectedPeerCount)")
        // Don't update notification for peer connections
    }
    
    override fun didDisconnectFromPeer(peerID: String) {
        connectedPeerCount = maxOf(0, connectedPeerCount - 1)
        Log.d(TAG, "Peer disconnected: $peerID (total: $connectedPeerCount)")
        // Don't update notification for peer disconnections
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        connectedPeerCount = peers.size
        Log.d(TAG, "Peer list updated: $connectedPeerCount peers")
        // Don't update notification for peer list changes
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        Log.d(TAG, "Peer $fromPeer left channel $channel")
    }
    
    override fun didReceiveDeliveryAck(ack: DeliveryAck) {
        Log.d(TAG, "Delivery acknowledgment received")
    }
    
    override fun didReceiveReadReceipt(receipt: ReadReceipt) {
        Log.d(TAG, "Read receipt received")
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        // Delegate to main app or return null if no channel decryption available
        return null
    }
    
    override fun getNickname(): String? {
        // Get stored nickname or use peer ID
        val prefs = getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
        return prefs.getString("user_nickname", null)
    }
    
    override fun isFavorite(peerID: String): Boolean {
        // Check if peer is marked as favorite for store-and-forward
        val prefs = getSharedPreferences("bitchat_favorites", Context.MODE_PRIVATE)
        return prefs.getBoolean("favorite_$peerID", false)
    }
    
    // Voice call delegate methods (background service doesn't handle calls directly)
    override fun onIncomingVoiceCall(callerNickname: String, callerPeerID: String, callId: String) {
        Log.i(TAG, "Incoming voice call from $callerNickname - notifying user")
        
        // Create high priority notification for incoming call
        val callNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Incoming call from $callerNickname")
            .setContentText("BitChat voice call")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(callId.hashCode(), callNotification)
    }
    
    override fun onVoiceCallStateChanged(state: VoiceCallState, callInfo: Map<String, Any>?) {
        Log.d(TAG, "Voice call state changed: $state")
    }
    
    override fun onSpeakerphoneToggled(isOn: Boolean) {
        Log.d(TAG, "Speakerphone toggled: $isOn")
    }
    
    override fun onMuteToggled(isMuted: Boolean) {
        Log.d(TAG, "Mute toggled: $isMuted")
    }
    
    // MARK: - Session Persistence for Background Message Delivery
    
    /**
     * Restore Noise encryption sessions when background service starts
     * This enables encrypted private message delivery when app is closed
     */
    private fun restoreEncryptionSessions() {
        Log.i(TAG, "🔄 Restoring Noise encryption sessions for background message delivery")
        
        try {
            // Get the encryption service from mesh service
            val encryptionService = getEncryptionServiceFromMesh()
            if (encryptionService != null) {
                // Restore saved sessions and peer fingerprints
                encryptionService.restoreSessions()
                
                // Set up callback to trigger handshakes for known peers when they come online
                encryptionService.onHandshakeRequired = { peerID ->
                    Log.d(TAG, "🤝 Handshake required with $peerID - initiating from background service")
                    try {
                        meshService.initiateNoiseHandshake(peerID)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initiate handshake with $peerID: ${e.message}")
                    }
                }
                
                Log.i(TAG, "✅ Encryption sessions restored - background message delivery enabled")
                
                // Auto-establish sessions with currently available peers
                serviceScope.launch {
                    kotlinx.coroutines.delay(2000) // Wait for peer discovery to complete
                    
                    val availablePeers = meshService.getPeerNicknames().keys
                    encryptionService.autoEstablishKnownPeerSessions(availablePeers)
                }
                
            } else {
                Log.w(TAG, "⚠️ Could not access encryption service - sessions not restored")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to restore encryption sessions: ${e.message}")
        }
    }
    
    /**
     * Save Noise encryption sessions when background service stops
     * This preserves sessions across app lifecycle changes
     */
    private fun saveEncryptionSessions() {
        Log.i(TAG, "💾 Saving Noise encryption sessions for persistence")
        
        try {
            val encryptionService = getEncryptionServiceFromMesh()
            if (encryptionService != null) {
                encryptionService.saveSessions()
                Log.i(TAG, "✅ Encryption sessions saved successfully")
            } else {
                Log.w(TAG, "⚠️ Could not access encryption service - sessions not saved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save encryption sessions: ${e.message}")
        }
    }
    
    /**
     * Access the EncryptionService from WifiMeshService directly
     * This avoids reflection and ProGuard obfuscation issues
     */
    private fun getEncryptionServiceFromMesh(): com.wichat.android.crypto.EncryptionService? {
        return try {
            // Use the direct accessor method to avoid reflection
            meshService.getEncryptionService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to access encryption service: ${e.message}")
            null
        }
    }
}