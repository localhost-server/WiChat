package com.wichat.android.audio

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Software-based echo cancellation using adaptive filtering
 * Used as fallback when hardware echo cancellation is not available or effective
 */
class SoftwareEchoCanceler {
    
    companion object {
        private const val TAG = "SoftwareEchoCanceler"
        
        // Echo cancellation parameters
        private const val FILTER_LENGTH = 128 // Number of taps in adaptive filter
        private const val LEARNING_RATE = 0.001f // Step size for filter adaptation
        private const val ECHO_SUPPRESSION_DB = -20f // Target suppression in dB
        private const val MINIMUM_SUPPRESSION = 0.1f // Minimum gain (10% of original)
        
        // Audio processing parameters
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_MS = 20
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 320 samples
    }
    
    // Adaptive filter coefficients
    private val filterCoeffs = FloatArray(FILTER_LENGTH) { 0.0f }
    
    // Reference signal buffer (far-end/speaker output)
    private val referenceBuffer = CircularFloatBuffer(FILTER_LENGTH * 4)
    
    // Near-end signal buffer (microphone input)
    private val nearEndBuffer = CircularFloatBuffer(FRAME_SIZE_SAMPLES)
    
    // Echo estimation buffer
    private val echoEstimate = FloatArray(FRAME_SIZE_SAMPLES)
    
    // Statistics for adaptation
    private var powerNearEnd = 0.0f
    private var powerFarEnd = 0.0f
    private var powerEcho = 0.0f
    
    // Adaptation control
    private var isConverged = false
    private var convergenceCounter = 0
    private val convergenceThreshold = 100 // frames
    
    /**
     * Process audio frame for echo cancellation
     * @param nearEndData Microphone input (contains voice + echo)
     * @param farEndData Speaker output (reference signal)
     * @return Processed audio with reduced echo
     */
    fun processFrame(nearEndData: ByteArray, farEndData: ByteArray?): ByteArray {
        // Convert to float arrays for processing
        val nearEndSamples = bytesToFloats(nearEndData)
        val farEndSamples = if (farEndData != null) bytesToFloats(farEndData) else FloatArray(nearEndSamples.size)
        
        // Add far-end samples to reference buffer
        referenceBuffer.addSamples(farEndSamples)
        
        // Process each sample in the frame
        val outputSamples = FloatArray(nearEndSamples.size)
        
        for (i in nearEndSamples.indices) {
            val nearSample = nearEndSamples[i]
            
            // Estimate echo using adaptive filter
            var echoEstimate = 0.0f
            for (j in 0 until FILTER_LENGTH) {
                val refIndex = referenceBuffer.getDelayedSample(j)
                echoEstimate += filterCoeffs[j] * refIndex
            }
            
            // Calculate error signal (near-end minus estimated echo)
            val errorSignal = nearSample - echoEstimate
            
            // Update filter coefficients using NLMS algorithm
            if (!isConverged) {
                updateFilterCoefficients(errorSignal)
            }
            
            // Apply additional suppression if needed
            val suppressionGain = calculateSuppressionGain(nearSample, echoEstimate)
            outputSamples[i] = errorSignal * suppressionGain
        }
        
        // Update statistics
        updateStatistics(nearEndSamples, farEndSamples)
        
        // Convert back to bytes
        return floatsToBytes(outputSamples)
    }
    
    /**
     * Update adaptive filter coefficients using Normalized Least Mean Squares (NLMS)
     */
    private fun updateFilterCoefficients(error: Float) {
        // Calculate power of reference signal
        var refPower = 0.0f
        for (i in 0 until FILTER_LENGTH) {
            val refSample = referenceBuffer.getDelayedSample(i)
            refPower += refSample * refSample
        }
        
        // Prevent division by zero
        val normalizedPower = refPower + 0.001f
        
        // Update coefficients
        val stepSize = LEARNING_RATE / normalizedPower
        for (i in 0 until FILTER_LENGTH) {
            val refSample = referenceBuffer.getDelayedSample(i)
            filterCoeffs[i] += stepSize * error * refSample
            
            // Prevent filter coefficients from becoming too large
            filterCoeffs[i] = filterCoeffs[i].coerceIn(-1.0f, 1.0f)
        }
    }
    
    /**
     * Calculate additional suppression gain based on echo characteristics
     */
    private fun calculateSuppressionGain(nearEnd: Float, echoEstimate: Float): Float {
        val echoLevel = abs(echoEstimate)
        val nearLevel = abs(nearEnd)
        
        if (nearLevel < 0.001f) {
            // Very quiet signal, apply maximum suppression
            return MINIMUM_SUPPRESSION
        }
        
        // Calculate echo-to-signal ratio
        val echoRatio = echoLevel / nearLevel
        
        if (echoRatio > 0.3f) {
            // Strong echo detected, apply suppression
            val suppressionFactor = (1.0f - echoRatio).coerceAtLeast(MINIMUM_SUPPRESSION)
            return suppressionFactor
        }
        
        // Weak or no echo, minimal suppression
        return 0.9f
    }
    
    /**
     * Update statistics for convergence detection
     */
    private fun updateStatistics(nearEnd: FloatArray, farEnd: FloatArray) {
        var nearPower = 0.0f
        var farPower = 0.0f
        
        for (i in nearEnd.indices) {
            nearPower += nearEnd[i] * nearEnd[i]
            farPower += farEnd[i] * farEnd[i]
        }
        
        // Exponential smoothing
        powerNearEnd = 0.95f * powerNearEnd + 0.05f * nearPower
        powerFarEnd = 0.95f * powerFarEnd + 0.05f * farPower
        
        // Check for convergence
        if (powerFarEnd > 0.001f && powerNearEnd > 0.001f) {
            convergenceCounter++
            if (convergenceCounter > convergenceThreshold) {
                isConverged = true
                Log.d(TAG, "Adaptive filter converged")
            }
        }
    }
    
    /**
     * Reset the echo canceller state
     */
    fun reset() {
        for (i in filterCoeffs.indices) {
            filterCoeffs[i] = 0.0f
        }
        referenceBuffer.clear()
        powerNearEnd = 0.0f
        powerFarEnd = 0.0f
        isConverged = false
        convergenceCounter = 0
        Log.d(TAG, "Echo canceller reset")
    }
    
    /**
     * Convert byte array to float array
     */
    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 2)
        
        for (i in floats.indices) {
            val shortVal = buffer.getShort()
            floats[i] = shortVal.toFloat() / Short.MAX_VALUE.toFloat()
        }
        
        return floats
    }
    
    /**
     * Convert float array to byte array
     */
    private fun floatsToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        
        for (float in floats) {
            val shortVal = (float * Short.MAX_VALUE.toFloat()).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer.putShort(shortVal)
        }
        
        return buffer.array()
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Software Echo Canceller Debug ===")
            appendLine("Filter Length: $FILTER_LENGTH taps")
            appendLine("Learning Rate: $LEARNING_RATE")
            appendLine("Converged: $isConverged")
            appendLine("Convergence Progress: $convergenceCounter/$convergenceThreshold")
            appendLine("Near-end Power: ${"%.6f".format(powerNearEnd)}")
            appendLine("Far-end Power: ${"%.6f".format(powerFarEnd)}")
            appendLine("Reference Buffer Size: ${referenceBuffer.size()}")
            
            // Show first few filter coefficients
            appendLine("Filter Coefficients (first 10):")
            for (i in 0 until min(10, FILTER_LENGTH)) {
                appendLine("  coeff[$i] = ${"%.6f".format(filterCoeffs[i])}")
            }
        }
    }
}

/**
 * Circular buffer for audio samples (float)
 */
private class CircularFloatBuffer(private val capacity: Int) {
    private val buffer = FloatArray(capacity)
    private var writeIndex = 0
    private var currentSize = 0
    
    fun addSamples(samples: FloatArray) {
        for (sample in samples) {
            buffer[writeIndex] = sample
            writeIndex = (writeIndex + 1) % capacity
            if (currentSize < capacity) {
                currentSize++
            }
        }
    }
    
    fun getDelayedSample(delay: Int): Float {
        if (delay >= currentSize || delay < 0) {
            return 0.0f
        }
        
        val readIndex = (writeIndex - 1 - delay + capacity) % capacity
        return buffer[readIndex]
    }
    
    fun clear() {
        for (i in buffer.indices) {
            buffer[i] = 0.0f
        }
        writeIndex = 0
        currentSize = 0
    }
    
    fun size(): Int = currentSize
}