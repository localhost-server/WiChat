package com.wichat.android.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * Audio engine for voice calls with Opus codec, echo cancellation and noise suppression
 */
class AudioEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2 // Reduced for lower latency
        private const val FRAME_SIZE_MS = 10 // 10ms frames for lower latency
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 16-bit samples
    }
    
    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    // Opus codec for compression
    private val opusCodec = OpusCodec()
    
    // Simple audio router for reliable device routing
    private val audioRouter = SimpleAudioRouter(context)
    
    // Audio effects
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    
    // Software echo cancellation fallback
    private val softwareEchoCanceler = SoftwareEchoCanceler()
    private var useSoftwareEchoCancellation = false
    private var lastPlaybackData: ByteArray? = null
    
    
    // Playback queue for received audio
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    
    // Coroutines
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    
    // State
    private var isRecording = false
    private var isPlaying = false
    private var isMuted = false
    private var sequenceNumber: UShort = 0u
    
    // Callbacks - now sends compressed Opus data instead of raw PCM
    var onAudioRecorded: ((ByteArray, UShort) -> Unit)? = null
    
    init {
        setupAudioManager()
        // Initialize Opus codec
        if (!opusCodec.initialize()) {
            Log.e(TAG, "⚠️ Failed to initialize Opus codec - will use raw PCM")
        }
        
        
        // Audio router will be set up when starting recording/playback
    }
    
    private fun setupAudioManager() {
        // Audio router will be configured when starting recording/playback
        Log.d(TAG, "Audio manager ready")
    }
    
    fun startRecording(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return true
        }
        
        try {
            // Setup audio routing for voice call
            audioRouter.setupForVoiceCall()
            
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }
            
            // Setup audio effects
            setupAudioEffects()
            
            
            audioRecord?.startRecording()
            isRecording = true
            sequenceNumber = 0u
            
            recordingJob = audioScope.launch {
                recordAudio()
            }
            
            Log.i(TAG, "Audio recording started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            return false
        }
    }
    
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recordingJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            // Release audio effects
            releaseAudioEffects()
            
            Log.i(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
        }
    }
    
    fun startPlayback(): Boolean {
        if (isPlaying) {
            Log.w(TAG, "Already playing")
            return true
        }
        
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed")
                return false
            }
            
            audioTrack?.play()
            isPlaying = true
            
            playbackJob = audioScope.launch {
                playAudio()
            }
            
            Log.i(TAG, "Audio playback started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback: ${e.message}")
            return false
        }
    }
    
    fun stopPlayback() {
        if (!isPlaying) return
        
        isPlaying = false
        playbackJob?.cancel()
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            
            // Clear playback queue
            playbackQueue.clear()
            
            Log.i(TAG, "Audio playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback: ${e.message}")
        }
    }
    
    fun queueAudioForPlayback(audioData: ByteArray) {
        if (isPlaying) {
            playbackQueue.offer(audioData)
        }
    }
    
    private suspend fun recordAudio() {
        val buffer = ByteArray(FRAME_SIZE_BYTES)
        
        while (isRecording && audioRecord != null) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (bytesRead > 0) {
                    // Process audio frame (apply effects, etc.)
                    val processedAudio = if (isMuted) {
                        // Send silent audio when muted - create zeros and compress them to match normal audio format
                        val silentBuffer = ByteArray(bytesRead) { 0 }
                        val encodedSilence = opusCodec.encode(silentBuffer)
                        if (encodedSilence != null) {
                            Log.v(TAG, "🔇 Muted: sending compressed silence (${encodedSilence.size} bytes)")
                            encodedSilence
                        } else {
                            Log.w(TAG, "⚠️ Failed to encode silence, sending raw zeros")
                            silentBuffer
                        }
                    } else {
                        processRecordedAudio(buffer, bytesRead)
                    }
                    
                    // Notify callback with processed audio (silent if muted)
                    onAudioRecorded?.invoke(processedAudio, sequenceNumber)
                    
                    sequenceNumber = ((sequenceNumber.toInt() + 1) and 0xFFFF).toUShort()
                }
                
                // Small delay to prevent excessive CPU usage
                delay(1)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio recording loop: ${e.message}")
                break
            }
        }
    }
    
    private suspend fun playAudio() {
        while (isPlaying && audioTrack != null) {
            try {
                val audioData = playbackQueue.poll()
                if (audioData != null) {
                    // Process received audio before playback
                    val processedAudio = processPlaybackAudio(audioData)
                    
                    audioTrack?.write(processedAudio, 0, processedAudio.size)
                } else {
                    // No audio to play, minimal delay for low latency
                    delay(1)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio playback loop: ${e.message}")
                break
            }
        }
    }
    
    private fun processRecordedAudio(buffer: ByteArray, length: Int): ByteArray {
        // Apply gain control and filtering if needed
        val processedBuffer = buffer.copyOf(length)
        
        // Apply software echo cancellation if enabled
        val echoCancelledBuffer = if (useSoftwareEchoCancellation) {
            try {
                softwareEchoCanceler.processFrame(processedBuffer, lastPlaybackData)
            } catch (e: Exception) {
                Log.w(TAG, "Software echo cancellation failed: ${e.message}")
                processedBuffer // Fall back to original buffer
            }
        } else {
            processedBuffer
        }
        
        // Basic volume normalization
        normalizeAudio(echoCancelledBuffer)
        
        // Encode with Opus codec for compression
        val encodedData = opusCodec.encode(echoCancelledBuffer)
        if (encodedData != null) {
            Log.v(TAG, "🎵 Opus compression: ${echoCancelledBuffer.size} → ${encodedData.size} bytes (${(encodedData.size * 100 / echoCancelledBuffer.size)}%)")
            return encodedData
        } else {
            Log.w(TAG, "⚠️ Opus encoding failed, sending raw PCM")
            return echoCancelledBuffer
        }
    }
    
    private fun processPlaybackAudio(audioData: ByteArray): ByteArray {
        // Decode Opus data back to PCM for playback
        val decodedData = opusCodec.decode(audioData)
        val processedAudio = if (decodedData != null) {
            Log.v(TAG, "🔊 Opus decompression: ${audioData.size} → ${decodedData.size} bytes")
            
            // Apply jitter buffer and audio processing for decoded audio
            normalizeAudio(decodedData)
            decodedData
        } else {
            Log.w(TAG, "⚠️ Opus decoding failed, trying raw PCM playback")
            // Fallback: assume it's raw PCM data
            val processedBuffer = audioData.copyOf()
            normalizeAudio(processedBuffer)
            processedBuffer
        }
        
        // Store playback data for software echo cancellation reference
        if (useSoftwareEchoCancellation) {
            lastPlaybackData = processedAudio.copyOf()
        }
        
        return processedAudio
    }
    
    private fun normalizeAudio(audioData: ByteArray) {
        // Convert bytes to 16-bit samples
        val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(audioData.size / 2)
        
        for (i in samples.indices) {
            samples[i] = buffer.getShort()
        }
        
        // Find peak amplitude
        var peak = 0
        for (sample in samples) {
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > peak) peak = abs
        }
        
        // Normalize if needed (avoid clipping)
        if (peak > 0 && peak < Short.MAX_VALUE / 2) {
            val gain = min(2.0f, Short.MAX_VALUE.toFloat() / peak / 2)
            for (i in samples.indices) {
                samples[i] = (samples[i] * gain).toInt().toShort()
            }
            
            // Write back to buffer
            buffer.rewind()
            for (sample in samples) {
                buffer.putShort(sample)
            }
        }
    }
    
    private fun setupAudioEffects() {
        audioRecord?.audioSessionId?.let { sessionId ->
            try {
                // Acoustic Echo Canceler - Enhanced setup
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)
                    if (echoCanceler != null) {
                        echoCanceler?.enabled = true
                        useSoftwareEchoCancellation = false
                        Log.i(TAG, "✅ Hardware echo canceler enabled for session $sessionId")
                        Log.i(TAG, "💡 For best audio quality, use earpiece or headset when devices are nearby")
                    } else {
                        useSoftwareEchoCancellation = true
                        Log.w(TAG, "⚠️ Hardware echo canceler failed - using software fallback")
                    }
                } else {
                    useSoftwareEchoCancellation = true
                    Log.w(TAG, "⚠️ Hardware echo canceler not available - using software fallback")
                    Log.i(TAG, "💡 Recommendation: Use earpiece or headset to avoid echo when devices are nearby")
                }
                
                // Noise Suppressor
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)
                    noiseSuppressor?.enabled = true
                    Log.d(TAG, "Noise suppressor enabled")
                }
                
                // Automatic Gain Control
                if (AutomaticGainControl.isAvailable()) {
                    automaticGainControl = AutomaticGainControl.create(sessionId)
                    automaticGainControl?.enabled = true
                    Log.d(TAG, "Automatic gain control enabled")
                }
                
                // Initialize software echo canceller if needed
                if (useSoftwareEchoCancellation) {
                    softwareEchoCanceler.reset()
                    Log.i(TAG, "Software echo cancellation initialized")
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setup audio effects: ${e.message}")
                // Fall back to software echo cancellation on any error
                useSoftwareEchoCancellation = true
                softwareEchoCanceler.reset()
                Log.i(TAG, "Falling back to software echo cancellation due to error")
            }
        }
    }
    
    private fun releaseAudioEffects() {
        try {
            echoCanceler?.release()
            echoCanceler = null
            
            noiseSuppressor?.release()
            noiseSuppressor = null
            
            automaticGainControl?.release()
            automaticGainControl = null
            
            Log.d(TAG, "Audio effects released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio effects: ${e.message}")
        }
    }
    
    fun toggleSpeakerphone() {
        audioRouter.toggleSpeakerphone()
    }
    
    fun isSpeakerphoneOn(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.isSpeakerphoneOn
    }
    
    fun toggleMute() {
        isMuted = !isMuted
        Log.d(TAG, "Microphone muted: $isMuted")
    }
    
    fun isMuted(): Boolean {
        return isMuted
    }
    
    fun setMuted(muted: Boolean) {
        isMuted = muted
        Log.d(TAG, "Microphone muted: $isMuted")
    }
    
    fun getMicrophoneLevel(): Float {
        // Return a simple amplitude level (0.0 to 1.0)
        // Return 0 if muted to show no activity
        return if (isRecording && !isMuted) 0.5f else 0.0f
    }
    
    fun shutdown() {
        Log.i(TAG, "Shutting down AudioEngine")
        
        stopRecording()
        stopPlayback()
        
        audioScope.cancel()
        
        // Release Opus codec
        opusCodec.release()
        
        // Reset software echo cancellation
        if (useSoftwareEchoCancellation) {
            softwareEchoCanceler.reset()
        }
        
        // Clean up audio router
        try {
            audioRouter.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up audio router: ${e.message}")
        }
        
        Log.i(TAG, "AudioEngine shutdown complete")
    }
    
    fun setSpeakerphoneOn(on: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentState = audioManager.isSpeakerphoneOn
        
        if (on != currentState) {
            audioRouter.toggleSpeakerphone()
        }
        
        Log.i(TAG, "🔊 Speakerphone: $on")
        if (on) {
            Log.w(TAG, "⚠️ Speakerphone enabled - may cause echo if devices are nearby")
        }
    }
    
    
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== AudioEngine Debug Info ===")
            appendLine("Recording: $isRecording")
            appendLine("Playing: $isPlaying")
            appendLine("Sequence Number: $sequenceNumber")
            appendLine("Playback Queue Size: ${playbackQueue.size}")
            appendLine("Sample Rate: $SAMPLE_RATE Hz")
            appendLine("Frame Size: $FRAME_SIZE_MS ms ($FRAME_SIZE_BYTES bytes)")
            appendLine("Hardware Echo Canceler: ${echoCanceler?.enabled ?: false}")
            appendLine("Software Echo Cancellation: $useSoftwareEchoCancellation")
            appendLine("Noise Suppressor: ${noiseSuppressor?.enabled ?: false}")
            appendLine("Auto Gain Control: ${automaticGainControl?.enabled ?: false}")
            appendLine("Speakerphone: ${isSpeakerphoneOn()}")
            appendLine()
            appendLine(audioRouter.getAudioInfo())
            appendLine()
            if (useSoftwareEchoCancellation) {
                appendLine(softwareEchoCanceler.getDebugInfo())
                appendLine()
            }
            appendLine(opusCodec.getDebugInfo())
        }
    }
}