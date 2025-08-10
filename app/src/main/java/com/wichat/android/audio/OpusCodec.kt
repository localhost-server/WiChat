package com.wichat.android.audio

import android.util.Log
import com.wichat.android.model.VoiceCallOffer

/**
 * Native Opus codec wrapper for voice call audio compression
 * 
 * Uses native Opus 1.5.2 library built from source for maximum
 * performance and latest features in mesh network transmission.
 */
class OpusCodec(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitRate: Int = 12000,
    private val complexity: Int = 5,
    private val useVBR: Boolean = true,
    private val useDTX: Boolean = true
) {
    
    companion object {
        private const val TAG = "OpusCodec"
        
        // Opus frame sizes in samples (at 16kHz)
        private const val FRAME_SIZE_20MS = 320  // 20ms * 16kHz = 320 samples
        private const val MAX_ENCODED_SIZE = 1024 // Maximum encoded frame size
        
        // Opus application types (from opus_defines.h)
        private const val OPUS_APPLICATION_VOIP = 2048
        private const val OPUS_APPLICATION_AUDIO = 2049  
        private const val OPUS_APPLICATION_RESTRICTED_LOWDELAY = 2051
        
        // Load native library
        init {
            try {
                System.loadLibrary("opuscodec")
                Log.i(TAG, "✅ Native Opus library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Failed to load native Opus library: ${e.message}")
                throw e
            }
        }
        
        // Native method declarations
        @JvmStatic external fun nativeInitEncoder(
            sampleRate: Int, channels: Int, application: Int,
            bitrate: Int, complexity: Int, vbr: Boolean, dtx: Boolean
        ): Long
        
        @JvmStatic external fun nativeInitDecoder(sampleRate: Int, channels: Int): Long
        @JvmStatic external fun nativeEncode(encoderHandle: Long, pcmData: ByteArray, frameSize: Int): ByteArray?
        @JvmStatic external fun nativeDecode(decoderHandle: Long, opusData: ByteArray, frameSize: Int): ByteArray?
        @JvmStatic external fun nativeGetVersion(): String
        @JvmStatic external fun nativeReleaseEncoder(encoderHandle: Long)
        @JvmStatic external fun nativeReleaseDecoder(decoderHandle: Long)
    }
    
    private var encoderHandle: Long = 0
    private var decoderHandle: Long = 0
    private var isInitialized = false
    
    /**
     * Initialize the Opus codec with given parameters
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.w(TAG, "Opus codec already initialized")
            return true
        }
        
        return try {
            Log.i(TAG, "🚀 Initializing native Opus codec:")
            Log.i(TAG, "  Sample Rate: ${sampleRate}Hz")
            Log.i(TAG, "  Channels: $channels")
            Log.i(TAG, "  Bit Rate: ${bitRate}bps")
            Log.i(TAG, "  Complexity: $complexity")
            Log.i(TAG, "  VBR: $useVBR")
            Log.i(TAG, "  DTX: $useDTX")
            
            // Get Opus version info
            val version = nativeGetVersion()
            Log.i(TAG, "📋 Using $version")
            
            // Initialize native encoder
            encoderHandle = nativeInitEncoder(
                sampleRate, channels, OPUS_APPLICATION_VOIP,
                bitRate, complexity, useVBR, useDTX
            )
            
            if (encoderHandle == 0L) {
                Log.e(TAG, "❌ Failed to initialize native encoder")
                return false
            }
            
            // Initialize native decoder
            decoderHandle = nativeInitDecoder(sampleRate, channels)
            
            if (decoderHandle == 0L) {
                Log.e(TAG, "❌ Failed to initialize native decoder")
                nativeReleaseEncoder(encoderHandle)
                encoderHandle = 0
                return false
            }
                
            isInitialized = true
            Log.i(TAG, "✅ Native Opus codec initialized successfully")
            Log.i(TAG, "🎯 Encoder handle: $encoderHandle, Decoder handle: $decoderHandle")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize native Opus codec: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Initialize from VoiceCallOffer parameters
     */
    fun initializeFromOffer(offer: VoiceCallOffer): Boolean {
        return OpusCodec(
            sampleRate = offer.sampleRate,
            channels = 1,
            bitRate = offer.bitRate,
            complexity = offer.complexity,
            useVBR = offer.useVBR,
            useDTX = offer.useDTX
        ).initialize()
    }
    
    /**
     * Encode PCM audio data to Opus
     * 
     * @param pcmData Raw PCM 16-bit audio data
     * @return Compressed Opus data, or null if encoding fails
     */
    fun encode(pcmData: ByteArray): ByteArray? {
        if (!isInitialized || encoderHandle == 0L) {
            Log.e(TAG, "❌ Native Opus encoder not initialized")
            return null
        }
        
        return try {
            // Ensure we have the right frame size (320 samples * 2 bytes = 640 bytes for 20ms at 16kHz)
            val expectedBytes = FRAME_SIZE_20MS * 2
            if (pcmData.size != expectedBytes) {
                Log.w(TAG, "⚠️ PCM frame size mismatch: ${pcmData.size} vs expected $expectedBytes bytes")
                // Pad or truncate to correct size
                val correctedFrame = ByteArray(expectedBytes)
                val copySize = minOf(pcmData.size, expectedBytes)
                System.arraycopy(pcmData, 0, correctedFrame, 0, copySize)
                
                val encoded = nativeEncode(encoderHandle, correctedFrame, FRAME_SIZE_20MS)
                if (encoded != null) {
                    Log.v(TAG, "🎤 Encoded ${correctedFrame.size} PCM bytes → ${encoded.size} Opus bytes (${(encoded.size * 100 / correctedFrame.size)}%)")
                }
                return encoded
            }
            
            val encoded = nativeEncode(encoderHandle, pcmData, FRAME_SIZE_20MS)
            if (encoded != null) {
                Log.v(TAG, "🎤 Encoded ${pcmData.size} PCM bytes → ${encoded.size} Opus bytes (${(encoded.size * 100 / pcmData.size)}%)")
            }
            encoded
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Native Opus encoding failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Decode Opus data to PCM audio
     * 
     * @param opusData Compressed Opus audio data
     * @return Raw PCM 16-bit audio data, or null if decoding fails
     */
    fun decode(opusData: ByteArray): ByteArray? {
        if (!isInitialized || decoderHandle == 0L) {
            Log.e(TAG, "❌ Native Opus decoder not initialized")
            return null
        }
        
        return try {
            val pcmData = nativeDecode(decoderHandle, opusData, FRAME_SIZE_20MS)
            if (pcmData != null) {
                Log.v(TAG, "🔊 Decoded ${opusData.size} Opus bytes → ${pcmData.size} PCM bytes")
            }
            pcmData
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Native Opus decoding failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Get the expected encoded frame size for current settings
     */
    fun getMaxEncodedFrameSize(): Int = MAX_ENCODED_SIZE
    
    /**
     * Get the PCM frame size in bytes (20ms at 16kHz, 16-bit, mono)
     */
    fun getPCMFrameSize(): Int = FRAME_SIZE_20MS * 2 // 2 bytes per sample
    
    /**
     * Release codec resources
     */
    fun release() {
        try {
            if (encoderHandle != 0L) {
                nativeReleaseEncoder(encoderHandle)
                encoderHandle = 0
            }
            
            if (decoderHandle != 0L) {
                nativeReleaseDecoder(decoderHandle)
                decoderHandle = 0
            }
            
            isInitialized = false
            Log.i(TAG, "🧹 Native Opus codec released")
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error releasing native Opus codec: ${e.message}")
        }
    }
    
    /**
     * Get codec information for debugging
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Native Opus Codec Info ===")
            appendLine("Initialized: $isInitialized")
            try {
                appendLine("Opus Version: ${nativeGetVersion()}")
            } catch (e: Exception) {
                appendLine("Opus Version: Error getting version")
            }
            appendLine("Encoder Handle: $encoderHandle")
            appendLine("Decoder Handle: $decoderHandle")
            appendLine("Sample Rate: ${sampleRate}Hz")
            appendLine("Channels: $channels")
            appendLine("Bit Rate: ${bitRate}bps")
            appendLine("Complexity: $complexity")
            appendLine("VBR: $useVBR")
            appendLine("DTX: $useDTX")
            appendLine("PCM Frame Size: ${getPCMFrameSize()} bytes")
            appendLine("Max Encoded Size: $MAX_ENCODED_SIZE bytes")
        }
    }
}