#include <jni.h>
#include <android/log.h>
#include <string>
#include <opus.h>

#define TAG "OpusNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Note: Removed global static pointers to prevent thread safety issues
// Handles are now passed as parameters from Java

extern "C" {

/**
 * Initialize Opus encoder
 */
JNIEXPORT jlong JNICALL
Java_com_bitchat_android_audio_OpusCodec_nativeInitEncoder(
    JNIEnv *env, jobject thiz,
    jint sample_rate, jint channels, jint application,
    jint bitrate, jint complexity, jboolean vbr, jboolean dtx
) {
    int error;
    
    LOGI("🚀 Initializing Opus encoder:");
    LOGI("  Sample rate: %d Hz", sample_rate);
    LOGI("  Channels: %d", channels);
    LOGI("  Application: %d", application);
    LOGI("  Bitrate: %d bps", bitrate);
    LOGI("  Complexity: %d", complexity);
    LOGI("  VBR: %s", vbr ? "true" : "false");
    LOGI("  DTX: %s", dtx ? "true" : "false");

    // Create encoder
    OpusEncoder* encoder = opus_encoder_create(sample_rate, channels, application, &error);
    if (error != OPUS_OK || encoder == nullptr) {
        LOGE("❌ Failed to create Opus encoder: %s", opus_strerror(error));
        return 0;
    }

    // Configure encoder settings
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(encoder, OPUS_SET_VBR(vbr ? 1 : 0));
    opus_encoder_ctl(encoder, OPUS_SET_DTX(dtx ? 1 : 0));
    
    // Voice optimizations
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(encoder, OPUS_SET_LSB_DEPTH(16));
    
    LOGI("✅ Opus encoder initialized successfully");
    return reinterpret_cast<jlong>(encoder);
}

/**
 * Initialize Opus decoder
 */
JNIEXPORT jlong JNICALL
Java_com_bitchat_android_audio_OpusCodec_nativeInitDecoder(
    JNIEnv *env, jobject thiz, jint sample_rate, jint channels
) {
    int error;
    
    LOGI("🚀 Initializing Opus decoder:");
    LOGI("  Sample rate: %d Hz", sample_rate);
    LOGI("  Channels: %d", channels);

    // Create decoder
    OpusDecoder* decoder = opus_decoder_create(sample_rate, channels, &error);
    if (error != OPUS_OK || decoder == nullptr) {
        LOGE("❌ Failed to create Opus decoder: %s", opus_strerror(error));
        return 0;
    }

    LOGI("✅ Opus decoder initialized successfully");
    return reinterpret_cast<jlong>(decoder);
}

/**
 * Encode PCM audio to Opus
 */
JNIEXPORT jbyteArray JNICALL
Java_com_bitchat_android_audio_OpusCodec_nativeEncode(
    JNIEnv *env, jobject thiz, jlong encoder_handle, jbyteArray pcm_data, jint frame_size
) {
    if (encoder_handle == 0) {
        LOGE("❌ Encoder not initialized");
        return nullptr;
    }

    OpusEncoder* encoder = reinterpret_cast<OpusEncoder*>(encoder_handle);
    
    // Get PCM data from Java
    jbyte* pcm_bytes = env->GetByteArrayElements(pcm_data, nullptr);
    jsize pcm_length = env->GetArrayLength(pcm_data);
    
    // Convert bytes to shorts (16-bit PCM)
    int16_t* pcm_shorts = reinterpret_cast<int16_t*>(pcm_bytes);
    int pcm_samples = pcm_length / 2; // 2 bytes per 16-bit sample
    
    // Allocate output buffer on heap to prevent stack overflow
    const int max_encoded_size = 1024; // Reduced from 4000 for voice calls
    unsigned char* encoded_data = new unsigned char[max_encoded_size];
    
    // Encode the audio
    int encoded_bytes = opus_encode(encoder, pcm_shorts, frame_size, encoded_data, max_encoded_size);
    
    // Release PCM data
    env->ReleaseByteArrayElements(pcm_data, pcm_bytes, JNI_ABORT);
    
    if (encoded_bytes < 0) {
        LOGE("❌ Opus encoding failed: %s", opus_strerror(encoded_bytes));
        delete[] encoded_data;
        return nullptr;
    }
    
    LOGD("🎤 Encoded %d samples → %d bytes (%.1f%% compression)", 
         pcm_samples, encoded_bytes, (float)encoded_bytes * 100.0f / pcm_length);
    
    // Create Java byte array for result
    jbyteArray result = env->NewByteArray(encoded_bytes);
    env->SetByteArrayRegion(result, 0, encoded_bytes, reinterpret_cast<jbyte*>(encoded_data));
    
    // Clean up heap allocation
    delete[] encoded_data;
    
    return result;
}

/**
 * Decode Opus audio to PCM
 */
JNIEXPORT jbyteArray JNICALL
Java_com_bitchat_android_audio_OpusCodec_nativeDecode(
    JNIEnv *env, jobject thiz, jlong decoder_handle, jbyteArray opus_data, jint frame_size
) {
    if (decoder_handle == 0) {
        LOGE("❌ Decoder not initialized");
        return nullptr;
    }

    OpusDecoder* decoder = reinterpret_cast<OpusDecoder*>(decoder_handle);
    
    // Get Opus data from Java
    jbyte* opus_bytes = env->GetByteArrayElements(opus_data, nullptr);
    jsize opus_length = env->GetArrayLength(opus_data);
    
    // Allocate PCM output buffer on heap to prevent stack overflow
    const int max_frame_size = 1024; // Reduced from 5760 for 16kHz voice calls
    int16_t* pcm_data = new int16_t[max_frame_size];
    
    // Decode the audio
    int decoded_samples = opus_decode(decoder, 
                                    reinterpret_cast<unsigned char*>(opus_bytes), 
                                    opus_length, 
                                    pcm_data, 
                                    frame_size, 
                                    0);
    
    // Release Opus data
    env->ReleaseByteArrayElements(opus_data, opus_bytes, JNI_ABORT);
    
    if (decoded_samples < 0) {
        LOGE("❌ Opus decoding failed: %s", opus_strerror(decoded_samples));
        delete[] pcm_data;
        return nullptr;
    }
    
    LOGD("🔊 Decoded %d bytes → %d samples", opus_length, decoded_samples);
    
    // Convert back to bytes and create Java array
    int pcm_bytes_count = decoded_samples * 2; // 2 bytes per 16-bit sample
    jbyteArray result = env->NewByteArray(pcm_bytes_count);
    env->SetByteArrayRegion(result, 0, pcm_bytes_count, reinterpret_cast<jbyte*>(pcm_data));
    
    // Clean up heap allocation
    delete[] pcm_data;
    
    return result;
}

/**
 * Get Opus version string
 */
JNIEXPORT jstring JNICALL
Java_com_bitchat_android_audio_OpusCodec_nativeGetVersion(JNIEnv *env, jobject thiz) {
    const char* version = opus_get_version_string();
    LOGI("📋 Opus version: %s", version);
    return env->NewStringUTF(version);
}

/**
 * Release encoder resources
 */
JNIEXPORT void JNICALL
Java_com_bitchat_android_audio_OpusCodec_nativeReleaseEncoder(JNIEnv *env, jobject thiz, jlong encoder_handle) {
    if (encoder_handle != 0) {
        OpusEncoder* encoder = reinterpret_cast<OpusEncoder*>(encoder_handle);
        opus_encoder_destroy(encoder);
        LOGI("🧹 Opus encoder released");
    }
}

/**
 * Release decoder resources
 */
JNIEXPORT void JNICALL
Java_com_bitchat_android_audio_OpusCodec_nativeReleaseDecoder(JNIEnv *env, jobject thiz, jlong decoder_handle) {
    if (decoder_handle != 0) {
        OpusDecoder* decoder = reinterpret_cast<OpusDecoder*>(decoder_handle);
        opus_decoder_destroy(decoder);
        LOGI("🧹 Opus decoder released");
    }
}

} // extern "C"