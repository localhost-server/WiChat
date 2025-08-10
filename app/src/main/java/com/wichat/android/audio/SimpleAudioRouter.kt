package com.wichat.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Simple and direct audio routing for voice calls
 * Forces Android to use the best available audio device
 */
class SimpleAudioRouter(private val context: Context) {
    
    companion object {
        private const val TAG = "SimpleAudioRouter"
    }
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    fun setupForVoiceCall() {
        Log.i(TAG, "Setting up audio for voice call")
        
        try {
            // Set audio mode for communication
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            // Request audio focus for voice call
            requestAudioFocus()
            
            // Detect and route to best audio device
            routeToBestAudioDevice()
            
            Log.i(TAG, "Audio setup complete - Mode: ${audioManager.mode}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup audio: ${e.message}")
        }
    }
    
    fun cleanup() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            abandonAudioFocus()
            Log.i(TAG, "Audio cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during audio cleanup: ${e.message}")
        }
    }
    
    private fun routeToBestAudioDevice() {
        // Force speakerphone OFF first - this makes Android route to earpiece/headset
        audioManager.isSpeakerphoneOn = false
        Log.i(TAG, "🔇 Speakerphone disabled - routing to earpiece/headset to prevent echo")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check available audio devices (API 23+)
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            
            for (device in outputDevices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        Log.i(TAG, "Found wired headset: ${device.productName}")
                        // Android should automatically route to wired headset when speaker is off
                        return
                    }
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        Log.i(TAG, "Found Bluetooth device: ${device.productName}")
                        audioManager.isBluetoothScoOn = true
                        audioManager.startBluetoothSco()
                        return
                    }
                }
            }
        }
        
        // Fallback: Use earpiece (built-in receiver)
        Log.i(TAG, "Using earpiece (built-in receiver)")
    }
    
    fun toggleSpeakerphone(): Boolean {
        val wasOn = audioManager.isSpeakerphoneOn
        
        if (wasOn) {
            // Turn off speaker, route to best available device
            audioManager.isSpeakerphoneOn = false
            routeToBestAudioDevice()
        } else {
            // Turn on speaker
            audioManager.isSpeakerphoneOn = true
            audioManager.isBluetoothScoOn = false
        }
        
        val isNowOn = audioManager.isSpeakerphoneOn
        Log.i(TAG, "Speaker toggled: $wasOn -> $isNowOn")
        return isNowOn
    }
    
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            
            val result = audioManager.requestAudioFocus(focusRequest)
            Log.d(TAG, "Audio focus request result: $result")
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
            Log.d(TAG, "Audio focus request result (legacy): $result")
        }
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Would need to store the focus request to abandon it properly
            // For simplicity, we'll just log
            Log.d(TAG, "Should abandon audio focus")
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
    
    fun getAudioInfo(): String {
        return buildString {
            appendLine("=== Audio Routing Info ===")
            appendLine("Audio Mode: ${audioManager.mode}")
            appendLine("Speakerphone: ${audioManager.isSpeakerphoneOn}")
            appendLine("Bluetooth SCO: ${audioManager.isBluetoothScoOn}")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                appendLine()
                appendLine("Available Output Devices:")
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (device in devices) {
                    val typeName = when (device.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in Earpiece"
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                        else -> "Unknown (${device.type})"
                    }
                    appendLine("  - $typeName: ${device.productName}")
                }
            }
        }
    }
}