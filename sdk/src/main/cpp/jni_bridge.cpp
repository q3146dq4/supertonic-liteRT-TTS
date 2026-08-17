#include <jni.h>
#include <android/log.h>

#include <speech_core/models/litert_supertonic_tts.h>
#include <speech_core/interfaces.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "SupertonicTTS"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct SynthesizerHandle {
    std::unique_ptr<speech_core::TTSInterface> tts;
    speech_core::LiteRTSupertonicTts* supertonic = nullptr;
    std::mutex mutex;
};

static constexpr int TTS_SUPERTONIC = 1;

static std::string to_string(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

static void throw_runtime(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCreateSynthesizer(
    JNIEnv* env, jobject, jstring modelDir, jboolean useNnapi, jint ttsModel,
    jstring voiceId, jint totalSteps, jfloat speed, jint numThreads, jint chunkCap) {
    if (ttsModel != TTS_SUPERTONIC) {
        throw_runtime(env, "Only Supertonic is supported by this TTS engine");
        return 0;
    }
    const std::string dir = to_string(env, modelDir);
    const std::string voice = to_string(env, voiceId);
    auto handle = std::make_unique<SynthesizerHandle>();
    try {
        // Soniqo's Supertonic-3 Android bundle is four LiteRT graphs.
        // CPU execution uses the stable TFLite/XNNPACK interpreter ABI so the
        // requested thread count is actually applied to every graph.
        (void)useNnapi;
        handle->tts = std::make_unique<speech_core::LiteRTSupertonicTts>(
            dir + "/duration_predictor.tflite",
            dir + "/text_encoder.tflite",
            dir + "/vector_estimator.tflite",
            dir + "/vocoder.tflite",
            dir,
            dir + "/voice_styles",
            false,
            std::max(1, std::min(64, static_cast<int>(numThreads))));
        handle->supertonic = dynamic_cast<speech_core::LiteRTSupertonicTts*>(handle->tts.get());
        if (handle->supertonic) {
            if (!voice.empty()) handle->supertonic->set_voice(voice);
            handle->supertonic->set_total_step(std::max(1, std::min(64, static_cast<int>(totalSteps))));
            handle->supertonic->set_speed(std::max(0.25f, std::min(3.0f, static_cast<float>(speed))));
            handle->supertonic->set_chunk_cap(std::max(24, std::min(96, static_cast<int>(chunkCap))));
        }
    } catch (const std::exception& e) {
        LOGE("Create failed: %s", e.what());
        throw_runtime(env, std::string("Supertonic initialization failed: ") + e.what());
        return 0;
    }
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeDestroySynthesizer(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<SynthesizerHandle*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeStopSynthesizer(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (h && h->tts) h->tts->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerVoice(JNIEnv* env, jobject, jlong handle, jstring voiceId) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    try { h->supertonic->set_voice(to_string(env, voiceId)); }
    catch (const std::exception& e) { throw_runtime(env, e.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerSpeed(JNIEnv*, jobject, jlong handle, jfloat speed) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    h->supertonic->set_speed(std::max(0.25f, std::min(3.0f, static_cast<float>(speed))));
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerSteps(JNIEnv*, jobject, jlong handle, jint totalSteps) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    h->supertonic->set_total_step(std::max(1, std::min(64, static_cast<int>(totalSteps))));
}


extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerChunkCap(JNIEnv*, jobject, jlong handle, jint chunkCap) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    h->supertonic->set_chunk_cap(std::max(24, std::min(96, static_cast<int>(chunkCap))));
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerPreGeneration(JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    h->supertonic->set_pre_generation(enabled == JNI_TRUE);
}


extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerPreGenerationQueue(JNIEnv*, jobject, jlong handle, jint depth) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    h->supertonic->set_pre_generation_queue(std::max(2, std::min(3, static_cast<int>(depth))));
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerChunkGap(JNIEnv*, jobject, jlong handle, jint minMs, jint maxMs) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    const int mn = std::max(0, std::min(2000, static_cast<int>(minMs)));
    const int mx = std::max(mn, std::min(2000, static_cast<int>(maxMs)));
    h->supertonic->set_chunk_gap_ms(mn, mx);
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSetSynthesizerTrailingSilenceTrim(JNIEnv*, jobject, jlong handle, jint trimMs) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return;
    h->supertonic->set_trailing_silence_trim_ms(std::max(0, std::min(500, static_cast<int>(trimMs))));
}

extern "C" JNIEXPORT jstring JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeGetLastProfile(JNIEnv* env, jobject, jlong handle) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->supertonic) return env->NewStringUTF("");
    const std::string profile = h->supertonic->performance_profile();
    return env->NewStringUTF(profile.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesizerSampleRate(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    return h && h->tts ? static_cast<jint>(h->tts->output_sample_rate()) : 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesize(JNIEnv* env, jobject, jlong handle, jstring text, jstring language) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->tts) { throw_runtime(env, "TTS engine is not initialized"); return nullptr; }
    std::vector<int16_t> pcm;
    try {
        std::lock_guard<std::mutex> guard(h->mutex);
        h->tts->synthesize(to_string(env, text), to_string(env, language),
            [](const float*, size_t, bool) {});
        const auto& merged = h->supertonic->last_pcm();
        pcm.reserve(merged.size());
        for (float x : merged) {
            const float clamped = std::max(-1.0f, std::min(1.0f, x));
            pcm.push_back(static_cast<int16_t>(clamped * 32767.0f));
        }
    } catch (const std::exception& e) {
        throw_runtime(env, std::string("Supertonic synthesis failed: ") + e.what());
        return nullptr;
    }
    const jsize bytes = static_cast<jsize>(pcm.size() * sizeof(int16_t));
    jbyteArray out = env->NewByteArray(bytes);
    if (bytes) env->SetByteArrayRegion(out, 0, bytes, reinterpret_cast<const jbyte*>(pcm.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesizeStreaming(
    JNIEnv* env, jobject, jlong handle, jstring text, jstring language, jobject callback) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->tts || !callback) { throw_runtime(env, "Invalid TTS streaming request"); return; }
    jclass cls = env->GetObjectClass(callback);
    jmethodID onChunk = cls ? env->GetMethodID(cls, "onChunk", "([BZ)V") : nullptr;
    if (!onChunk) { throw_runtime(env, "Invalid TTS callback"); return; }
    try {
        std::lock_guard<std::mutex> guard(h->mutex);
        h->tts->synthesize(to_string(env, text), to_string(env, language),
            [&](const float* samples, size_t count, bool finalChunk) {
                std::vector<int16_t> pcm(count);
                for (size_t i = 0; i < count; ++i) {
                    const float x = std::max(-1.0f, std::min(1.0f, samples[i]));
                    pcm[i] = static_cast<int16_t>(x * 32767.0f);
                }
                jbyteArray audio = env->NewByteArray(static_cast<jsize>(pcm.size() * sizeof(int16_t)));
                if (!audio) { h->tts->cancel(); return; }
                if (!pcm.empty()) env->SetByteArrayRegion(audio, 0,
                    static_cast<jsize>(pcm.size() * sizeof(int16_t)),
                    reinterpret_cast<const jbyte*>(pcm.data()));
                env->CallVoidMethod(callback, onChunk, audio, static_cast<jboolean>(finalChunk));
                env->DeleteLocalRef(audio);
                if (env->ExceptionCheck()) h->tts->cancel();
            });
    } catch (const std::exception& e) {
        throw_runtime(env, std::string("Supertonic streaming failed: ") + e.what());
    }
    env->DeleteLocalRef(cls);
}
