#pragma once

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_layout.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_types.h"

#include <cstdint>
#include <cstring>
#include <fstream>
#include <initializer_list>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#ifndef LOG_TAG
#define LOG_TAG "Speech"
#endif
#ifndef LOGI
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#endif
#ifndef LOGE
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif
#else
#include <cstdio>
#ifndef LOGI
#define LOGI(...) do { std::fprintf(stderr, "[speech] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif
#ifndef LOGE
#define LOGE(...) do { std::fprintf(stderr, "[speech ERROR] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif
#endif

namespace speech_core {

inline void litert_check(LiteRtStatus status, const char* what) {
    if (status != kLiteRtStatusOk) {
        const char* status_text = LiteRtGetStatusString(status);
        std::string message = std::string("LiteRT: ") + what
            + " failed (status=" + std::to_string(status);
        if (status_text && *status_text) {
            message += ", ";
            message += status_text;
        }
        message += ")";
        throw std::runtime_error(message);
    }
}

/// Build a ranked tensor type from element dtype + static dims (rank ≤ 8).
inline LiteRtRankedTensorType make_type(LiteRtElementType dtype,
                                         std::initializer_list<int32_t> dims) {
    LiteRtRankedTensorType t{};
    t.element_type       = dtype;
    t.layout.rank        = static_cast<unsigned int>(dims.size());
    t.layout.has_strides = false;
    size_t i = 0;
    for (int32_t d : dims) t.layout.dimensions[i++] = d;
    return t;
}

/// Total element count from a layout. Assumes all dims are static.
inline size_t layout_element_count(const LiteRtLayout& l) {
    size_t n = 1;
    for (unsigned int i = 0; i < l.rank; ++i) {
        n *= static_cast<size_t>(l.dimensions[i]);
    }
    return n;
}

/// RAII wrapper around a *managed* host-memory TensorBuffer.
/// We tried wrapping caller-owned memory via `LiteRtCreateTensorBufferFromHostMemory`
/// but it returns `kLiteRtStatusErrorMemoryAllocationFailure` for the sizes
/// VoxCPM2 uses — LiteRT v2.1.x's host-memory backend has stricter alignment
/// requirements than std::vector::data() guarantees. The managed-allocation
/// path picks aligned memory internally; we just lock/write before Run and
/// lock/read after. Buffer lifetime is per-Invoke (cheap to construct).
class LiteRtHostBuffer {
public:
    /// Allocate a managed host-memory tensor buffer of the given size and
    /// (optionally) seed it with the contents of `seed` (e.g. an input).
    LiteRtHostBuffer(LiteRtEnvironment env,
                     const LiteRtRankedTensorType& type,
                     size_t bytes,
                     const void* seed = nullptr) : bytes_(bytes) {
        litert_check(LiteRtCreateManagedTensorBuffer(
                         env, kLiteRtTensorBufferTypeHostMemory, &type, bytes, &buf_),
                     "CreateManagedTensorBuffer");
        if (seed) write(seed, bytes);
    }
    /// Allocate using the exact buffer requirements returned by CompiledModel.
    /// Accelerator runtimes (notably Qualcomm HTP and Android GPU backends) may
    /// require alignment or a non-host backing type. Using a generic HostMemory
    /// buffer ignores that contract and can produce invalid accelerator results.
    LiteRtHostBuffer(LiteRtEnvironment env,
                     const LiteRtRankedTensorType& type,
                     LiteRtTensorBufferRequirements requirements,
                     const void* seed = nullptr) {
        litert_check(LiteRtCreateManagedTensorBufferFromRequirements(
                         env, &type, requirements, &buf_),
                     "CreateManagedTensorBufferFromRequirements");
        litert_check(LiteRtGetTensorBufferPackedSize(buf_, &bytes_),
                     "GetTensorBufferPackedSize");
        if (seed) write(seed, bytes_);
    }

    ~LiteRtHostBuffer() { if (buf_) LiteRtDestroyTensorBuffer(buf_); }

    LiteRtHostBuffer(const LiteRtHostBuffer&)            = delete;
    LiteRtHostBuffer& operator=(const LiteRtHostBuffer&) = delete;
    LiteRtHostBuffer(LiteRtHostBuffer&& o) noexcept
        : buf_(o.buf_), bytes_(o.bytes_) { o.buf_ = nullptr; }
    LiteRtHostBuffer& operator=(LiteRtHostBuffer&&)      = delete;

    /// Copy `bytes` from `src` into this buffer's host memory.
    void write(const void* src, size_t bytes) {
        if (bytes > bytes_) throw std::runtime_error("LiteRT TensorBuffer write exceeds packed size");
        void* p = nullptr;
        litert_check(LiteRtLockTensorBuffer(buf_, &p, kLiteRtTensorBufferLockModeWrite),
                     "LockTensorBuffer(write)");
        std::memcpy(p, src, bytes);
        litert_check(LiteRtUnlockTensorBuffer(buf_), "UnlockTensorBuffer");
    }

    /// Copy `bytes` from this buffer's host memory into `dst`.
    void read(void* dst, size_t bytes) const {
        if (bytes > bytes_) throw std::runtime_error("LiteRT TensorBuffer read exceeds packed size");
        void* p = nullptr;
        litert_check(LiteRtLockTensorBuffer(buf_, &p, kLiteRtTensorBufferLockModeRead),
                     "LockTensorBuffer(read)");
        std::memcpy(dst, p, bytes);
        litert_check(LiteRtUnlockTensorBuffer(buf_), "UnlockTensorBuffer");
    }

    size_t             byte_size() const { return bytes_; }
    LiteRtTensorBuffer raw()       const { return buf_; }

private:
    LiteRtTensorBuffer buf_   = nullptr;
    size_t             bytes_ = 0;
};

/// Process-wide LiteRT environment + per-model load helper.
///
/// Backed by `libLiteRt.{so,dll,dylib}` from Google's `ai-edge-litert` package
/// (extracted from the PyPI wheel by `scripts/fetch_litert.sh` locally; pulled
/// the same way in CI). Replaces the legacy `libtensorflowlite_c` path — the
/// old TFLite C API in our v2.18-v2.20 source builds couldn't load >2 GB
/// models (VoxCPM2's text_prefill is 2.08 GB).
class LiteRTEngine {
public:
    static LiteRTEngine& get() {
        static LiteRTEngine instance;
        return instance;
    }

    /// Configure accelerator discovery before the first CompiledModel is created.
    /// Android extracts the runtime/accelerator .so files into nativeLibraryDir.
    /// LiteRT's CompiledModel API needs that directory explicitly in order to
    /// discover out-of-tree accelerator libraries such as ClGl GPU.
    void configure_android_accelerators(const std::string& native_library_dir,
                                        const std::string& compiler_cache_dir) {
        if (native_library_dir.empty()) return;
        if (env_) {
            // All app backends use the same extracted native library directory.
            // Reconfiguration after environment creation is intentionally ignored.
            return;
        }
        runtime_library_dir_ = native_library_dir;
        compiler_plugin_library_dir_ = native_library_dir;
        dispatch_library_dir_ = native_library_dir;
        compiler_cache_dir_ = compiler_cache_dir;
    }

    LiteRtEnvironment env() {
        if (!env_) {
            std::vector<LiteRtEnvOption> options;
            auto add_string = [&](LiteRtEnvOptionTag tag, const std::string& value) {
                if (value.empty()) return;
                LiteRtEnvOption o{};
                o.tag = tag;
                o.value.type = kLiteRtAnyTypeString;
                o.value.str_value = value.c_str();
                options.push_back(o);
            };
            add_string(kLiteRtEnvOptionTagRuntimeLibraryDir, runtime_library_dir_);
            add_string(kLiteRtEnvOptionTagCompilerPluginLibraryDir, compiler_plugin_library_dir_);
            add_string(kLiteRtEnvOptionTagDispatchLibraryDir, dispatch_library_dir_);
            add_string(kLiteRtEnvOptionTagCompilerCacheDir, compiler_cache_dir_);

            // Register all Android accelerators needed by this app. Accelerator
            // selection itself remains strict in load(): GPU requests GPU only,
            // Qualcomm NPU requests NPU only, and no CPU/GPU fallback bit is set.
            // The runtime/compiler/dispatch search paths above point at the APK's
            // nativeLibraryDir, where the ClGl accelerator and Qualcomm QAIRT
            // runtime libraries are packaged.
            LiteRtEnvOption accel{};
            accel.tag = kLiteRtEnvOptionTagAutoRegisterAccelerators;
            accel.value.type = kLiteRtAnyTypeInt;
            accel.value.int_value = static_cast<int64_t>(
                kLiteRtHwAcceleratorCpu | kLiteRtHwAcceleratorGpu | kLiteRtHwAcceleratorNpu);
            options.push_back(accel);

            litert_check(LiteRtCreateEnvironment(static_cast<int>(options.size()),
                                                  options.data(), &env_),
                         "CreateEnvironment");
            LOGI("LiteRT environment: native libs=%s cache=%s",
                 runtime_library_dir_.empty() ? "<default>" : runtime_library_dir_.c_str(),
                 compiler_cache_dir_.empty() ? "<none>" : compiler_cache_dir_.c_str());
        }
        return env_;
    }

    /// Load a `.tflite` and compile it for CPU execution.
    ///
    /// `out_model` and `out_compiled` are caller-owned. Free in reverse order:
    /// `LiteRtDestroyCompiledModel(compiled)` first, then `LiteRtDestroyModel(model)`.
    void load(const std::string& path,
              bool hw_accel,
              LiteRtModel* out_model,
              LiteRtCompiledModel* out_compiled) {
        load(path, hw_accel ? kLiteRtHwAcceleratorGpu : kLiteRtHwAcceleratorCpu, out_model, out_compiled);
    }

    /// Load a `.tflite` and compile it for an explicit accelerator.
    /// CPU is always available. GPU/NPU require the corresponding Android
    /// accelerator/runtime libraries to be packaged with the app/device.
    void load(const std::string& path,
              LiteRtHwAccelerators accelerator,
              LiteRtModel* out_model,
              LiteRtCompiledModel* out_compiled,
              bool allow_cpu_fallback = false) {
        LOGI("Loading LiteRT model: %s",
             path.substr(path.find_last_of('/') + 1).c_str());

        LiteRtEnvironment environment = env();
        LiteRtModel m = nullptr;
        // LiteRT v2.1.5's LiteRtCreateModelFromFile fails on Windows for files
        // ≥ 2 GiB ("Failed to get file size" — 32-bit stat overflow). VoxCPM2's
        // FP16 token-step graph is ~4.3 GiB, so the file API can't load it. Use
        // LiteRtCreateModelFromBuffer (size_t buffer_size is 64-bit) for big
        // files, falling back to the file API otherwise so most loads stay
        // unchanged. The buffer is zero-copy and must outlive the model, so
        // we retain it in the engine singleton. We also cache the buffer by
        // path: a test suite that reloads the same VoxCPM2 graphs across six
        // wrapper instances would otherwise sink 6 × ~6.3 GiB ≈ 38 GiB of
        // RAM (CI Linux runners have ~7 GiB and SIGKILL'd at 9 min). Caching
        // caps it at one copy per path. Threshold is well under 2 GiB so the
        // INT8 prefill graph (~2.0 GiB) also routes through the safer path on
        // Windows.
#if defined(__ANDROID__)
        // LiteRT 2.1.6's file-backed model loader can return
        // kLiteRtStatusErrorFileIO on an otherwise readable app-private model
        // path. Android CompiledModel graphs are small enough here to use the
        // buffer API instead. The retained buffer outlives LiteRtModel.
        constexpr std::uint64_t kBufferThreshold = 0;
#else
        constexpr std::uint64_t kBufferThreshold = std::uint64_t{1} << 30;  // 1 GiB
#endif
        std::ifstream f(path, std::ios::binary | std::ios::ate);
        if (!f) {
            throw std::runtime_error("LiteRT: cannot open " + path);
        }
        const std::uint64_t size = static_cast<std::uint64_t>(f.tellg());
        if (size > kBufferThreshold) {
            auto it = retained_buffers_.find(path);
            const std::vector<char>* buf_ptr = nullptr;
            if (it != retained_buffers_.end()) {
                buf_ptr = it->second.get();
            } else {
                auto buf = std::make_unique<std::vector<char>>(static_cast<size_t>(size));
                f.seekg(0);
                f.read(buf->data(), static_cast<std::streamsize>(size));
                if (!f) {
                    throw std::runtime_error("LiteRT: read failed for " + path);
                }
                buf_ptr = buf.get();
                retained_buffers_.emplace(path, std::move(buf));
            }
            litert_check(LiteRtCreateModelFromBuffer(environment, buf_ptr->data(), buf_ptr->size(), &m),
                         "CreateModelFromBuffer");
        } else {
            f.close();
            litert_check(LiteRtCreateModelFromFile(environment, path.c_str(), &m), "CreateModelFromFile");
        }

        // Build compile options for the requested accelerator. LiteRT rejects
        // a NULL options pointer (kLiteRtStatusErrorInvalidArgument). Android
        // builds may package GPU and vendor NPU runtimes; callers handle a
        // failed accelerator compile and may fall back to CPU.
        LiteRtOptions opts = nullptr;
        litert_check(LiteRtCreateOptions(&opts), "CreateOptions");
        LiteRtHwAccelerators requested = accelerator;
        if (allow_cpu_fallback && accelerator != kLiteRtHwAcceleratorCpu) {
            requested = static_cast<LiteRtHwAccelerators>(
                static_cast<int>(accelerator) | static_cast<int>(kLiteRtHwAcceleratorCpu));
        }
        LiteRtStatus s = LiteRtSetOptionsHardwareAccelerators(opts, requested);
        if (s != kLiteRtStatusOk) {
            LiteRtDestroyOptions(opts);
            LiteRtDestroyModel(m);
            litert_check(s, "SetOptionsHardwareAccelerators");
        }

        LiteRtCompiledModel c = nullptr;
        s = LiteRtCreateCompiledModel(environment, m, opts, &c);
        LiteRtDestroyOptions(opts);
        if (s != kLiteRtStatusOk) {
            LiteRtDestroyModel(m);
            litert_check(s, "CreateCompiledModel");
        }
        *out_model    = m;
        *out_compiled = c;
    }

    /// Release the retained file buffer for `path`, if any.
    ///
    /// The caller MUST have already destroyed any `LiteRtModel` created from
    /// this path -- the model holds a zero-copy pointer into the buffer, so
    /// freeing the buffer while a model still references it is undefined
    /// behaviour. This is the lazy-unload path used by the VoxCPM2 wrapper
    /// to drop the ~4.1 GiB FP16 text_prefill graph between synthesize()
    /// calls, freeing node headroom (the FP16 bundle needs ~10 GiB resident;
    /// servers run the ONNX/CUDA path instead, so LiteRT is the edge/desktop
    /// path). No-op when `path` was below the
    /// CreateModelFromBuffer threshold (1 GiB) and therefore was never
    /// retained.
    void release_buffer(const std::string& path) {
        retained_buffers_.erase(path);
    }

private:
    LiteRTEngine() = default;
    ~LiteRTEngine() { if (env_) LiteRtDestroyEnvironment(env_); }
    LiteRTEngine(const LiteRTEngine&)            = delete;
    LiteRTEngine& operator=(const LiteRTEngine&) = delete;

    LiteRtEnvironment env_ = nullptr;
    std::string runtime_library_dir_;
    std::string compiler_plugin_library_dir_;
    std::string dispatch_library_dir_;
    std::string compiler_cache_dir_;
    // Backing storage for models loaded via LiteRtCreateModelFromBuffer,
    // keyed by file path. LiteRT retains a zero-copy pointer into each
    // buffer for the model's lifetime, so buffers must outlive any models
    // created from them. The engine is a singleton, so this naturally
    // lives until process exit. Keying by path means re-loading the same
    // file reuses the existing buffer instead of allocating another copy
    // (matters for test suites that re-instantiate big-model wrappers).
    std::unordered_map<std::string, std::unique_ptr<std::vector<char>>> retained_buffers_;
};

}  // namespace speech_core
