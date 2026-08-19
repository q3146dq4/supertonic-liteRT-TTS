#include <jni.h>
#include <android/log.h>

#include <speech_core/models/litert_supertonic_tts.h>
#include <speech_core/interfaces.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <sstream>
#include <iomanip>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <stdexcept>
#include <vector>

#define LOG_TAG "SupertonicTTS"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct SynthesizerHandle {
    std::unique_ptr<speech_core::TTSInterface> tts;
    speech_core::LiteRTSupertonicTts* supertonic = nullptr;
    std::mutex mutex;

    // JNI-side timings for the most recent non-streaming synthesis.
    // These deliberately sit outside LiteRTSupertonicTts::performance_profile()
    // so we can see time spent before/after the core native synthesize() call.
    double jni_lock_wait_ms = 0.0;
    double jni_arg_convert_ms = 0.0;
    double jni_core_ms = 0.0;
    double jni_pcm_convert_ms = 0.0;
    double jni_bytearray_alloc_ms = 0.0;
    double jni_bytearray_copy_ms = 0.0;
    double jni_total_ms = 0.0;
    long long jni_pcm_samples = 0;
};

using JniClock = std::chrono::steady_clock;
static double jni_elapsed_ms(JniClock::time_point a, JniClock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

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

static std::string consume_java_exception(JNIEnv* env, const char* where) {
    if (!env->ExceptionCheck()) return {};
    jthrowable ex = env->ExceptionOccurred();
    env->ExceptionClear();
    std::string msg = where ? where : "Java accelerator call failed";
    if (ex) {
        jclass cls = env->GetObjectClass(ex);
        jmethodID mid_to_string = cls ? env->GetMethodID(cls, "toString", "()Ljava/lang/String;") : nullptr;
        if (mid_to_string) {
            auto text = static_cast<jstring>(env->CallObjectMethod(ex, mid_to_string));
            if (!env->ExceptionCheck() && text) msg += ": " + to_string(env, text);
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (text) env->DeleteLocalRef(text);
        }
        if (cls) env->DeleteLocalRef(cls);
        env->DeleteLocalRef(ex);
    }
    return msg;
}

class JniSupertonicRunner final : public speech_core::SupertonicExternalRunner {
public:
    JniSupertonicRunner(JNIEnv* env, jobject runner) {
        if (!runner) throw std::runtime_error("accelerator runner object is null");
        if (env->GetJavaVM(&jvm_) != JNI_OK || !jvm_) {
            throw std::runtime_error("GetJavaVM failed for accelerator runner");
        }
        runner_ = env->NewGlobalRef(runner);
        if (!runner_) throw std::runtime_error("NewGlobalRef failed for accelerator runner");
        jclass cls = env->GetObjectClass(runner);
        if (!cls) {
            const std::string error = consume_java_exception(env, "accelerator runner class lookup failed");
            env->DeleteGlobalRef(runner_);
            runner_ = nullptr;
            throw std::runtime_error(error.empty() ? "accelerator runner class lookup failed" : error);
        }
        duration_ = env->GetMethodID(cls, "runDuration", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)V");
        encoder_ = env->GetMethodID(cls, "runEncoder", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)V");
        vector_ = env->GetMethodID(cls, "runVector", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)V");
        vocoder_ = env->GetMethodID(cls, "runVocoder", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)V");
        jmethodID has_duration = env->GetMethodID(cls, "hasDuration", "()Z");
        jmethodID has_encoder = env->GetMethodID(cls, "hasEncoder", "()Z");
        jmethodID has_vector = env->GetMethodID(cls, "hasVector", "()Z");
        jmethodID has_vocoder = env->GetMethodID(cls, "hasVocoder", "()Z");
        jmethodID backend_report = env->GetMethodID(cls, "backendReport", "()Ljava/lang/String;");
        const std::string err = consume_java_exception(env, "accelerator method lookup failed");
        if (!err.empty() || !duration_ || !encoder_ || !vector_ || !vocoder_ ||
            !has_duration || !has_encoder || !has_vector || !has_vocoder || !backend_report) {
            env->DeleteLocalRef(cls);
            env->DeleteGlobalRef(runner_);
            runner_ = nullptr;
            throw std::runtime_error(err.empty() ? "accelerator runner JNI method missing" : err);
        }

        supports_duration_ = env->CallBooleanMethod(runner_, has_duration) == JNI_TRUE;
        supports_encoder_ = env->CallBooleanMethod(runner_, has_encoder) == JNI_TRUE;
        supports_vector_ = env->CallBooleanMethod(runner_, has_vector) == JNI_TRUE;
        supports_vocoder_ = env->CallBooleanMethod(runner_, has_vocoder) == JNI_TRUE;
        auto report = static_cast<jstring>(env->CallObjectMethod(runner_, backend_report));
        const std::string capability_error = consume_java_exception(env, "accelerator capability lookup failed");
        if (capability_error.empty() && report) backend_report_ = to_string(env, report);
        if (report) env->DeleteLocalRef(report);
        env->DeleteLocalRef(cls);
        if (!capability_error.empty()) {
            env->DeleteGlobalRef(runner_);
            runner_ = nullptr;
            throw std::runtime_error(capability_error);
        }
        if (backend_report_.empty()) backend_report_ = "Android accelerator (stage coverage unavailable)";
    }

    ~JniSupertonicRunner() override {
        bool attached = false;
        if (JNIEnv* env = get_env(attached)) {
            if (runner_) env->DeleteGlobalRef(runner_);
        }
        runner_ = nullptr;
        if (attached && jvm_) jvm_->DetachCurrentThread();
    }

    bool supports_duration() const override { return supports_duration_; }
    bool supports_encoder() const override { return supports_encoder_; }
    bool supports_vector() const override { return supports_vector_; }
    bool supports_vocoder() const override { return supports_vocoder_; }
    std::string backend_report() const override { return backend_report_; }

    void run_duration(const int64_t* text_ids, size_t text_ids_count,
                      const float* style_dp, size_t style_dp_count,
                      const float* text_mask, size_t text_mask_count,
                      float* output) override {
        bool attached = false; JNIEnv* env = require_env(attached);
        jobject a = buffer(env, text_ids, text_ids_count * sizeof(int64_t));
        jobject b = buffer(env, style_dp, style_dp_count * sizeof(float));
        jobject c = buffer(env, text_mask, text_mask_count * sizeof(float));
        jobject d = buffer(env, output, sizeof(float));
        env->CallVoidMethod(runner_, duration_, a, b, c, d);
        cleanup(env, {a,b,c,d});
        finish(env, attached, "QNN/GPU duration invoke failed");
    }

    void run_encoder(const int64_t* text_ids, size_t text_ids_count,
                     const float* style_ttl, size_t style_ttl_count,
                     const float* text_mask, size_t text_mask_count,
                     float* output, size_t output_count) override {
        bool attached = false; JNIEnv* env = require_env(attached);
        jobject a = buffer(env, text_ids, text_ids_count * sizeof(int64_t));
        jobject b = buffer(env, style_ttl, style_ttl_count * sizeof(float));
        jobject c = buffer(env, text_mask, text_mask_count * sizeof(float));
        jobject d = buffer(env, output, output_count * sizeof(float));
        env->CallVoidMethod(runner_, encoder_, a, b, c, d);
        cleanup(env, {a,b,c,d});
        finish(env, attached, "QNN/GPU encoder invoke failed");
    }

    void run_vector(float* noisy_latent, size_t noisy_latent_count,
                    const float* text_emb, size_t text_emb_count,
                    const float* style_ttl, size_t style_ttl_count,
                    const float* latent_mask, size_t latent_mask_count,
                    const float* text_mask, size_t text_mask_count,
                    float current_step, float total_step) override {
        bool attached = false; JNIEnv* env = require_env(attached);
        float cur = current_step, total = total_step;
        std::vector<float> out(noisy_latent_count);
        jobject a = buffer(env, noisy_latent, noisy_latent_count * sizeof(float));
        jobject b = buffer(env, text_emb, text_emb_count * sizeof(float));
        jobject c = buffer(env, style_ttl, style_ttl_count * sizeof(float));
        jobject d = buffer(env, latent_mask, latent_mask_count * sizeof(float));
        jobject e = buffer(env, text_mask, text_mask_count * sizeof(float));
        jobject f = buffer(env, &cur, sizeof(float));
        jobject g = buffer(env, &total, sizeof(float));
        // Do not alias an Interpreter input and output buffer. Some delegates
        // bind external memory directly and are not required to support aliasing.
        jobject h = buffer(env, out.data(), out.size() * sizeof(float));
        env->CallVoidMethod(runner_, vector_, a,b,c,d,e,f,g,h);
        cleanup(env, {a,b,c,d,e,f,g,h});
        finish(env, attached, "QNN/GPU vector estimator invoke failed");
        std::memcpy(noisy_latent, out.data(), out.size() * sizeof(float));
    }

    void run_vocoder(const float* latent, size_t latent_count,
                     float* output, size_t output_count) override {
        bool attached = false; JNIEnv* env = require_env(attached);
        jobject a = buffer(env, latent, latent_count * sizeof(float));
        jobject b = buffer(env, output, output_count * sizeof(float));
        env->CallVoidMethod(runner_, vocoder_, a, b);
        cleanup(env, {a,b});
        finish(env, attached, "QNN/GPU vocoder invoke failed");
    }

private:
    JavaVM* jvm_ = nullptr;
    jobject runner_ = nullptr;
    jmethodID duration_ = nullptr, encoder_ = nullptr, vector_ = nullptr, vocoder_ = nullptr;
    bool supports_duration_ = false;
    bool supports_encoder_ = false;
    bool supports_vector_ = false;
    bool supports_vocoder_ = false;
    std::string backend_report_;

    JNIEnv* get_env(bool& attached) const {
        attached = false;
        if (!jvm_) return nullptr;
        JNIEnv* env = nullptr;
        const jint state = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (state == JNI_OK) return env;
        if (state == JNI_EDETACHED) {
            // Android NDK's C++ JNI wrapper takes JNIEnv** here. Casting to
            // void** is the C ABI form and is rejected by current NDK headers.
            if (jvm_->AttachCurrentThread(&env, nullptr) == JNI_OK) { attached = true; return env; }
        }
        return nullptr;
    }
    JNIEnv* require_env(bool& attached) const {
        JNIEnv* env = get_env(attached);
        if (!env) throw std::runtime_error("could not attach accelerator JNI thread");
        return env;
    }
    static jobject buffer(JNIEnv* env, const void* ptr, size_t bytes) {
        jobject b = env->NewDirectByteBuffer(const_cast<void*>(ptr), static_cast<jlong>(bytes));
        if (!b) throw std::runtime_error("NewDirectByteBuffer failed for accelerator tensor");
        return b;
    }
    static void cleanup(JNIEnv* env, std::initializer_list<jobject> refs) {
        for (jobject r : refs) if (r) env->DeleteLocalRef(r);
    }
    void finish(JNIEnv* env, bool attached, const char* where) const {
        const std::string error = consume_java_exception(env, where);
        if (attached && jvm_) jvm_->DetachCurrentThread();
        if (!error.empty()) throw std::runtime_error(error);
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeCreateSynthesizer(
    JNIEnv* env, jobject, jstring modelDir, jboolean useNnapi, jint backend, jint ttsModel,
    jstring voiceId, jint totalSteps, jfloat speed, jint numThreads, jint chunkCap,
    jstring nativeLibraryDir, jstring acceleratorCacheDir, jobject acceleratorRunner) {
    if (ttsModel != TTS_SUPERTONIC) {
        throw_runtime(env, "Only Supertonic is supported by this TTS engine");
        return 0;
    }
    if (backend < 0 || backend > 2) {
        throw_runtime(env, "Unknown Supertonic inference backend");
        return 0;
    }
    const std::string dir = to_string(env, modelDir);
    const std::string voice = to_string(env, voiceId);
    const std::string native_lib_dir = to_string(env, nativeLibraryDir);
    const std::string accel_cache_dir = to_string(env, acceleratorCacheDir);
    std::shared_ptr<speech_core::SupertonicExternalRunner> external_runner;
    if (backend != 0) {
        try {
            external_runner = std::make_shared<JniSupertonicRunner>(env, acceleratorRunner);
        } catch (const std::exception& e) {
            throw_runtime(env, std::string("Supertonic accelerator runner init failed: ") + e.what());
            return 0;
        }
    }
    auto handle = std::make_unique<SynthesizerHandle>();
    try {
        // Soniqo's Supertonic-3 Android bundle is four LiteRT graphs.
        // CPU execution uses the stable TFLite/XNNPACK interpreter ABI so the
        // requested thread count is actually applied to every graph.
        (void)useNnapi;
        speech_core::LiteRTSupertonicTts::Backend native_backend = speech_core::LiteRTSupertonicTts::Backend::Cpu;
        if (backend == 1) native_backend = speech_core::LiteRTSupertonicTts::Backend::Gpu;
        else if (backend == 2) native_backend = speech_core::LiteRTSupertonicTts::Backend::Npu;
        handle->tts = std::make_unique<speech_core::LiteRTSupertonicTts>(
            dir + "/duration_predictor.tflite",
            dir + "/text_encoder.tflite",
            dir + "/vector_estimator.tflite",
            dir + "/vocoder.tflite",
            dir,
            dir + "/voice_styles",
            false,
            std::max(1, std::min(64, static_cast<int>(numThreads))),
            native_backend,
            native_lib_dir,
            accel_cache_dir,
            std::move(external_runner));
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
    std::ostringstream oss;
    oss.setf(std::ios::fixed);
    oss << std::setprecision(3) << h->supertonic->performance_profile()
        << ";jni_lock_wait=" << h->jni_lock_wait_ms
        << ";jni_arg_convert=" << h->jni_arg_convert_ms
        << ";jni_core=" << h->jni_core_ms
        << ";jni_pcm_convert=" << h->jni_pcm_convert_ms
        << ";jni_bytearray_alloc=" << h->jni_bytearray_alloc_ms
        << ";jni_bytearray_copy=" << h->jni_bytearray_copy_ms
        << ";jni_total=" << h->jni_total_ms
        << ";jni_pcm_samples=" << h->jni_pcm_samples;
    const std::string profile = oss.str();
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

    const auto jni_start = JniClock::now();
    std::vector<int16_t> pcm;
    try {
        const auto lock_start = JniClock::now();
        std::unique_lock<std::mutex> guard(h->mutex);
        h->jni_lock_wait_ms = jni_elapsed_ms(lock_start, JniClock::now());

        const auto arg_start = JniClock::now();
        const std::string native_text = to_string(env, text);
        const std::string native_language = to_string(env, language);
        h->jni_arg_convert_ms = jni_elapsed_ms(arg_start, JniClock::now());

        const auto core_start = JniClock::now();
        // This API is non-streaming. Passing an empty std::function rather than an
        // empty lambda prevents the core from doing streaming-only bookkeeping.
        h->tts->synthesize(native_text, native_language, {});
        h->jni_core_ms = jni_elapsed_ms(core_start, JniClock::now());

        const auto pcm_start = JniClock::now();
        const auto& merged = h->supertonic->last_pcm();
        pcm.resize(merged.size());
        for (size_t i = 0; i < merged.size(); ++i) {
            if (!std::isfinite(merged[i])) {
                throw std::runtime_error("non-finite PCM reached JNI; refusing corrupted audio");
            }
            const float clamped = std::max(-1.0f, std::min(1.0f, merged[i]));
            pcm[i] = static_cast<int16_t>(clamped * 32767.0f);
        }
        h->jni_pcm_samples = static_cast<long long>(pcm.size());
        h->jni_pcm_convert_ms = jni_elapsed_ms(pcm_start, JniClock::now());
    } catch (const std::exception& e) {
        throw_runtime(env, std::string("Supertonic synthesis failed: ") + e.what());
        return nullptr;
    }

    const jsize bytes = static_cast<jsize>(pcm.size() * sizeof(int16_t));
    const auto alloc_start = JniClock::now();
    jbyteArray out = env->NewByteArray(bytes);
    h->jni_bytearray_alloc_ms = jni_elapsed_ms(alloc_start, JniClock::now());
    if (!out) {
        throw_runtime(env, "Failed to allocate PCM ByteArray");
        return nullptr;
    }
    const auto copy_start = JniClock::now();
    if (bytes) env->SetByteArrayRegion(out, 0, bytes, reinterpret_cast<const jbyte*>(pcm.data()));
    h->jni_bytearray_copy_ms = jni_elapsed_ms(copy_start, JniClock::now());
    h->jni_total_ms = jni_elapsed_ms(jni_start, JniClock::now());
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_audio_soniqo_speech_NativeBridge_nativeSynthesizeStreaming(
    JNIEnv* env, jobject, jlong handle, jstring text, jstring language, jobject callback) {
    auto* h = reinterpret_cast<SynthesizerHandle*>(handle);
    if (!h || !h->tts || !callback) { throw_runtime(env, "Invalid TTS streaming request"); return; }
    jclass cls = env->GetObjectClass(callback);
    if (!cls) {
        const std::string error = consume_java_exception(env, "TTS callback class lookup failed");
        throw_runtime(env, error.empty() ? "Invalid TTS callback" : error);
        return;
    }
    jmethodID onChunk = env->GetMethodID(cls, "onChunk", "([BZ)V");
    if (!onChunk) {
        const std::string error = consume_java_exception(env, "TTS callback method lookup failed");
        env->DeleteLocalRef(cls);
        throw_runtime(env, error.empty() ? "Invalid TTS callback" : error);
        return;
    }
    try {
        std::lock_guard<std::mutex> guard(h->mutex);
        h->tts->synthesize(to_string(env, text), to_string(env, language),
            [&](const float* samples, size_t count, bool finalChunk) {
                std::vector<int16_t> pcm(count);
                for (size_t i = 0; i < count; ++i) {
                    if (!std::isfinite(samples[i])) {
                        throw std::runtime_error("non-finite streaming PCM reached JNI; refusing corrupted audio");
                    }
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
