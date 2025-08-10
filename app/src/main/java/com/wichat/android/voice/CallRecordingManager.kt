package com.wichat.android.voice

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Call Recording Manager for UDP voice calls
 * Records both local and remote audio streams
 */
class CallRecordingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CallRecordingManager"
        private const val RECORDINGS_DIR = "voice_recordings"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }
    
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Recording state
    private var isRecording = false
    private var currentCallId: String? = null
    private var recordingFile: File? = null
    
    // Audio buffers for mixing
    private val localAudioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private val remoteAudioBuffer = ConcurrentLinkedQueue<ByteArray>()
    
    // Recording job
    private var recordingJob: Job? = null
    
    data class CallRecording(
        val callId: String,
        val filePath: String,
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val participants: List<String>
    )
    
    /**
     * Start recording a voice call
     */
    fun startRecording(callId: String, participants: List<String>): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }
        
        try {
            // Create recordings directory
            val recordingsDir = File(context.filesDir, RECORDINGS_DIR)
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }
            
            // Create recording file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "call_${callId}_$timestamp.wav"
            recordingFile = File(recordingsDir, fileName)
            
            currentCallId = callId
            isRecording = true
            
            // Start recording job
            recordingJob = recordingScope.launch {
                recordCall(participants)
            }
            
            Log.i(TAG, "Started recording call $callId to ${recordingFile?.absolutePath}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            return false
        }
    }
    
    /**
     * Stop recording the current call
     */
    fun stopRecording(): CallRecording? {
        if (!isRecording) {
            return null
        }
        
        isRecording = false
        recordingJob?.cancel()
        
        val callId = currentCallId
        val file = recordingFile
        
        if (callId != null && file != null && file.exists()) {
            val recording = CallRecording(
                callId = callId,
                filePath = file.absolutePath,
                startTime = 0L, // Would need to track this
                endTime = System.currentTimeMillis(),
                durationMs = 0L, // Would calculate from timestamps
                participants = emptyList() // Would track participants
            )
            
            Log.i(TAG, "Stopped recording call $callId, saved to ${file.absolutePath}")
            return recording
        }
        
        return null
    }
    
    /**
     * Add local audio data to recording
     */
    fun addLocalAudio(audioData: ByteArray) {
        if (isRecording) {
            localAudioBuffer.offer(audioData)
        }
    }
    
    /**
     * Add remote audio data to recording
     */
    fun addRemoteAudio(audioData: ByteArray, peerID: String) {
        if (isRecording) {
            remoteAudioBuffer.offer(audioData)
        }
    }
    
    /**
     * Get all recordings
     */
    fun getAllRecordings(): List<CallRecording> {
        val recordingsDir = File(context.filesDir, RECORDINGS_DIR)
        if (!recordingsDir.exists()) {
            return emptyList()
        }
        
        return recordingsDir.listFiles { file ->
            file.name.endsWith(".wav")
        }?.map { file ->
            // Parse filename to extract call info
            val parts = file.nameWithoutExtension.split("_")
            CallRecording(
                callId = if (parts.size > 1) parts[1] else "unknown",
                filePath = file.absolutePath,
                startTime = 0L,
                endTime = file.lastModified(),
                durationMs = 0L,
                participants = emptyList()
            )
        } ?: emptyList()
    }
    
    /**
     * Delete a recording
     */
    fun deleteRecording(recording: CallRecording): Boolean {
        return try {
            val file = File(recording.filePath)
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete recording: ${e.message}")
            false
        }
    }
    
    private suspend fun recordCall(participants: List<String>) {
        val outputStream = FileOutputStream(recordingFile!!)
        
        // Write WAV header
        writeWavHeader(outputStream, 0) // We'll update the size later
        
        try {
            while (isRecording) {
                // Mix local and remote audio
                val mixedAudio = mixAudioStreams()
                if (mixedAudio.isNotEmpty()) {
                    outputStream.write(mixedAudio)
                }
                
                delay(20) // 20ms frames
            }
        } finally {
            // Update WAV header with correct file size
            outputStream.close()
            updateWavHeader(recordingFile!!)
        }
    }
    
    private fun mixAudioStreams(): ByteArray {
        val localData = localAudioBuffer.poll()
        val remoteData = remoteAudioBuffer.poll()
        
        return when {
            localData != null && remoteData != null -> {
                // Mix both streams
                mixAudioData(localData, remoteData)
            }
            localData != null -> localData
            remoteData != null -> remoteData
            else -> ByteArray(0)
        }
    }
    
    private fun mixAudioData(local: ByteArray, remote: ByteArray): ByteArray {
        val maxLength = maxOf(local.size, remote.size)
        val mixed = ByteArray(maxLength)
        
        for (i in 0 until maxLength step 2) {
            val localSample = if (i < local.size - 1) {
                (local[i].toInt() and 0xFF) or ((local[i + 1].toInt() and 0xFF) shl 8)
            } else 0
            
            val remoteSample = if (i < remote.size - 1) {
                (remote[i].toInt() and 0xFF) or ((remote[i + 1].toInt() and 0xFF) shl 8)
            } else 0
            
            // Mix samples (simple addition with clipping)
            val mixedSample = (localSample + remoteSample).coerceIn(-32768, 32767)
            
            mixed[i] = (mixedSample and 0xFF).toByte()
            if (i + 1 < maxLength) {
                mixed[i + 1] = ((mixedSample shr 8) and 0xFF).toByte()
            }
        }
        
        return mixed
    }
    
    private fun writeWavHeader(outputStream: FileOutputStream, dataSize: Int) {
        val header = ByteArray(44)
        val totalSize = dataSize + 36
        
        // RIFF header
        "RIFF".toByteArray().copyInto(header, 0)
        intToByteArray(totalSize).copyInto(header, 4)
        "WAVE".toByteArray().copyInto(header, 8)
        
        // Format chunk
        "fmt ".toByteArray().copyInto(header, 12)
        intToByteArray(16).copyInto(header, 16) // Format chunk size
        shortToByteArray(1).copyInto(header, 20) // PCM format
        shortToByteArray(CHANNELS).copyInto(header, 22) // Channels
        intToByteArray(SAMPLE_RATE).copyInto(header, 24) // Sample rate
        intToByteArray(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8).copyInto(header, 28) // Byte rate
        shortToByteArray(CHANNELS * BITS_PER_SAMPLE / 8).copyInto(header, 32) // Block align
        shortToByteArray(BITS_PER_SAMPLE).copyInto(header, 34) // Bits per sample
        
        // Data chunk
        "data".toByteArray().copyInto(header, 36)
        intToByteArray(dataSize).copyInto(header, 40)
        
        outputStream.write(header)
    }
    
    private fun updateWavHeader(file: File) {
        try {
            val fileSize = file.length().toInt()
            val dataSize = fileSize - 44
            
            file.outputStream().use { outputStream ->
                outputStream.channel.position(4)
                outputStream.write(intToByteArray(fileSize - 8))
                
                outputStream.channel.position(40)
                outputStream.write(intToByteArray(dataSize))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header: ${e.message}")
        }
    }
    
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
    
    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
    
    fun cleanup() {
        isRecording = false
        recordingJob?.cancel()
        recordingScope.cancel()
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
    }
}