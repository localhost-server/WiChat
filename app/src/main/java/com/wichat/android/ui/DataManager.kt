package com.wichat.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Handles data persistence operations for the chat system
 */
class DataManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DataManager"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Channel-related maps that need to persist state
    private val _channelCreators = mutableMapOf<String, String>()
    private val _favoritePeers = mutableSetOf<String>()
    private val _blockedUsers = mutableSetOf<String>()
    private val _channelMembers = mutableMapOf<String, MutableSet<String>>()
    
    val channelCreators: Map<String, String> get() = _channelCreators
    val favoritePeers: Set<String> get() = _favoritePeers
    val blockedUsers: Set<String> get() = _blockedUsers
    val channelMembers: Map<String, MutableSet<String>> get() = _channelMembers
    
    // MARK: - Nickname Management
    
    fun loadNickname(): String {
        val savedNickname = prefs.getString("nickname", null)
        return if (savedNickname != null) {
            Log.d(TAG, "Using saved nickname: $savedNickname")
            savedNickname
        } else {
            val consistentNickname = generateConsistentNickname()
            saveNickname(consistentNickname)
            Log.i(TAG, "Generated consistent nickname: $consistentNickname")
            consistentNickname
        }
    }
    
    /**
     * Generate a consistent nickname based on device Android ID
     * Format: im + first 6 characters of device ID (e.g., ima1b2c3)
     */
    private fun generateConsistentNickname(): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            
            Log.d(TAG, "Generating consistent nickname from Android ID with 'im' prefix")
            
            if (androidId != null && androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
                // Hash Android ID to get consistent device ID (same method as peer ID)
                val hash = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
                val deviceIdBytes = hash.take(8).toByteArray()
                val fullDeviceId = deviceIdBytes.joinToString("") { "%02x".format(it) }
                
                // Take first 6 characters and add 'im' prefix
                val shortDeviceId = fullDeviceId.take(6)
                val nickname = "im$shortDeviceId"
                
                Log.i(TAG, "Generated nickname: $nickname (from device ID: $fullDeviceId)")
                nickname
            } else {
                // Fallback to stored random nickname
                Log.w(TAG, "Android ID unavailable, using fallback nickname generation")
                generateAndStoreFallbackNickname()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating consistent nickname: ${e.message}")
            generateAndStoreFallbackNickname()
        }
    }
    
    /**
     * Fallback method: Generate and store a random nickname with 'im' format
     */
    private fun generateAndStoreFallbackNickname(): String {
        val fallbackPrefs = context.getSharedPreferences("bitchat_fallback_nickname", Context.MODE_PRIVATE)
        val storedNickname = fallbackPrefs.getString("nickname", null)
        
        return if (storedNickname != null) {
            Log.i(TAG, "Using stored fallback nickname: $storedNickname")
            storedNickname
        } else {
            // Generate random device ID and take first 6 characters with 'im' prefix
            val randomBytes = ByteArray(8)
            Random.nextBytes(randomBytes)
            val randomDeviceId = randomBytes.joinToString("") { "%02x".format(it) }
            val shortDeviceId = randomDeviceId.take(6)
            val fallbackNickname = "im$shortDeviceId"
            
            fallbackPrefs.edit().putString("nickname", fallbackNickname).apply()
            Log.i(TAG, "Generated and stored fallback nickname: $fallbackNickname")
            fallbackNickname
        }
    }
    
    fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname).apply()
        Log.d(TAG, "Saved nickname: $nickname")
    }
    
    /**
     * Get debug information about nickname generation
     */
    fun getNicknameDebugInfo(): String {
        return try {
            val savedNickname = prefs.getString("nickname", null)
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            
            buildString {
                appendLine("=== Nickname Debug Info ===")
                appendLine("Saved nickname: $savedNickname")
                appendLine("Android ID: ${androidId?.take(8)}...")
                if (androidId != null && androidId.isNotEmpty()) {
                    val hash = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
                    val deviceIdBytes = hash.take(8).toByteArray()
                    val fullDeviceId = deviceIdBytes.joinToString("") { "%02x".format(it) }
                    val shortDeviceId = fullDeviceId.take(6)
                    appendLine("Full device ID: $fullDeviceId")
                    appendLine("Short device ID (6 chars): $shortDeviceId")
                    appendLine("Would generate: im$shortDeviceId")
                    appendLine("Format: 'im' + first 6 chars of device ID")
                }
                appendLine("========================")
            }
        } catch (e: Exception) {
            "Nickname debug error: ${e.message}"
        }
    }
    
    /**
     * Force regenerate nickname (for testing or user request)
     */
    fun regenerateNickname(): String {
        // Clear saved nickname
        prefs.edit().remove("nickname").apply()
        // Also clear fallback
        context.getSharedPreferences("bitchat_fallback_nickname", Context.MODE_PRIVATE)
            .edit().clear().apply()
        
        // Generate new one
        val newNickname = loadNickname()
        Log.i(TAG, "Force regenerated nickname: $newNickname")
        return newNickname
    }
    
    // MARK: - Channel Data Management
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        // Load joined channels
        val savedChannels = prefs.getStringSet("joined_channels", emptySet()) ?: emptySet()
        
        // Load password protected channels
        val savedProtectedChannels = prefs.getStringSet("password_protected_channels", emptySet()) ?: emptySet()
        
        // Load channel creators
        val creatorsJson = prefs.getString("channel_creators", "{}")
        try {
            val creatorsMap = gson.fromJson(creatorsJson, Map::class.java) as? Map<String, String>
            creatorsMap?.let { _channelCreators.putAll(it) }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        
        // Initialize channel members for loaded channels
        savedChannels.forEach { channel ->
            if (!_channelMembers.containsKey(channel)) {
                _channelMembers[channel] = mutableSetOf()
            }
        }
        
        return Pair(savedChannels, savedProtectedChannels)
    }
    
    fun saveChannelData(joinedChannels: Set<String>, passwordProtectedChannels: Set<String>) {
        prefs.edit().apply {
            putStringSet("joined_channels", joinedChannels)
            putStringSet("password_protected_channels", passwordProtectedChannels)
            putString("channel_creators", gson.toJson(_channelCreators))
            apply()
        }
    }
    
    fun addChannelCreator(channel: String, creatorID: String) {
        _channelCreators[channel] = creatorID
    }
    
    fun removeChannelCreator(channel: String) {
        _channelCreators.remove(channel)
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return _channelCreators[channel] == peerID
    }
    
    // MARK: - Channel Members Management
    
    fun addChannelMember(channel: String, peerID: String) {
        if (!_channelMembers.containsKey(channel)) {
            _channelMembers[channel] = mutableSetOf()
        }
        _channelMembers[channel]?.add(peerID)
    }
    
    fun removeChannelMember(channel: String, peerID: String) {
        _channelMembers[channel]?.remove(peerID)
    }
    
    fun removeChannelMembers(channel: String) {
        _channelMembers.remove(channel)
    }
    
    fun cleanupDisconnectedMembers(channel: String, connectedPeers: List<String>, myPeerID: String) {
        _channelMembers[channel]?.removeAll { memberID ->
            memberID != myPeerID && !connectedPeers.contains(memberID)
        }
    }
    
    fun cleanupAllDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        _channelMembers.values.forEach { members ->
            members.removeAll { memberID ->
                memberID != myPeerID && !connectedPeers.contains(memberID)
            }
        }
    }
    
    // MARK: - Favorites Management
    
    fun loadFavorites() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        _favoritePeers.addAll(savedFavorites)
        Log.d(TAG, "Loaded ${savedFavorites.size} favorite users from storage: $savedFavorites")
    }
    
    fun saveFavorites() {
        prefs.edit().putStringSet("favorites", _favoritePeers).apply()
        Log.d(TAG, "Saved ${_favoritePeers.size} favorite users to storage: $_favoritePeers")
    }
    
    fun addFavorite(fingerprint: String) {
        val wasAdded = _favoritePeers.add(fingerprint)
        Log.d(TAG, "addFavorite: fingerprint=$fingerprint, wasAdded=$wasAdded")
        saveFavorites()
        logAllFavorites()
    }
    
    fun removeFavorite(fingerprint: String) {
        val wasRemoved = _favoritePeers.remove(fingerprint)
        Log.d(TAG, "removeFavorite: fingerprint=$fingerprint, wasRemoved=$wasRemoved")
        saveFavorites()
        logAllFavorites()
    }
    
    fun isFavorite(fingerprint: String): Boolean {
        val result = _favoritePeers.contains(fingerprint)
        Log.d(TAG, "isFavorite check: fingerprint=$fingerprint, result=$result")
        return result
    }
    
    fun logAllFavorites() {
        Log.i(TAG, "=== ALL FAVORITE USERS ===")
        Log.i(TAG, "Total favorites: ${_favoritePeers.size}")
        _favoritePeers.forEach { fingerprint ->
            Log.i(TAG, "Favorite fingerprint: $fingerprint")
        }
        Log.i(TAG, "========================")
    }
    
    // MARK: - Blocked Users Management
    
    fun loadBlockedUsers() {
        val savedBlockedUsers = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        _blockedUsers.addAll(savedBlockedUsers)
    }
    
    fun saveBlockedUsers() {
        prefs.edit().putStringSet("blocked_users", _blockedUsers).apply()
    }
    
    fun addBlockedUser(fingerprint: String) {
        _blockedUsers.add(fingerprint)
        saveBlockedUsers()
    }
    
    fun removeBlockedUser(fingerprint: String) {
        _blockedUsers.remove(fingerprint)
        saveBlockedUsers()
    }
    
    fun isUserBlocked(fingerprint: String): Boolean {
        return _blockedUsers.contains(fingerprint)
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllData() {
        _channelCreators.clear()
        _favoritePeers.clear()
        _blockedUsers.clear()
        _channelMembers.clear()
        prefs.edit().clear().apply()
    }
}
