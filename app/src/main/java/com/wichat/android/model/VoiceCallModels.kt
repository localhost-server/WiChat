package com.wichat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Voice call state management
 */
enum class VoiceCallState {
    IDLE,           // No active call
    CALLING,        // Outgoing call initiated
    RINGING,        // Incoming call received
    CONNECTING,     // Call accepted, establishing connection
    ACTIVE,         // Call in progress
    ENDING,         // Call termination in progress
    ENDED           // Call completed
}

/**
 * Voice call offer message
 */
@Parcelize
data class VoiceCallOffer(
    val callId: String = UUID.randomUUID().toString(),
    val callerPeerID: String,
    val callerNickname: String,
    val timestamp: Long = System.currentTimeMillis(),
    val audioCodec: String = "OPUS",  // Opus codec implementation
    val sampleRate: Int = 16000,      // 16kHz sample rate (Opus native)
    val bitRate: Int = 12000,         // 12kbps for mesh networking (was 32000 raw PCM)
    val frameSize: Int = 20,          // 20ms frames (Opus standard)
    val complexity: Int = 5,          // Opus complexity (0-10, 5 is balanced)
    val useVBR: Boolean = true,       // Variable bitrate for better quality
    val useDTX: Boolean = true        // Discontinuous transmission (silence detection)
) : Parcelable {
    
    fun toBinaryData(): ByteArray {
        val buffer = ByteBuffer.allocate(512).apply { order(ByteOrder.BIG_ENDIAN) }
        
        // Call ID
        val callIdBytes = callId.toByteArray(Charsets.UTF_8)
        buffer.put(callIdBytes.size.toByte())
        buffer.put(callIdBytes)
        
        // Caller Peer ID
        val peerIdBytes = callerPeerID.toByteArray(Charsets.UTF_8)
        buffer.put(peerIdBytes.size.toByte())
        buffer.put(peerIdBytes)
        
        // Caller Nickname
        val nicknameBytes = callerNickname.toByteArray(Charsets.UTF_8)
        buffer.put(nicknameBytes.size.toByte())
        buffer.put(nicknameBytes)
        
        // Timestamp
        buffer.putLong(timestamp)
        
        // Audio codec
        val codecBytes = audioCodec.toByteArray(Charsets.UTF_8)
        buffer.put(codecBytes.size.toByte())
        buffer.put(codecBytes)
        
        // Sample rate and bit rate
        buffer.putInt(sampleRate)
        buffer.putInt(bitRate)
        
        // Opus-specific parameters
        buffer.putInt(frameSize)
        buffer.putInt(complexity)
        buffer.put(if (useVBR) 1.toByte() else 0.toByte())
        buffer.put(if (useDTX) 1.toByte() else 0.toByte())
        
        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }
    
    companion object {
        fun fromBinaryData(data: ByteArray): VoiceCallOffer? {
            try {
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }
                
                // Call ID
                val callIdLength = buffer.get().toInt() and 0xFF
                val callIdBytes = ByteArray(callIdLength)
                buffer.get(callIdBytes)
                val callId = String(callIdBytes, Charsets.UTF_8)
                
                // Caller Peer ID
                val peerIdLength = buffer.get().toInt() and 0xFF
                val peerIdBytes = ByteArray(peerIdLength)
                buffer.get(peerIdBytes)
                val callerPeerID = String(peerIdBytes, Charsets.UTF_8)
                
                // Caller Nickname
                val nicknameLength = buffer.get().toInt() and 0xFF
                val nicknameBytes = ByteArray(nicknameLength)
                buffer.get(nicknameBytes)
                val callerNickname = String(nicknameBytes, Charsets.UTF_8)
                
                // Timestamp
                val timestamp = buffer.getLong()
                
                // Audio codec
                val codecLength = buffer.get().toInt() and 0xFF
                val codecBytes = ByteArray(codecLength)
                buffer.get(codecBytes)
                val audioCodec = String(codecBytes, Charsets.UTF_8)
                
                // Sample rate and bit rate
                val sampleRate = buffer.getInt()
                val bitRate = buffer.getInt()
                
                // Opus-specific parameters (with fallbacks for older versions)
                val frameSize = if (buffer.remaining() >= 4) buffer.getInt() else 20
                val complexity = if (buffer.remaining() >= 4) buffer.getInt() else 5
                val useVBR = if (buffer.remaining() >= 1) buffer.get() == 1.toByte() else true
                val useDTX = if (buffer.remaining() >= 1) buffer.get() == 1.toByte() else true
                
                return VoiceCallOffer(
                    callId = callId,
                    callerPeerID = callerPeerID,
                    callerNickname = callerNickname,
                    timestamp = timestamp,
                    audioCodec = audioCodec,
                    sampleRate = sampleRate,
                    bitRate = bitRate,
                    frameSize = frameSize,
                    complexity = complexity,
                    useVBR = useVBR,
                    useDTX = useDTX
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

/**
 * Voice call answer message
 */
@Parcelize
data class VoiceCallAnswer(
    val callId: String,
    val answererPeerID: String,
    val answererNickname: String,
    val accepted: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {
    
    fun toBinaryData(): ByteArray {
        val buffer = ByteBuffer.allocate(256).apply { order(ByteOrder.BIG_ENDIAN) }
        
        // Call ID
        val callIdBytes = callId.toByteArray(Charsets.UTF_8)
        buffer.put(callIdBytes.size.toByte())
        buffer.put(callIdBytes)
        
        // Answerer Peer ID
        val peerIdBytes = answererPeerID.toByteArray(Charsets.UTF_8)
        buffer.put(peerIdBytes.size.toByte())
        buffer.put(peerIdBytes)
        
        // Answerer Nickname
        val nicknameBytes = answererNickname.toByteArray(Charsets.UTF_8)
        buffer.put(nicknameBytes.size.toByte())
        buffer.put(nicknameBytes)
        
        // Accepted flag
        buffer.put(if (accepted) 1.toByte() else 0.toByte())
        
        // Timestamp
        buffer.putLong(timestamp)
        
        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }
    
    companion object {
        fun fromBinaryData(data: ByteArray): VoiceCallAnswer? {
            try {
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }
                
                // Call ID
                val callIdLength = buffer.get().toInt() and 0xFF
                val callIdBytes = ByteArray(callIdLength)
                buffer.get(callIdBytes)
                val callId = String(callIdBytes, Charsets.UTF_8)
                
                // Answerer Peer ID
                val peerIdLength = buffer.get().toInt() and 0xFF
                val peerIdBytes = ByteArray(peerIdLength)
                buffer.get(peerIdBytes)
                val answererPeerID = String(peerIdBytes, Charsets.UTF_8)
                
                // Answerer Nickname
                val nicknameLength = buffer.get().toInt() and 0xFF
                val nicknameBytes = ByteArray(nicknameLength)
                buffer.get(nicknameBytes)
                val answererNickname = String(nicknameBytes, Charsets.UTF_8)
                
                // Accepted flag
                val accepted = buffer.get() == 1.toByte()
                
                // Timestamp
                val timestamp = buffer.getLong()
                
                return VoiceCallAnswer(
                    callId = callId,
                    answererPeerID = answererPeerID,
                    answererNickname = answererNickname,
                    accepted = accepted,
                    timestamp = timestamp
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

/**
 * Voice call hangup message
 */
@Parcelize
data class VoiceCallHangup(
    val callId: String,
    val senderPeerID: String,
    val reason: String = "User ended call",
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {
    
    fun toBinaryData(): ByteArray {
        val buffer = ByteBuffer.allocate(256).apply { order(ByteOrder.BIG_ENDIAN) }
        
        // Call ID
        val callIdBytes = callId.toByteArray(Charsets.UTF_8)
        buffer.put(callIdBytes.size.toByte())
        buffer.put(callIdBytes)
        
        // Sender Peer ID
        val peerIdBytes = senderPeerID.toByteArray(Charsets.UTF_8)
        buffer.put(peerIdBytes.size.toByte())
        buffer.put(peerIdBytes)
        
        // Reason
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        buffer.put(reasonBytes.size.toByte())
        buffer.put(reasonBytes)
        
        // Timestamp
        buffer.putLong(timestamp)
        
        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }
    
    companion object {
        fun fromBinaryData(data: ByteArray): VoiceCallHangup? {
            try {
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }
                
                // Call ID
                val callIdLength = buffer.get().toInt() and 0xFF
                val callIdBytes = ByteArray(callIdLength)
                buffer.get(callIdBytes)
                val callId = String(callIdBytes, Charsets.UTF_8)
                
                // Sender Peer ID
                val peerIdLength = buffer.get().toInt() and 0xFF
                val peerIdBytes = ByteArray(peerIdLength)
                buffer.get(peerIdBytes)
                val senderPeerID = String(peerIdBytes, Charsets.UTF_8)
                
                // Reason
                val reasonLength = buffer.get().toInt() and 0xFF
                val reasonBytes = ByteArray(reasonLength)
                buffer.get(reasonBytes)
                val reason = String(reasonBytes, Charsets.UTF_8)
                
                // Timestamp
                val timestamp = buffer.getLong()
                
                return VoiceCallHangup(
                    callId = callId,
                    senderPeerID = senderPeerID,
                    reason = reason,
                    timestamp = timestamp
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

/**
 * Voice audio data packet - now supports compressed Opus data
 */
@Parcelize
data class VoiceAudioPacket(
    val callId: String,
    val senderPeerID: String,
    val sequenceNumber: UShort,
    val audioData: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val compressionType: String = "OPUS"  // "OPUS" or "PCM" for backward compatibility
) : Parcelable {
    
    fun toBinaryData(): ByteArray {
        val buffer = ByteBuffer.allocate(audioData.size + 64).apply { order(ByteOrder.BIG_ENDIAN) }
        
        // Call ID
        val callIdBytes = callId.toByteArray(Charsets.UTF_8)
        buffer.put(callIdBytes.size.toByte())
        buffer.put(callIdBytes)
        
        // Sender Peer ID
        val peerIdBytes = senderPeerID.toByteArray(Charsets.UTF_8)
        buffer.put(peerIdBytes.size.toByte())
        buffer.put(peerIdBytes)
        
        // Sequence number
        buffer.putShort(sequenceNumber.toShort())
        
        // Timestamp
        buffer.putLong(timestamp)
        
        // Compression type
        val compressionBytes = compressionType.toByteArray(Charsets.UTF_8)
        buffer.put(compressionBytes.size.toByte())
        buffer.put(compressionBytes)
        
        // Audio data length and data
        buffer.putShort(audioData.size.toShort())
        buffer.put(audioData)
        
        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }
    
    companion object {
        fun fromBinaryData(data: ByteArray): VoiceAudioPacket? {
            try {
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }
                
                // Call ID
                val callIdLength = buffer.get().toInt() and 0xFF
                val callIdBytes = ByteArray(callIdLength)
                buffer.get(callIdBytes)
                val callId = String(callIdBytes, Charsets.UTF_8)
                
                // Sender Peer ID
                val peerIdLength = buffer.get().toInt() and 0xFF
                val peerIdBytes = ByteArray(peerIdLength)
                buffer.get(peerIdBytes)
                val senderPeerID = String(peerIdBytes, Charsets.UTF_8)
                
                // Sequence number
                val sequenceNumber = buffer.getShort().toUShort()
                
                // Timestamp
                val timestamp = buffer.getLong()
                
                // Compression type (with fallback for older versions)
                val compressionType = if (buffer.remaining() >= 1) {
                    val compressionLength = buffer.get().toInt() and 0xFF
                    val compressionBytes = ByteArray(compressionLength)
                    buffer.get(compressionBytes)
                    String(compressionBytes, Charsets.UTF_8)
                } else {
                    "PCM" // Default to PCM for backward compatibility
                }
                
                // Audio data
                val audioDataLength = buffer.getShort().toInt() and 0xFFFF
                val audioData = ByteArray(audioDataLength)
                buffer.get(audioData)
                
                return VoiceAudioPacket(
                    callId = callId,
                    senderPeerID = senderPeerID,
                    sequenceNumber = sequenceNumber,
                    audioData = audioData,
                    timestamp = timestamp,
                    compressionType = compressionType
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as VoiceAudioPacket
        
        if (callId != other.callId) return false
        if (senderPeerID != other.senderPeerID) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (!audioData.contentEquals(other.audioData)) return false
        if (timestamp != other.timestamp) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = callId.hashCode()
        result = 31 * result + senderPeerID.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}