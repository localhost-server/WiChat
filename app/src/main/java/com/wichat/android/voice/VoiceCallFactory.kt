package com.wichat.android.voice

import android.content.Context

/**
 * Factory for creating voice call managers
 * Allows easy switching between TCP and UDP implementations
 */
object VoiceCallFactory {
    
    enum class VoiceCallType {
        TCP_LEGACY     // Original TCP-based implementation (UDP removed due to duplication)
    }
    
    /**
     * Create a voice call manager based on the specified type
     */
    fun createVoiceCallManager(
        type: VoiceCallType,
        context: Context,
        myPeerID: String,
        myNickname: String
    ): VoiceCallManager {
        return when (type) {
            VoiceCallType.TCP_LEGACY -> {
                VoiceCallManager(context, myPeerID, myNickname)
            }
        }
    }
    
    /**
     * Get recommended voice call type based on device capabilities and preferences
     */
    fun getRecommendedType(context: Context): VoiceCallType {
        // In production, you might check:
        // - Device capabilities (Android version, hardware specs)
        // - User preferences
        // - Network conditions
        // - Battery level
        
        return VoiceCallType.TCP_LEGACY // Only TCP implementation available
    }
    
    /**
     * Check if voice call features are available
     */
    fun isVoiceCallAvailable(context: Context): Boolean {
        return try {
            // Check if required APIs are available
            android.os.Build.VERSION.SDK_INT >= 26 && // API 26+ for better audio APIs
            context.packageManager.hasSystemFeature("android.hardware.microphone")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get available features for voice calls
     */
    fun getAvailableFeatures(): Map<String, Boolean> {
        return mapOf(
            "Voice calls" to true,
            "Echo cancellation" to true,
            "Noise suppression" to true,
            "Automatic gain control" to true,
            "Speakerphone support" to true,
            "Mute support" to true
        )
    }
}