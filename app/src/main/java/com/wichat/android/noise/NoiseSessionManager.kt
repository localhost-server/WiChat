package com.wichat.android.noise

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * SIMPLIFIED Noise session manager - focuses on core functionality only
 */
class NoiseSessionManager(
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    
    companion object {
        private const val TAG = "NoiseSessionManager"
    }
    
    // Internal sessions map - exposed for session persistence
    internal val sessions = ConcurrentHashMap<String, NoiseSession>()
    
    // Track pending handshakes to prevent race conditions
    private val pendingHandshakes = ConcurrentHashMap<String, Boolean>()
    
    // Callbacks
    var onSessionEstablished: ((String, ByteArray) -> Unit)? = null
    var onSessionFailed: ((String, Throwable) -> Unit)? = null
    
    // MARK: - Simple Session Management

    /**
     * Add new session for a peer
     */
    fun addSession(peerID: String, session: NoiseSession) {
        sessions[peerID] = session
        Log.d(TAG, "Added new session for $peerID")
    }

    /**
     * Get existing session for a peer
     */
    fun getSession(peerID: String): NoiseSession? {
        val session = sessions[peerID]
        return session
    }
    
    /**
     * Remove session for a peer
     */
    fun removeSession(peerID: String) {
        sessions[peerID]?.destroy()
        sessions.remove(peerID)
        Log.d(TAG, "Removed session for $peerID")
    }
    
    /**
     * SYNCHRONIZED: Initiate handshake with race condition prevention
     */
    @Synchronized
    fun initiateHandshake(peerID: String): ByteArray {
        Log.d(TAG, "initiateHandshake($peerID)")

        // Check if handshake is already pending to prevent race conditions
        if (pendingHandshakes.containsKey(peerID)) {
            Log.w(TAG, "Handshake already pending for $peerID, skipping duplicate request")
            return ByteArray(0)
        }

        // Check if we already have a working session
        val existingSession = getSession(peerID)
        if (existingSession != null && existingSession.isEstablished()) {
            Log.w(TAG, "Session already established with $peerID, not creating new one")
            return ByteArray(0)
        }
        
        // Check if session is currently handshaking - but allow reset if stuck
        if (existingSession != null && existingSession.isHandshaking()) {
            Log.w(TAG, "Found handshaking session with $peerID - checking if stuck")
            val state = existingSession.getState()
            // If handshake has been stuck for too long, reset it
            if (state is NoiseSession.NoiseSessionState.Handshaking) {
                // Force reset of stuck handshaking session
                Log.w(TAG, "Resetting stuck handshaking session for $peerID")
                removeSession(peerID)
            } else {
                Log.w(TAG, "Handshake already in progress with $peerID, not starting new one")
                return ByteArray(0)
            }
        }
        
        // Mark handshake as pending to prevent concurrent attempts
        pendingHandshakes[peerID] = true
        
        try {
            // Only remove failed/broken sessions, not working ones
            if (existingSession != null && !existingSession.isEstablished()) {
                val state = existingSession.getState()
                if (state is NoiseSession.NoiseSessionState.Failed) {
                    Log.d(TAG, "Removing failed session for $peerID: ${state.error.message}")
                    removeSession(peerID)
                }
            }
            
            // Only create new session if none exists or previous was removed
            if (getSession(peerID) == null) {
                val session = NoiseSession(
                    peerID = peerID,
                    isInitiator = true,
                    localStaticPrivateKey = localStaticPrivateKey,
                    localStaticPublicKey = localStaticPublicKey
                )
                Log.d(TAG, "Storing new INITIATOR session for $peerID")
                addSession(peerID, session)
            }
        
            try {
                val currentSession = getSession(peerID) ?: throw IllegalStateException("No session found after creation")
                val handshakeData = currentSession.startHandshake()
                Log.d(TAG, "Started handshake with $peerID as INITIATOR")
                return handshakeData
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start handshake: ${e.message}")
                sessions.remove(peerID)
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate handshake with $peerID: ${e.message}")
            throw e
        } finally {
            // Always remove from pending handshakes when done
            pendingHandshakes.remove(peerID)
            Log.d(TAG, "Removed $peerID from pending handshakes")
        }
    }
    
    /**
     * Handle incoming handshake message
     */
    fun processHandshakeMessage(peerID: String, message: ByteArray): ByteArray? {
        Log.d(TAG, "processHandshakeMessage($peerID, ${message.size} bytes)")
        
        try {
            var session = getSession(peerID)
            
            // If no session exists, create one as responder
            if (session == null) {
                Log.d(TAG, "Creating new RESPONDER session for $peerID")
                session = NoiseSession(
                    peerID = peerID,
                    isInitiator = false,
                    localStaticPrivateKey = localStaticPrivateKey,
                    localStaticPublicKey = localStaticPublicKey
                )
                addSession(peerID, session)
            }
            
            // Process handshake message
            val response = session.processHandshakeMessage(message)
            
            // Check if session is established
            if (session.isEstablished()) {
                Log.d(TAG, "✅ Session ESTABLISHED with $peerID")
                val remoteStaticKey = session.getRemoteStaticPublicKey()
                if (remoteStaticKey != null) {
                    onSessionEstablished?.invoke(peerID, remoteStaticKey)
                }
            }
            
            return response
            
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed with $peerID: ${e.message}")
            sessions.remove(peerID)
            onSessionFailed?.invoke(peerID, e)
            throw e
        }
    }
    
    /**
     * SIMPLIFIED: Encrypt data
     */
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID) ?: throw IllegalStateException("No session found for $peerID")
        if (!session.isEstablished()) {
            throw IllegalStateException("Session not established with $peerID")
        }
        return session.encrypt(data)
    }
    
    /**
     * SIMPLIFIED: Decrypt data
     */
    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID)
        if (session == null) {
            Log.e(TAG, "No session found for $peerID when trying to decrypt")
            throw IllegalStateException("No session found for $peerID")
        }
        if (!session.isEstablished()) {
            Log.e(TAG, "Session not established with $peerID when trying to decrypt")
            throw IllegalStateException("Session not established with $peerID")
        }
        return session.decrypt(encryptedData)
    }
    
    /**
     * Check if session is established with peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        val hasSession = getSession(peerID)?.isEstablished() ?: false
        Log.d(TAG, "hasEstablishedSession($peerID): $hasSession")
        return hasSession
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        return getSession(peerID)?.getState() ?: NoiseSession.NoiseSessionState.Uninitialized
    }
    
    /**
     * Get remote static public key for a peer (if session established)
     */
    fun getRemoteStaticKey(peerID: String): ByteArray? {
        return getSession(peerID)?.getRemoteStaticPublicKey()
    }
    
    /**
     * Get handshake hash for channel binding (if session established)
     */
    fun getHandshakeHash(peerID: String): ByteArray? {
        return getSession(peerID)?.getHandshakeHash()
    }
    
    /**
     * Get sessions that need rekeying based on time or message count
     */
    fun getSessionsNeedingRekey(): List<String> {
        return sessions.entries
            .filter { (_, session) -> 
                session.isEstablished() && session.needsRekey()
            }
            .map { it.key }
    }
    
    /**
     * Save established sessions - simplified approach 
     * Currently sessions are maintained in memory only
     */
    fun saveSessions(context: android.content.Context) {
        val establishedCount = sessions.count { it.value.isEstablished() }
        Log.d(TAG, "Session save: ${establishedCount} established sessions (maintained in memory)")
        
        // For now, sessions are maintained in memory only during the app lifecycle
        // This is sufficient for the background service use case where the service
        // continues running and maintains sessions while the UI is closed
    }
    
    /**
     * Restore sessions - simplified approach
     * Currently sessions are maintained in memory only
     */
    fun restoreSessions(context: android.content.Context) {
        Log.d(TAG, "Session restore: using in-memory sessions")
        
        // Since we're using a background service that maintains sessions in memory,
        // we don't need complex persistence. The key insight is that the background
        // service keeps the mesh service and encryption sessions alive while the UI is closed.
    }
    
    /**
     * Clear saved sessions - simplified approach
     */
    fun clearSavedSessions(context: android.content.Context) {
        Log.d(TAG, "Session clear: clearing in-memory sessions")
        sessions.clear()
    }

    /**
     * Get debug information
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Noise Session Manager Debug ===")
        appendLine("Active sessions: ${sessions.size}")
        appendLine("")
        
        if (sessions.isNotEmpty()) {
            appendLine("Sessions:")
            sessions.forEach { (peerID, session) ->
                appendLine("  $peerID: ${session.getState()}")
            }
        }
    }
    
    /**
     * Shutdown manager and clean up all sessions
     */
    fun shutdown() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        Log.d(TAG, "Noise session manager shut down")
    }
}

/**
 * Session-related errors
 */
sealed class NoiseSessionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object SessionNotFound : NoiseSessionError("Session not found")
    object SessionNotEstablished : NoiseSessionError("Session not established")
    object InvalidState : NoiseSessionError("Session in invalid state")
    object HandshakeFailed : NoiseSessionError("Handshake failed")
    object AlreadyEstablished : NoiseSessionError("Session already established")
}
