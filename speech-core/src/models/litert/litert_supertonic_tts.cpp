#include "speech_core/models/litert_supertonic_tts.h"
#include "tflite_c_api_minimal.h"
#include "speech_core/models/litert_engine.h"

#include "speech_core/util/json.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <future>
#include <iomanip>
#include <deque>
#include <limits>
#include <random>
#include <sstream>
#include <utility>
#include <stdexcept>

namespace speech_core {
namespace {
constexpr int kSampleRateConst = 44100;
using SteadyClock = std::chrono::steady_clock;

double elapsed_ms(SteadyClock::time_point a, SteadyClock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

// Local UTF-8 helpers.
//
// supertonic_tokenizer.cpp keeps these in its private anonymous namespace, so
// they are intentionally not visible from this translation unit.  The
// Supertonic synthesis path also needs codepoint-level splitting when the
// duration predictor would otherwise truncate a chunk.
std::vector<char32_t> utf8_to_u32(const std::string& s) {
    std::vector<char32_t> out;
    out.reserve(s.size());

    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        char32_t cp = 0;
        int len = 0;

        if ((c & 0x80u) == 0x00u) {
            cp = c; len = 1;
        } else if ((c & 0xE0u) == 0xC0u) {
            cp = c & 0x1Fu; len = 2;
        } else if ((c & 0xF0u) == 0xE0u) {
            cp = c & 0x0Fu; len = 3;
        } else if ((c & 0xF8u) == 0xF0u) {
            cp = c & 0x07u; len = 4;
        } else {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }

        if (i + static_cast<size_t>(len) > n) {
            out.push_back(0xFFFD);
            break;
        }

        bool ok = true;
        for (int k = 1; k < len; ++k) {
            const unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0u) != 0x80u) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3Fu);
        }

        if (!ok) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }

        out.push_back(cp);
        i += static_cast<size_t>(len);
    }

    return out;
}

void append_u32(std::string& s, char32_t cp) {
    if (cp < 0x80) {
        s.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        s.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        s.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        s.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        s.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        s.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        s.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        s.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        s.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        s.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string u32_to_utf8(const std::vector<char32_t>& v) {
    std::string s;
    s.reserve(v.size() * 2);
    for (const char32_t cp : v) append_u32(s, cp);
    return s;
}

// Choose a semantic split point near the middle of a chunk.
// For whitespace languages (including Korean), never split inside an eojeol/word
// merely because the duration predictor overflowed. Punctuation stays with the
// left side. Only fall back to a codepoint split when there is no safe boundary
// (e.g. an unbroken URL or a long Japanese run without punctuation).
size_t choose_semantic_split(const std::vector<char32_t>& cps) {
    if (cps.size() < 2) return 0;

    const size_t target = cps.size() / 2;
    size_t best = 0;
    size_t best_distance = std::numeric_limits<size_t>::max();

    auto consider = [&](size_t split) {
        if (split == 0 || split >= cps.size()) return;
        // Avoid producing a tiny fragment when a more balanced boundary exists.
        const size_t left = split;
        const size_t right = cps.size() - split;
        if (left < 2 || right < 2) return;
        const size_t distance = left > target ? left - target : target - left;
        if (distance < best_distance) {
            best_distance = distance;
            best = split;
        }
    };

    for (size_t i = 0; i < cps.size(); ++i) {
        const char32_t c = cps[i];
        const bool whitespace =
            c == U' ' || c == U'\t' || c == U'\n' || c == U'\r';
        const bool punctuation =
            c == U',' || c == U';' || c == U':' ||
            c == U'.' || c == U'!' || c == U'?' ||
            c == U'，' || c == U'；' || c == U'：' ||
            c == U'。' || c == U'！' || c == U'？' ||
            c == U'、';
        if (whitespace) {
            // Drop the whitespace from the boundary itself.
            consider(i);
        } else if (punctuation) {
            // Keep sentence/clause punctuation on the left.
            consider(i + 1);
        }
    }

    if (best != 0) return best;
    return target;
}

// Find the active audio range for profiling only.  This does NOT trim or
// modify the waveform.  A conservative threshold avoids classifying the
// low-energy edges of Korean/Chinese phonemes as silence.
std::pair<size_t, size_t> active_range(
    const std::vector<float>& pcm, size_t begin, size_t end) {
    begin = std::min(begin, pcm.size());
    end = std::min(end, pcm.size());
    if (begin >= end) return {begin, begin};

    float peak = 0.0f;
    for (size_t i = begin; i < end; ++i) {
        peak = std::max(peak, std::fabs(pcm[i]));
    }
    if (peak <= 1.0e-6f) return {end, end};

    // Keep the threshold deliberately conservative because this is only a
    // diagnostic/profile measurement.
    const float threshold = std::max(1.0e-4f, peak * 0.005f);

    size_t first = begin;
    while (first < end && std::fabs(pcm[first]) < threshold) ++first;

    size_t last = end;
    while (last > first && std::fabs(pcm[last - 1]) < threshold) --last;

    return {first, last};
}


int graph_latent_frames() {
    if (const char* e = std::getenv("SUPERTONIC_LATENT_FRAMES")) {
        const int v = std::atoi(e);
        if (v > 0) return v;
    }
    return 64;
}

std::vector<float> parse_float_array(const std::string& s, size_t& i) {
    std::vector<float> out;
    json::skip_ws(s, i);
    if (i >= s.size() || s[i] != '[') return out;
    int depth = 0;
    while (i < s.size()) {
        const char c = s[i];
        if (c == '[') { ++depth; ++i; continue; }
        if (c == ']') { --depth; ++i; if (depth == 0) break; continue; }
        if (c == ',' || c == ' ' || c == '\t' || c == '\n' || c == '\r') { ++i; continue; }
        const std::string v = json::parse_value_raw(s, i);
        if (!v.empty()) out.push_back(std::strtof(v.c_str(), nullptr));
    }
    return out;
}

std::vector<float> extract_style(const std::string& text, const std::string& key) {
    size_t i = 0;
    json::skip_ws(text, i);
    if (i >= text.size() || text[i] != '{') return {};
    ++i;
    while (i < text.size()) {
        json::skip_ws(text, i);
        if (text[i] == '}') break;
        if (text[i] == ',') { ++i; continue; }
        const std::string k = json::parse_string(text, i);
        json::skip_ws(text, i);
        if (i < text.size() && text[i] == ':') ++i;
        json::skip_ws(text, i);
        if (k == key && i < text.size() && text[i] == '[') {
            return parse_float_array(text, i);
        }
        if (k == key && i < text.size() && text[i] == '{') {
            ++i;
            while (i < text.size()) {
                json::skip_ws(text, i);
                if (text[i] == '}') { ++i; break; }
                if (text[i] == ',') { ++i; continue; }
                const std::string kk = json::parse_string(text, i);
                json::skip_ws(text, i);
                if (i < text.size() && text[i] == ':') ++i;
                json::skip_ws(text, i);
                if (kk == "data" && i < text.size() && text[i] == '[') return parse_float_array(text, i);
                json::skip_value(text, i);
            }
            return {};
        }
        json::skip_value(text, i);
    }
    return {};
}


void tflite_check(TfLiteStatus status, const char* operation) {
    if (status != kTfLiteOk) {
        throw std::runtime_error(std::string("Supertonic TFLite: ") + operation + " failed");
    }
}

bool tensor_has_shape(const TfLiteTensor* tensor, std::initializer_list<int> expected) {
    if (!tensor || TfLiteTensorNumDims(tensor) != static_cast<int>(expected.size())) return false;
    int i = 0;
    for (int d : expected) {
        if (TfLiteTensorDim(tensor, i++) != d) return false;
    }
    return true;
}

TfLiteTensor* find_input(TfLiteInterpreter* interpreter, std::initializer_list<int> shape) {
    TfLiteTensor* result = nullptr;
    const int count = TfLiteInterpreterGetInputTensorCount(interpreter);
    for (int i = 0; i < count; ++i) {
        auto* t = TfLiteInterpreterGetInputTensor(interpreter, i);
        if (!tensor_has_shape(t, shape)) continue;
        if (result) throw std::runtime_error("Supertonic: ambiguous input shape");
        result = t;
    }
    if (!result) throw std::runtime_error("Supertonic: required input tensor missing");
    return result;
}

const TfLiteTensor* find_output(TfLiteInterpreter* interpreter, std::initializer_list<int> shape) {
    const TfLiteTensor* result = nullptr;
    const int count = TfLiteInterpreterGetOutputTensorCount(interpreter);
    for (int i = 0; i < count; ++i) {
        const auto* t = TfLiteInterpreterGetOutputTensor(interpreter, i);
        if (!tensor_has_shape(t, shape)) continue;
        if (result) throw std::runtime_error("Supertonic: ambiguous output shape");
        result = t;
    }
    if (!result) throw std::runtime_error("Supertonic: required output tensor missing");
    return result;
}

struct Graph {
    using Backend = LiteRTSupertonicTts::Backend;

    Backend backend = Backend::Cpu;
    std::string name;
    std::string model_path;
    int cpu_threads = 4;

    // CPU reference path: legacy TfLite C API + XNNPACK. This path is kept
    // unchanged because it is the already-verified fast baseline on Android.
    TfLiteModel* model = nullptr;
    TfLiteOpaqueDelegate* delegate = nullptr;
    bool owns_delegate = false;
    TfLiteInterpreter* interpreter = nullptr;

    // Strict accelerator path: LiteRT CompiledModel. GPU and NPU are requested
    // WITHOUT CPU/GPU fallback and are accepted only if LiteRT reports that the
    // entire graph is accelerated. This deliberately forbids mixed execution.
    LiteRtModel accel_model = nullptr;
    LiteRtCompiledModel compiled = nullptr;
    LiteRtSignature signature = nullptr;
    std::vector<LiteRtRankedTensorType> input_types;
    std::vector<LiteRtRankedTensorType> output_types;
    std::vector<std::string> input_names;
    std::vector<std::string> output_names;
    std::vector<std::unique_ptr<LiteRtHostBuffer>> input_buffers;
    std::vector<std::unique_ptr<LiteRtHostBuffer>> output_buffers;
    bool fully_accelerated = false;
    std::string acceleration_detail;

    ~Graph() { release_resources(); }

    Graph() = default;
    Graph(const Graph&) = delete;
    Graph& operator=(const Graph&) = delete;

    void release_resources() noexcept {
        input_buffers.clear();
        output_buffers.clear();
        input_types.clear();
        output_types.clear();
        input_names.clear();
        output_names.clear();
        signature = nullptr;
        if (interpreter) { TfLiteInterpreterDelete(interpreter); interpreter = nullptr; }
        if (delegate && owns_delegate) TfLiteXNNPackDelegateDelete(delegate);
        delegate = nullptr;
        owns_delegate = false;
        if (model) { TfLiteModelDelete(model); model = nullptr; }
        if (compiled) { LiteRtDestroyCompiledModel(compiled); compiled = nullptr; }
        if (accel_model) { LiteRtDestroyModel(accel_model); accel_model = nullptr; }
    }

    bool uses_interpreter_primary() const { return backend == Backend::Cpu; }

    static size_t elem_size(LiteRtElementType t) {
        switch (t) {
            case kLiteRtElementTypeFloat32: return 4;
            case kLiteRtElementTypeInt64: return 8;
            case kLiteRtElementTypeInt32: return 4;
            case kLiteRtElementTypeFloat16: return 2;
            default: throw std::runtime_error("Supertonic: unsupported accelerated tensor type");
        }
    }

    static bool layout_has_shape(const LiteRtLayout& l, const std::vector<int>& shape) {
        if (l.rank != shape.size()) return false;
        for (size_t i = 0; i < shape.size(); ++i) if (l.dimensions[i] != shape[i]) return false;
        return true;
    }

    static bool tensor_has_shape_vec(const TfLiteTensor* tensor, const std::vector<int>& expected) {
        if (!tensor || TfLiteTensorNumDims(tensor) != static_cast<int>(expected.size())) return false;
        for (size_t i = 0; i < expected.size(); ++i)
            if (TfLiteTensorDim(tensor, static_cast<int>(i)) != expected[i]) return false;
        return true;
    }

    int find_accel_input(const std::vector<int>& shape, int occurrence = 0) const {
        int seen = 0;
        for (size_t i = 0; i < input_types.size(); ++i) {
            if (!layout_has_shape(input_types[i].layout, shape)) continue;
            if (seen++ == occurrence) return static_cast<int>(i);
        }
        throw std::runtime_error("Supertonic[" + name + "]: required accelerated input tensor missing");
    }

    int find_accel_output(const std::vector<int>& shape, int occurrence = 0) const {
        int seen = 0;
        for (size_t i = 0; i < output_types.size(); ++i) {
            if (!layout_has_shape(output_types[i].layout, shape)) continue;
            if (seen++ == occurrence) return static_cast<int>(i);
        }
        throw std::runtime_error("Supertonic[" + name + "]: required accelerated output tensor missing");
    }

    static std::string normalize_tensor_name(std::string value) {
        // SavedModel/TFLite signatures sometimes prefix semantic names with
        // "serving_default_" or append a ':0' tensor suffix. Strip those so
        // the Supertonic canonical names remain stable across conversions.
        constexpr const char* prefix = "serving_default_";
        if (value.rfind(prefix, 0) == 0) value.erase(0, std::strlen(prefix));
        const auto colon = value.find(':');
        if (colon != std::string::npos) value.erase(colon);
        return value;
    }

    int expected_generic_arg_index(const std::string& semantic) const {
        // Supertonic-3-LiteRT was exported from positional Python callables. In
        // the published models LiteRT therefore exposes generic signature names
        // such as args_0/args_1/... instead of the semantic names used by the
        // C++ pipeline. Map those positional names explicitly per graph.
        //
        // IMPORTANT: never use signature *iteration order* here. LiteRT is free
        // to return names in an order such as args_2,args_0,args_1 (observed on
        // the real duration model). The args_N suffix is the stable positional
        // identity.
        if (name == "duration") {
            if (semantic == "text_ids") return 0;
            if (semantic == "style_dp") return 1;
            if (semantic == "text_mask") return 2;
        } else if (name == "encoder") {
            if (semantic == "text_ids") return 0;
            if (semantic == "style_ttl") return 1;
            if (semantic == "text_mask") return 2;
        } else if (name == "vector_estimator") {
            // vector_estimator(xt, text_emb, style_ttl, latent_mask,
            //                  text_mask, current_step, total_step)
            if (semantic == "noisy_latent") return 0;
            if (semantic == "text_emb") return 1;
            if (semantic == "style_ttl") return 2;
            if (semantic == "latent_mask") return 3;
            if (semantic == "text_mask") return 4;
            if (semantic == "current_step") return 5;
            if (semantic == "total_step") return 6;
        } else if (name == "vocoder") {
            if (semantic == "latent") return 0;
        }
        return -1;
    }

    int find_accel_input_name(const std::string& semantic,
                              const std::vector<int>& expected_shape,
                              int shape_occurrence = 0) const {
        const std::string want = normalize_tensor_name(semantic);

        // 1) Prefer a real semantic signature name when a future/re-exported
        // model contains one.
        for (size_t i = 0; i < input_names.size(); ++i) {
            if (normalize_tensor_name(input_names[i]) == want) {
                if (!layout_has_shape(input_types[i].layout, expected_shape)) {
                    throw std::runtime_error("Supertonic[" + name + "]: input '" + semantic +
                                             "' name matched but shape mismatched");
                }
                return static_cast<int>(i);
            }
        }

        // 2) Published soniqo Supertonic-3-LiteRT uses args_N positional
        // signatures. Resolve by the encoded N, not by returned list order.
        const int arg_index = expected_generic_arg_index(semantic);
        if (arg_index >= 0) {
            const std::string generic = "args_" + std::to_string(arg_index);
            for (size_t i = 0; i < input_names.size(); ++i) {
                if (normalize_tensor_name(input_names[i]) != generic) continue;
                if (!layout_has_shape(input_types[i].layout, expected_shape)) {
                    throw std::runtime_error("Supertonic[" + name + "]: mapped input '" + semantic +
                                             "' -> " + generic + " but shape mismatched");
                }
                LOGI("Supertonic[%s] mapped semantic input %s -> %s (signature slot %zu)",
                     name.c_str(), semantic.c_str(), generic.c_str(), i);
                return static_cast<int>(i);
            }
        }

        // 3) Last-resort compatibility for exports that have neither semantic
        // names nor args_N names. Shape matching is safe for unique shapes;
        // occurrence disambiguates the two {1} VE scalars.
        int seen = 0;
        for (size_t i = 0; i < input_types.size(); ++i) {
            if (!layout_has_shape(input_types[i].layout, expected_shape)) continue;
            if (seen++ == shape_occurrence) {
                LOGI("Supertonic[%s] fallback shape-mapped semantic input %s -> %s (slot %zu)",
                     name.c_str(), semantic.c_str(), input_names[i].c_str(), i);
                return static_cast<int>(i);
            }
        }

        throw std::runtime_error("Supertonic[" + name + "]: accelerated input '" + semantic +
                                 "' missing; expected=" +
                                 (arg_index >= 0 ? "args_" + std::to_string(arg_index) : std::string("semantic/shape")) +
                                 "; signature names=" + join_names(input_names));
    }

    static std::string join_names(const std::vector<std::string>& names) {
        std::string out;
        for (size_t i = 0; i < names.size(); ++i) {
            if (i) out += ",";
            out += names[i];
        }
        return out;
    }

    static bool all_finite(const float* x, size_t n) {
        if (!x && n) return false;
        for (size_t i = 0; i < n; ++i) if (!std::isfinite(x[i])) return false;
        return true;
    }

    void load(const std::string& path,
              int threads,
              Backend requested,
              const char* graph_name,
              std::uintptr_t /*unused_qnn_delegate_handle*/ = 0) {
        backend = requested;
        name = graph_name ? graph_name : "graph";
        model_path = path;
        cpu_threads = std::max(1, threads);

        if (backend == Backend::Cpu) {
            model = TfLiteModelCreateFromFile(path.c_str());
            if (!model) throw std::runtime_error("Supertonic[" + name + "]: failed to load model " + path);
            auto* options = TfLiteInterpreterOptionsCreate();
            if (!options) throw std::runtime_error("Supertonic[" + name + "]: failed to create interpreter options");
            TfLiteInterpreterOptionsSetNumThreads(options, threads);
            auto xnn = TfLiteXNNPackDelegateOptionsDefault();
            xnn.num_threads = threads;
            delegate = TfLiteXNNPackDelegateCreate(&xnn);
            owns_delegate = true;
            if (!delegate) {
                TfLiteInterpreterOptionsDelete(options);
                throw std::runtime_error("Supertonic[" + name + "]: failed to create XNNPACK delegate");
            }
            TfLiteInterpreterOptionsAddDelegate(options, delegate);
            interpreter = TfLiteInterpreterCreate(model, options);
            TfLiteInterpreterOptionsDelete(options);
            if (!interpreter) throw std::runtime_error("Supertonic[" + name + "]: CPU interpreter creation failed");
            if (TfLiteInterpreterAllocateTensors(interpreter) != kTfLiteOk)
                throw std::runtime_error("Supertonic[" + name + "]: CPU AllocateTensors failed");
            fully_accelerated = true;
            acceleration_detail = "CPU/XNNPACK";
            return;
        }

        const LiteRtHwAccelerators accel = backend == Backend::Gpu
            ? kLiteRtHwAcceleratorGpu
            : kLiteRtHwAcceleratorNpu;
        const char* accel_name = backend == Backend::Gpu ? "GPU" : "Qualcomm NPU/QNN";

        try {
            LiteRTEngine::get().load(path, accel, &accel_model, &compiled,
                                     /*allow_cpu_fallback=*/false);
        } catch (const std::exception& e) {
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name +
                                     " strict compile failed: " + e.what());
        }

        bool full = false;
        const LiteRtStatus full_status = LiteRtCompiledModelIsFullyAccelerated(compiled, &full);
        if (full_status != kLiteRtStatusOk) {
            const char* status_text = LiteRtGetStatusString(full_status);
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name +
                                     " full-delegation check failed (status=" +
                                     std::to_string(full_status) +
                                     (status_text && *status_text ? std::string(", ") + status_text : std::string()) + ")");
        }
        if (!full) {
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name +
                                     " FULL DELEGATION FAILED: LiteRT reported a partial graph. "
                                     "Strict mode forbids CPU/GPU fallback.");
        }
        fully_accelerated = true;
        acceleration_detail = backend == Backend::Gpu
            ? "LiteRT GPU STRICT FULL"
            : "LiteRT Qualcomm NPU/QNN STRICT FULL";

        try {
            litert_check(LiteRtGetModelSignature(accel_model, 0, &signature), "Supertonic signature");
            LiteRtParamIndex ni = 0, no = 0;
            litert_check(LiteRtGetNumSignatureInputs(signature, &ni), "Supertonic num inputs");
            litert_check(LiteRtGetNumSignatureOutputs(signature, &no), "Supertonic num outputs");
            input_types.resize(ni); output_types.resize(no);
            input_names.resize(ni); output_names.resize(no);
            input_buffers.reserve(ni); output_buffers.reserve(no);
            auto env = LiteRTEngine::get().env();
            for (LiteRtParamIndex i = 0; i < ni; ++i) {
                const char* tensor_name = nullptr;
                litert_check(LiteRtGetSignatureInputName(signature, i, &tensor_name), "Supertonic input name");
                input_names[i] = tensor_name ? tensor_name : "";
                LiteRtTensor t = nullptr;
                litert_check(LiteRtGetSignatureInputTensorByIndex(signature, i, &t), "Supertonic input tensor");
                litert_check(LiteRtGetRankedTensorType(t, &input_types[i]), "Supertonic input type");
                if (backend == Backend::Npu) {
                    // Qualcomm HTP has its own alignment/backing requirements.
                    // Obey the CompiledModel contract instead of forcing host memory.
                    LiteRtTensorBufferRequirements req = nullptr;
                    litert_check(LiteRtGetCompiledModelInputBufferRequirements(compiled, 0, i, &req),
                                 "Supertonic input buffer requirements");
                    input_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, input_types[i], req));
                } else {
                    // The Android OpenCL accelerator currently advertises a packed
                    // OpenCL buffer type that managed allocation may reject; host
                    // buffers are the documented working path for this delegate.
                    const size_t bytes = layout_element_count(input_types[i].layout) * elem_size(input_types[i].element_type);
                    input_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, input_types[i], bytes));
                }
            }
            for (LiteRtParamIndex i = 0; i < no; ++i) {
                const char* tensor_name = nullptr;
                litert_check(LiteRtGetSignatureOutputName(signature, i, &tensor_name), "Supertonic output name");
                output_names[i] = tensor_name ? tensor_name : "";
                LiteRtTensor t = nullptr;
                litert_check(LiteRtGetSignatureOutputTensorByIndex(signature, i, &t), "Supertonic output tensor");
                litert_check(LiteRtGetRankedTensorType(t, &output_types[i]), "Supertonic output type");
                if (backend == Backend::Npu) {
                    LiteRtTensorBufferRequirements req = nullptr;
                    litert_check(LiteRtGetCompiledModelOutputBufferRequirements(compiled, 0, i, &req),
                                 "Supertonic output buffer requirements");
                    output_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, output_types[i], req));
                } else {
                    const size_t bytes = layout_element_count(output_types[i].layout) * elem_size(output_types[i].element_type);
                    output_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, output_types[i], bytes));
                }
            }
            LOGI("Supertonic[%s] strict signature inputs=[%s] outputs=[%s]", name.c_str(),
                 join_names(input_names).c_str(), join_names(output_names).c_str());

        } catch (const std::exception& e) {
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name + " I/O setup failed: " + e.what());
        }
    }

    void write_input_dims(const std::vector<int>& shape, const void* data, size_t bytes, int occurrence = 0) {
        if (uses_interpreter_primary()) {
            int seen = 0;
            TfLiteTensor* target = nullptr;
            const int count = TfLiteInterpreterGetInputTensorCount(interpreter);
            for (int i = 0; i < count; ++i) {
                auto* t = TfLiteInterpreterGetInputTensor(interpreter, i);
                if (!tensor_has_shape_vec(t, shape)) continue;
                if (seen++ == occurrence) { target = t; break; }
            }
            if (!target) throw std::runtime_error("Supertonic[" + name + "]: required input tensor missing");
            tflite_check(TfLiteTensorCopyFromBuffer(target, data, bytes), "input copy");
        } else {
            input_buffers[find_accel_input(shape, occurrence)]->write(data, bytes);
        }
    }

    void write_input(std::initializer_list<int> shape, const void* data, size_t bytes, int occurrence = 0) {
        write_input_dims(std::vector<int>(shape), data, bytes, occurrence);
    }

    void write_input_named(const char* semantic_name, std::initializer_list<int> cpu_shape,
                           const void* data, size_t bytes, int cpu_occurrence = 0) {
        if (uses_interpreter_primary()) {
            write_input(cpu_shape, data, bytes, cpu_occurrence);
            return;
        }
        const std::vector<int> expected_shape(cpu_shape);
        const int index = find_accel_input_name(semantic_name ? semantic_name : "", expected_shape, cpu_occurrence);
        const auto expected = layout_element_count(input_types[index].layout) * elem_size(input_types[index].element_type);
        if (bytes != expected) {
            throw std::runtime_error("Supertonic[" + name + "]: input '" + semantic_name +
                                     "' byte-size mismatch: got=" + std::to_string(bytes) +
                                     " expected=" + std::to_string(expected));
        }
        input_buffers[index]->write(data, bytes);
    }

    void run(const char* what) {
        if (uses_interpreter_primary()) {
            if (TfLiteInterpreterInvoke(interpreter) != kTfLiteOk)
                throw std::runtime_error("Supertonic[" + name + "]: " + std::string(what) + " failed");
            return;
        }
        std::vector<LiteRtTensorBuffer> ins, outs;
        ins.reserve(input_buffers.size()); outs.reserve(output_buffers.size());
        for (auto& b : input_buffers) ins.push_back(b->raw());
        for (auto& b : output_buffers) outs.push_back(b->raw());
        const auto status = LiteRtRunCompiledModel(compiled, 0, ins.size(), ins.data(), outs.size(), outs.data());
        if (status != kLiteRtStatusOk) {
            const char* status_text = LiteRtGetStatusString(status);
            throw std::runtime_error("Supertonic[" + name + "]: strict accelerator invoke failed (status=" +
                                     std::to_string(status) +
                                     (status_text && *status_text ? std::string(", ") + status_text : std::string()) + ")");
        }
    }

    void read_output(std::initializer_list<int> shape_il, void* data, size_t bytes, int occurrence = 0) {
        const std::vector<int> shape(shape_il);
        if (uses_interpreter_primary()) {
            const TfLiteTensor* result = nullptr;
            const int count = TfLiteInterpreterGetOutputTensorCount(interpreter);
            int seen = 0;
            for (int i = 0; i < count; ++i) {
                const auto* t = TfLiteInterpreterGetOutputTensor(interpreter, i);
                if (!tensor_has_shape_vec(t, shape)) continue;
                if (seen++ == occurrence) { result = t; break; }
            }
            if (!result) throw std::runtime_error("Supertonic[" + name + "]: required output tensor missing");
            if (TfLiteTensorCopyToBuffer(result, data, bytes) != kTfLiteOk)
                throw std::runtime_error("Supertonic[" + name + "]: output copy failed");
            return;
        }

        output_buffers[find_accel_output(shape, occurrence)]->read(data, bytes);

        // Full accelerator means no silent CPU rescue. If the hardware path
        // produces invalid floats, stop synthesis before those values can turn
        // into the loud digital/static noise seen in earlier QNN experiments.
        if (bytes % sizeof(float) == 0) {
            const size_t n = bytes / sizeof(float);
            const auto* f = static_cast<const float*>(data);
            if (!all_finite(f, n)) {
                throw std::runtime_error("Supertonic[" + name + "]: STRICT accelerator output contains NaN/Inf; audio blocked");
            }
            if (name == "duration" && n > 0 && !(f[0] > 0.0f && f[0] < 60.0f)) {
                throw std::runtime_error("Supertonic[duration]: STRICT accelerator returned invalid duration");
            }
            if (name == "vocoder") {
                float peak = 0.0f;
                for (size_t i = 0; i < n; ++i) peak = std::max(peak, std::fabs(f[i]));
                if (!std::isfinite(peak) || peak > 8.0f)
                    throw std::runtime_error("Supertonic[vocoder]: STRICT accelerator waveform is numerically invalid; audio blocked");
            }
        }
    }

    std::string decision_label() const {
        if (backend == Backend::Cpu) return "CPU/XNNPACK";
        return backend == Backend::Gpu ? "GPU(FULL)" : "NPU(FULL)";
    }
};
std::vector<float> trim_edge_silence(const std::vector<float>& pcm, int trailing_trim_ms) {
    if (trailing_trim_ms <= 0 || pcm.size() < 4096) return pcm;
    float peak = 0.0f;
    for (float x : pcm) peak = std::max(peak, std::fabs(x));
    if (peak < 1.0e-5f) return pcm;

    const float threshold = std::max(5.0e-4f, peak * 0.003f);
    const size_t min_run = static_cast<size_t>(0.035 * kSampleRateConst);
    const size_t leading_max_trim = static_cast<size_t>(0.45 * kSampleRateConst);
    const size_t trailing_max_trim = static_cast<size_t>(std::max(0, trailing_trim_ms) * kSampleRateConst / 1000);

    size_t first = 0;
    size_t low = 0;
    for (size_t i = 0; i < pcm.size() && i < leading_max_trim + min_run; ++i) {
        if (std::fabs(pcm[i]) < threshold) {
            ++low;
        } else {
            if (low >= min_run) first = i;
            else first = 0;
            break;
        }
    }
    if (low >= min_run && first == 0) first = std::min(low, leading_max_trim);
    if (first > leading_max_trim) first = leading_max_trim;

    size_t last = pcm.size();
    low = 0;
    for (size_t i = pcm.size(); i-- > 0 && (pcm.size() - i) <= trailing_max_trim + min_run;) {
        if (std::fabs(pcm[i]) < threshold) {
            ++low;
        } else {
            if (low >= min_run) last = i + 1;
            break;
        }
    }
    if (low >= min_run && last == pcm.size()) last = pcm.size() - std::min(low, trailing_max_trim);
    if (last > pcm.size()) last = pcm.size();

    if (first >= last) return pcm;
    std::vector<float> out(pcm.begin() + static_cast<std::ptrdiff_t>(first),
                          pcm.begin() + static_cast<std::ptrdiff_t>(last));
    return out.size() >= 2048 ? out : pcm;
}

void load_style(const std::string& path, std::vector<float>& ttl, std::vector<float>& dp) {
    const std::string text = json::read_file(path);
    if (text.empty()) throw std::runtime_error("Supertonic: cannot read voice style " + path);
    ttl = extract_style(text, "style_ttl");
    dp = extract_style(text, "style_dp");
}
} // namespace


struct LiteRTSupertonicTts::InterpreterState {
    Graph duration;
    Graph encoder;
    Graph vector;
    Graph vocoder;
};

LiteRTSupertonicTts::LiteRTSupertonicTts(
    const std::string& duration_path,
    const std::string& text_encoder_path,
    const std::string& vector_estimator_path,
    const std::string& vocoder_path,
    const std::string& tokenizer_dir,
    const std::string& voice_styles_dir,
    bool hw_accel,
    int num_threads,
    Backend backend,
    std::string native_library_dir,
    std::string accelerator_cache_dir,
    std::shared_ptr<SupertonicExternalRunner> external_runner)
    : num_threads_(std::max(1, std::min(64, num_threads))),
      backend_(backend),
      native_library_dir_(std::move(native_library_dir)),
      accelerator_cache_dir_(std::move(accelerator_cache_dir)),
      external_runner_(std::move(external_runner)),
      duration_path_(duration_path),
      text_encoder_path_(text_encoder_path),
      vector_estimator_path_(vector_estimator_path),
      vocoder_path_(vocoder_path),
      tokenizer_dir_(tokenizer_dir),
      voice_styles_dir_(voice_styles_dir) {
    (void)hw_accel;

    if (backend_ == Backend::Cpu) {
        // Keep the already-measured CPU/XNNPACK implementation completely
        // untouched. Accelerator experiments never replace or wrap this path.
        interp_ = std::make_unique<InterpreterState>();
        interp_->duration.load(duration_path_, num_threads_, Backend::Cpu, "duration");
        interp_->encoder.load(text_encoder_path_, num_threads_, Backend::Cpu, "encoder");
        interp_->vector.load(vector_estimator_path_, num_threads_, Backend::Cpu, "vector_estimator");
        interp_->vocoder.load(vocoder_path_, num_threads_, Backend::Cpu, "vocoder");
        backend_report_ = "CPU/XNNPACK";
    } else {
        if (!external_runner_) {
            throw std::runtime_error("Supertonic accelerator delegate runner was not supplied");
        }

        const bool cpu_duration = !external_runner_->supports_duration();
        const bool cpu_encoder = !external_runner_->supports_encoder();
        const bool cpu_vector = !external_runner_->supports_vector();
        const bool cpu_vocoder = !external_runner_->supports_vocoder();
        if (cpu_duration || cpu_encoder || cpu_vector || cpu_vocoder) {
            interp_ = std::make_unique<InterpreterState>();
            if (cpu_duration) interp_->duration.load(duration_path_, num_threads_, Backend::Cpu, "duration");
            if (cpu_encoder) interp_->encoder.load(text_encoder_path_, num_threads_, Backend::Cpu, "encoder");
            if (cpu_vector) interp_->vector.load(vector_estimator_path_, num_threads_, Backend::Cpu, "vector_estimator");
            if (cpu_vocoder) interp_->vocoder.load(vocoder_path_, num_threads_, Backend::Cpu, "vocoder");
        }
        backend_report_ = external_runner_->backend_report();
        // Pre-generation would create additional delegates/interpreters and can
        // increase memory pressure dramatically. Keep it enabled for CPU only.
        pre_generation_ = false;
    }

    namespace fs = std::filesystem;
    tokenizer_ = std::make_unique<SupertonicTokenizer>(
        (fs::path(tokenizer_dir_) / "unicode_indexer.json").string(),
        (fs::path(tokenizer_dir_) / "tts.json").string());

    for (const auto& entry : fs::directory_iterator(voice_styles_dir_)) {
        if (entry.path().extension() != ".json") continue;
        VoiceStyle v;
        load_style(entry.path().string(), v.style_ttl, v.style_dp);
        if (v.style_ttl.size() != kStyleTtlFloats || v.style_dp.size() != kStyleDpFloats) continue;
        voices_.emplace(entry.path().stem().string(), std::move(v));
    }
    if (voices_.empty()) throw std::runtime_error("Supertonic: no voice styles in " + voice_styles_dir);
    if (!voices_.count(voice_id_)) voice_id_ = voices_.begin()->first;
}

LiteRTSupertonicTts::~LiteRTSupertonicTts() = default;

void LiteRTSupertonicTts::destroy_graphs() noexcept { interp_.reset(); }

void LiteRTSupertonicTts::cancel() {
    cancelled_.store(true);
    for (auto& engine : pregen_engines_) {
        if (engine) engine->cancel();
    }
}

void LiteRTSupertonicTts::set_pre_generation(bool enabled) {
    // Accelerator delegates are intentionally single-runner/single-thread. Do
    // not create duplicate 380 MB delegate engines for speculative pre-generation.
    if (backend_ != Backend::Cpu) { pre_generation_ = false; return; }
    // Settings may be toggled while Android is still synthesizing. Do not destroy
    // in-flight pre-generation engines here; the next synthesis observes the new
    // flag safely after the current request finishes.
    pre_generation_ = enabled;
}

void LiteRTSupertonicTts::set_pre_generation_queue(int depth) {
    // The engine pool is resized only at the start of the next synthesis request,
    // after the current request has released its native synthesis lock.
    pre_generation_queue_ = std::max(2, std::min(3, depth));
}

void LiteRTSupertonicTts::set_chunk_gap_ms(int min_ms, int max_ms) {
    chunk_gap_min_ms_ = std::max(0, std::min(2000, min_ms));
    chunk_gap_max_ms_ = std::max(chunk_gap_min_ms_, std::min(2000, max_ms));
}

void LiteRTSupertonicTts::set_trailing_silence_trim_ms(int trim_ms) {
    trailing_silence_trim_ms_ = std::max(0, std::min(500, trim_ms));
}

void LiteRTSupertonicTts::set_total_step(int total_step) {
    total_step_ = std::max(1, std::min(64, total_step));
    for (auto& engine : pregen_engines_) if (engine) engine->set_total_step(total_step_);
}

void LiteRTSupertonicTts::set_chunk_cap(int chunk_cap) {
    chunk_cap_ = std::max(24, std::min(96, chunk_cap));
    for (auto& engine : pregen_engines_) if (engine) engine->set_chunk_cap(chunk_cap_);
}

void LiteRTSupertonicTts::set_voice(const std::string& voice_id) {
    if (!voices_.count(voice_id)) throw std::invalid_argument("Supertonic: unknown voice '" + voice_id + "'");
    voice_id_ = voice_id;
    for (auto& engine : pregen_engines_) if (engine) engine->set_voice(voice_id);
}

std::vector<std::string> LiteRTSupertonicTts::voices() const {
    std::vector<std::string> ids;
    ids.reserve(voices_.size());
    for (const auto& kv : voices_) ids.push_back(kv.first);
    std::sort(ids.begin(), ids.end());
    return ids;
}

const LiteRTSupertonicTts::VoiceStyle& LiteRTSupertonicTts::current_voice() const {
    const auto it = voices_.find(voice_id_);
    if (it == voices_.end()) throw std::runtime_error("Supertonic: voice not loaded");
    return it->second;
}

void LiteRTSupertonicTts::synthesize(const std::string& text,
                                     const std::string& language,
                                     TTSChunkCallback on_chunk) {
    const auto total_start = SteadyClock::now();
    profile_.reset();
    last_pcm_.clear();
    cancelled_.store(false);
    seed_used_ = seed_ == 0 ? std::random_device{}() : seed_;

    // Keep the model itself at 1x and apply user-selected speech rate after synthesis.
    // This prevents high-speed generation from shortening the predicted duration so far
    // that the fixed L=64 vocoder window cuts words off.
    speed_ = 1.0f;

    const double window_s = static_cast<double>(graph_latent_frames()) * kChunkSamples / kSampleRate;
    const bool cjk = language == "ko" || language == "ja" || language == "zh";
    // The old cap (roughly 24 Korean characters) caused excessive chunking and audible gaps.
    // Keep enough text per graph while retaining a safety margin for the fixed latent window.
    const int auto_cap = std::max(24, static_cast<int>(std::min(96.0, window_s * (cjk ? 13.0 : 20.0))));
    const int dur_cap = std::max(24, std::min(96, chunk_cap_ > 0 ? chunk_cap_ : auto_cap));
    const auto chunking_start = SteadyClock::now();
    const auto chunks = tokenizer_->chunk(text, language, dur_cap);
    profile_.chunking_ms += elapsed_ms(chunking_start, SteadyClock::now());
    profile_.chunk_count = static_cast<int>(chunks.size());
    // Accumulate VE timings across every chunk. Previously synth_chunk() reset this
    // vector for each chunk, so the profiler displayed only the final chunk's VE cost.
    profile_.ve_step_ms.assign(static_cast<size_t>(total_step_), 0.0);

    std::vector<float> full_pcm;
    constexpr size_t kCrossfadeSamples = 220; // 5 ms @ 44.1 kHz; do not trim phonemes at chunk edges.

    auto append_chunk = [&](const std::vector<float>& pcm) {
        if (pcm.empty()) return;
        if (!Graph::all_finite(pcm.data(), pcm.size())) {
            throw std::runtime_error("Supertonic: refusing non-finite chunk PCM after accelerator fallback");
        }
        const auto append_start = SteadyClock::now();
        const auto trimmed = trim_edge_silence(pcm, trailing_silence_trim_ms_);
        const std::vector<float>& source = trimmed.empty() ? pcm : trimmed;
        if (full_pcm.empty()) {
            full_pcm.insert(full_pcm.end(), source.begin(), source.end());
            profile_.append_ms += elapsed_ms(append_start, SteadyClock::now());
            return;
        }
        const size_t n = std::min({kCrossfadeSamples, full_pcm.size(), source.size()});
        const size_t old_start = full_pcm.size() - n;
        for (size_t i = 0; i < n; ++i) {
            const float t = static_cast<float>(i + 1) / static_cast<float>(n);
            full_pcm[old_start + i] = full_pcm[old_start + i] * (1.0f - t) + source[i] * t;
        }
        if (source.size() > n) full_pcm.insert(full_pcm.end(), source.begin() + static_cast<std::ptrdiff_t>(n), source.end());
        profile_.append_ms += elapsed_ms(append_start, SteadyClock::now());
    };

    // Streaming keeps a 5 ms tail pending so the next chunk can crossfade into it.
    // The first playable bytes are delivered immediately after the first chunk is
    // synthesized, while later chunks are generated only after audioAvailable()
    // returns; this gives Android clients a one-chunk look-ahead opportunity.
    std::vector<float> pending_stream_tail;
    bool stream_started = false;

    struct PregenResult {
        std::vector<float> pcm;
        bool truncated = false;
    };

    const int effective_pregen_depth = std::max(2, std::min(3, pre_generation_queue_));
    if (backend_ == Backend::Cpu && pre_generation_ && on_chunk && chunks.size() > 1) {
        if (static_cast<int>(pregen_engines_.size()) != effective_pregen_depth) {
            for (auto& engine : pregen_engines_) if (engine) engine->cancel();
            pregen_engines_.clear();
            pregen_engines_.reserve(static_cast<size_t>(effective_pregen_depth));
            for (int i = 0; i < effective_pregen_depth; ++i) {
                pregen_engines_.push_back(std::make_unique<LiteRTSupertonicTts>(
                    duration_path_, text_encoder_path_, vector_estimator_path_, vocoder_path_,
                    tokenizer_dir_, voice_styles_dir_, false, num_threads_, backend_,
                    native_library_dir_, accelerator_cache_dir_, std::shared_ptr<SupertonicExternalRunner>{}));
            }
        }
        for (auto& engine : pregen_engines_) {
            engine->set_voice(voice_id_);
            engine->set_total_step(total_step_);
            engine->set_speed(1.0f);
            engine->set_chunk_cap(dur_cap);
            engine->seed_used_ = seed_used_;
            engine->cancelled_.store(false);
        }
        profile_.pregen_queue_depth = effective_pregen_depth;
    } else {
        profile_.pregen_queue_depth = 0;
    }
    auto last_audio_emit = SteadyClock::time_point{};
    auto emit_stream = [&](const std::vector<float>& pcm, bool final_chunk) {
        if (!on_chunk) return;
        const auto stream_start = SteadyClock::now();
        auto emit = [&](const std::vector<float>& data) {
            if (data.empty()) return;
            if (!stream_started) {
                stream_started = true;
                profile_.ttfa_ms = elapsed_ms(total_start, SteadyClock::now());
            }
            if (last_audio_emit != SteadyClock::time_point{}) {
                auto now = SteadyClock::now();
                const double current_gap_ms = elapsed_ms(last_audio_emit, now);
                if (current_gap_ms < static_cast<double>(chunk_gap_min_ms_)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(chunk_gap_min_ms_) - std::chrono::milliseconds(static_cast<int>(current_gap_ms)));
                }
                const double gap_ms = elapsed_ms(last_audio_emit, SteadyClock::now());
                profile_.max_chunk_gap_ms = std::max(profile_.max_chunk_gap_ms, gap_ms);
                profile_.avg_chunk_gap_ms =
                    ((profile_.avg_chunk_gap_ms * profile_.chunk_gap_count) + gap_ms) /
                    static_cast<double>(profile_.chunk_gap_count + 1);
                ++profile_.chunk_gap_count;
                if (gap_ms > static_cast<double>(chunk_gap_max_ms_)) ++profile_.chunk_gap_over_max_count;
            }
            ++profile_.streamed_chunks;
            on_chunk(data.data(), data.size(), false);
            last_audio_emit = SteadyClock::now();
        };

        if (!pcm.empty()) {
            const auto trimmed = trim_edge_silence(pcm, trailing_silence_trim_ms_);
            const std::vector<float>& source = trimmed.empty() ? pcm : trimmed;
            if (pending_stream_tail.empty()) {
                if (source.size() <= kCrossfadeSamples) {
                    pending_stream_tail = source;
                } else {
                    const size_t cut = source.size() - kCrossfadeSamples;
                    std::vector<float> head(source.begin(), source.begin() + static_cast<std::ptrdiff_t>(cut));
                    emit(head);
                    pending_stream_tail.assign(source.begin() + static_cast<std::ptrdiff_t>(cut), source.end());
                }
            } else {
                const size_t n = std::min({kCrossfadeSamples, pending_stream_tail.size(), source.size()});
                std::vector<float> seam(n);
                for (size_t i = 0; i < n; ++i) {
                    const float t = static_cast<float>(i + 1) / static_cast<float>(n);
                    seam[i] = pending_stream_tail[pending_stream_tail.size() - n + i] * (1.0f - t) + source[i] * t;
                }
                emit(seam);
                if (source.size() <= kCrossfadeSamples) {
                    pending_stream_tail = source;
                } else {
                    const size_t keep = std::min(kCrossfadeSamples, source.size());
                    if (source.size() > n + keep) {
                        std::vector<float> middle(source.begin() + static_cast<std::ptrdiff_t>(n),
                                                   source.end() - static_cast<std::ptrdiff_t>(keep));
                        emit(middle);
                    }
                    pending_stream_tail.assign(source.end() - static_cast<std::ptrdiff_t>(keep), source.end());
                }
            }
        }
        if (final_chunk) {
            emit(pending_stream_tail);
            pending_stream_tail.clear();
            on_chunk(nullptr, 0, true);
        }
        profile_.stream_emit_ms += elapsed_ms(stream_start, SteadyClock::now());
    };

    // A duration prediction can still exceed the fixed 64-frame vocoder window even
    // when the text fits the 128-codepoint input tensor. In that case synth_chunk()
    // necessarily truncates the tail. Detect that case and recursively split the text
    // before accepting any audio. This is the important anti-"sentence swallowed"
    // safeguard for long Korean/Chinese sentences.
    std::function<void(const std::string&, size_t)> synth_safe;
    synth_safe = [&](const std::string& chunk, size_t seed_index) {
        if (cancelled_.load() || chunk.empty()) return;
        const int before = profile_.truncated_chunks;
        auto pcm = synth_chunk(chunk, language, seed_index);
        const bool truncated = profile_.truncated_chunks > before;
        if (truncated) {
            const auto cps = utf8_to_u32(chunk);
            if (cps.size() > 8) {
                const size_t split = choose_semantic_split(cps);
                if (split > 0 && split < cps.size()) {
                    std::vector<char32_t> left(cps.begin(), cps.begin() + static_cast<std::ptrdiff_t>(split));
                    std::vector<char32_t> right(cps.begin() + static_cast<std::ptrdiff_t>(split), cps.end());

                    // A whitespace boundary is not speech content; remove it from
                    // both children. This is what prevents cases such as
                    // "황금 / 기 시절" after a duration-overflow retry.
                    auto trim_ws = [](std::vector<char32_t>& v) {
                        while (!v.empty() &&
                               (v.front() == U' ' || v.front() == U'\t' ||
                                v.front() == U'\n' || v.front() == U'\r')) {
                            v.erase(v.begin());
                        }
                        while (!v.empty() &&
                               (v.back() == U' ' || v.back() == U'\t' ||
                                v.back() == U'\n' || v.back() == U'\r')) {
                            v.pop_back();
                        }
                    };
                    trim_ws(left);
                    trim_ws(right);

                    auto to_text = [](const std::vector<char32_t>& v) { return u32_to_utf8(v); };
                    profile_.truncated_chunks = before;
                    if (!left.empty()) synth_safe(to_text(left), seed_index * 2 + 1);
                    if (!right.empty()) synth_safe(to_text(right), seed_index * 2 + 2);
                    return;
                }
            }
        }
        if (!pcm.empty()) {
            append_chunk(pcm);
            emit_stream(pcm, false);
        }
    };

    struct PregenTask {
        size_t chunk_index = 0;
        size_t engine_index = 0;
        std::future<PregenResult> future;
    };
    std::deque<PregenTask> pending;
    size_t next_chunk_to_launch = 1;
    auto launch_one = [&](size_t index) {
        if (!pre_generation_ || pregen_engines_.empty() || index >= chunks.size() || pending.size() >= pregen_engines_.size()) return;
        const size_t engine_index = (index - 1) % pregen_engines_.size();
        auto& engine = pregen_engines_[engine_index];
        const std::string chunk_text = chunks[index];
        const std::string chunk_lang = language;
        pending.push_back(PregenTask{index, engine_index, std::async(std::launch::async, [engine = engine.get(), chunk_text, chunk_lang, index]() {
            engine->cancelled_.store(false);
            const int before = engine->profile_.truncated_chunks;
            auto pcm = engine->synth_chunk(chunk_text, chunk_lang, index);
            return PregenResult{std::move(pcm), engine->profile_.truncated_chunks > before};
        })});
    };

    if (backend_ == Backend::Cpu && pre_generation_ && on_chunk && chunks.size() > 1 && !pregen_engines_.empty()) {
        while (pending.size() < pregen_engines_.size() && next_chunk_to_launch < chunks.size()) {
            launch_one(next_chunk_to_launch++);
        }
    }

    for (size_t ci = 0; ci < chunks.size(); ++ci) {
        if (cancelled_.load()) return;

        if (ci == 0 || !pre_generation_ || pregen_engines_.empty() || !on_chunk) {
            synth_safe(chunks[ci], ci);
        } else {
            PregenTask task = std::move(pending.front());
            pending.pop_front();
            PregenResult ready = task.future.get();
            if (next_chunk_to_launch < chunks.size()) launch_one(next_chunk_to_launch++);
            if (!ready.truncated && !ready.pcm.empty()) {
                ++profile_.pregen_used_chunks;
                append_chunk(ready.pcm);
                emit_stream(ready.pcm, false);
            } else {
                synth_safe(chunks[ci], ci);
            }
        }
    }
    // Signal that the last playable PCM has already been emitted before cleaning up
    // speculative pre-generation work. Android TTS can therefore advance its own
    // utterance/chapter state while native pre-generation workers perform their
    // cooperative cancellation and teardown in the background of this call.
    // The JNI layer records this final marker and sends callback.done() immediately.
    if (on_chunk) emit_stream({}, true);

    // Never leave speculative work running against an engine that the next utterance
    // may reuse. Cancel first, then join only the already-started workers. Because the
    // final marker above is sent before this wait, UI/navigation code is no longer
    // forced to wait for speculative cleanup before it can start the next chapter.
    const auto pregen_cleanup_start = SteadyClock::now();
    for (auto& task : pending) {
        if (task.engine_index < pregen_engines_.size() && pregen_engines_[task.engine_index]) {
            pregen_engines_[task.engine_index]->cancel();
        }
    }
    for (auto& task : pending) task.future.wait();
    pending.clear();
    profile_.pregen_cleanup_ms += elapsed_ms(pregen_cleanup_start, SteadyClock::now());

    const auto final_postprocess_start = SteadyClock::now();
    if (!full_pcm.empty()) {
        if (!Graph::all_finite(full_pcm.data(), full_pcm.size())) {
            throw std::runtime_error("Supertonic: non-finite PCM detected before final postprocess; audio was not emitted");
        }
        double sum_sq = 0.0;
        float peak = 0.0f;
        for (float x : full_pcm) {
            peak = std::max(peak, std::fabs(x));
            sum_sq += static_cast<double>(x) * static_cast<double>(x);
        }
        // One final conservative global normalization keeps the overall loudness stable.
        const double rms = std::sqrt(sum_sq / static_cast<double>(full_pcm.size()));
        if (rms > 1.0e-5) {
            float gain = static_cast<float>(0.060 / rms);
            gain = std::max(0.90f, std::min(1.10f, gain));
            if (peak > 1.0e-5f) gain = std::min(gain, 0.94f / peak);
            for (float& x : full_pcm) x = std::max(-1.0f, std::min(1.0f, x * gain));
        }

        profile_.peak = 0.0;
        double post_sq = 0.0;
        for (float x : full_pcm) {
            profile_.peak = std::max(profile_.peak, static_cast<double>(std::fabs(x)));
            post_sq += static_cast<double>(x) * static_cast<double>(x);
        }
        profile_.rms = std::sqrt(post_sq / static_cast<double>(full_pcm.size()));
        // Apply the user-selected maximum final trailing-silence trim after all chunk
        // joins and normalization. This controls the actual end-of-output tail.
        if (trailing_silence_trim_ms_ > 0 && full_pcm.size() >= 4096) {
            const float peak_now = static_cast<float>(profile_.peak);
            const float threshold = std::max(5.0e-4f, peak_now * 0.003f);
            const size_t min_run = static_cast<size_t>(0.035 * kSampleRateConst);
            const size_t max_trim = static_cast<size_t>(trailing_silence_trim_ms_) * kSampleRateConst / 1000;
            size_t low = 0;
            for (size_t i = full_pcm.size(); i-- > 0 && (full_pcm.size() - i) <= max_trim + min_run;) {
                if (std::fabs(full_pcm[i]) < threshold) ++low;
                else break;
            }
            if (low >= min_run) {
                const size_t remove = std::min(low, max_trim);
                if (remove > 0 && remove < full_pcm.size() - 2048) full_pcm.resize(full_pcm.size() - remove);
            }
        }
        // Recompute RMS after the optional final tail trim so the profile matches
        // the exact PCM that will be returned to the caller.
        double final_sq = 0.0;
        profile_.peak = 0.0;
        for (float x : full_pcm) {
            profile_.peak = std::max(profile_.peak, static_cast<double>(std::fabs(x)));
            final_sq += static_cast<double>(x) * static_cast<double>(x);
        }
        profile_.rms = std::sqrt(final_sq / static_cast<double>(full_pcm.size()));
        const auto final_active = active_range(full_pcm, 0, full_pcm.size());
        profile_.leading_silence_ms = (static_cast<double>(final_active.first) / kSampleRate) * 1000.0;
        profile_.trailing_silence_ms = (static_cast<double>(full_pcm.size() - final_active.second) / kSampleRate) * 1000.0;
    }

    profile_.final_postprocess_ms += elapsed_ms(final_postprocess_start, SteadyClock::now());

    if (!full_pcm.empty()) {
        // Never destructively trim the final waveform. Low-energy leading/trailing
        // phonemes are legitimate speech, especially in Korean.
        last_pcm_ = full_pcm;
    }

    // Keep the backend report chosen during construction. Accelerator modes in
    // this build select graph stages from the Java runner's device capability
    // result. Unselected stages stay on native XNNPACK and the exact mapping is
    // retained in backend_report_.
    // Never relabel either path as the old CompiledModel STRICT experiment.
    profile_.total_ms = elapsed_ms(total_start, SteadyClock::now());
}

std::string LiteRTSupertonicTts::performance_profile() const {
    std::ostringstream oss;
    oss.setf(std::ios::fixed);
    oss.precision(3);
    oss << "dp=" << profile_.duration_predictor_ms
        << ";encoder=" << profile_.text_encoder_ms
        << ";vocoder=" << profile_.vocoder_ms
        << ";tensor_copy=" << profile_.tensor_copy_ms
        << ";chunking=" << profile_.chunking_ms
        << ";token_process=" << profile_.token_process_ms
        << ";latent_setup=" << profile_.latent_setup_ms
        << ";append=" << profile_.append_ms
        << ";stream_emit=" << profile_.stream_emit_ms
        << ";pregen_cleanup=" << profile_.pregen_cleanup_ms
        << ";final_postprocess=" << profile_.final_postprocess_ms
        << ";total=" << profile_.total_ms
        << ";voice=" << voice_id_
        << ";model_speed=1.0"
        << ";steps=" << total_step_
        << ";threads=" << num_threads_
        << ";chunk_silence_ms=" << (chunk_silence_s_ * 1000.0f)
        << ";chunks=" << profile_.chunk_count
        << ";truncated_chunks=" << profile_.truncated_chunks
        << ";peak=" << profile_.peak
        << ";rms=" << profile_.rms
        << ";lead_silence_ms=" << profile_.leading_silence_ms
        << ";trail_silence_ms=" << profile_.trailing_silence_ms
        << ";ttfa_ms=" << profile_.ttfa_ms
        << ";streamed_chunks=" << profile_.streamed_chunks
        << ";max_chunk_gap_ms=" << profile_.max_chunk_gap_ms
        << ";avg_chunk_gap_ms=" << profile_.avg_chunk_gap_ms
        << ";chunk_gap_min_ms=" << chunk_gap_min_ms_
        << ";chunk_gap_max_ms=" << chunk_gap_max_ms_
        << ";chunk_gap_over_max_count=" << profile_.chunk_gap_over_max_count
        << ";pregen=" << (pre_generation_ ? "on" : "off")
        << ";pregen_queue_depth=" << profile_.pregen_queue_depth
        << ";pregen_used_chunks=" << profile_.pregen_used_chunks
        << ";trailing_trim_setting_ms=" << trailing_silence_trim_ms_
        << ";chunk_cap=" << chunk_cap_
        << ";backend=" << backend_report_
        << ";ve_steps=";
    for (size_t i = 0; i < profile_.ve_step_ms.size(); ++i) {
        if (i) oss << ',';
        oss << profile_.ve_step_ms[i];
    }
    return oss.str();
}

std::vector<float> LiteRTSupertonicTts::synth_chunk(const std::string& chunk,
                                                     const std::string& language,
                                                     size_t chunk_index) {
    if (backend_ == Backend::Cpu && !interp_)
        throw std::runtime_error("Supertonic: CPU interpreter state is not initialized");
    if (backend_ != Backend::Cpu && !external_runner_)
        throw std::runtime_error("Supertonic: accelerator delegate runner is not initialized");
    const VoiceStyle& voice = current_voice();
    const auto token_start = SteadyClock::now();
    const auto tok = tokenizer_->process(chunk, language, kTextT);
    profile_.token_process_ms += elapsed_ms(token_start, SteadyClock::now());

    const std::vector<int64_t> ids64(tok.ids.begin(), tok.ids.end());
    float duration = 0.0f;
    {
        const auto t0 = SteadyClock::now();
        if (backend_ == Backend::Cpu || !external_runner_->supports_duration()) {
            interp_->duration.write_input_named("text_mask", {1, 1, kTextT}, tok.mask.data(), tok.mask.size() * sizeof(float));
            interp_->duration.write_input_named("text_ids", {1, kTextT}, ids64.data(), ids64.size() * sizeof(int64_t));
            interp_->duration.write_input_named("style_dp", {1, 8, 16}, voice.style_dp.data(), voice.style_dp.size() * sizeof(float));
            interp_->duration.run("duration Run");
            interp_->duration.read_output({1}, &duration, sizeof(float));
        } else {
            external_runner_->run_duration(ids64.data(), ids64.size(),
                                           voice.style_dp.data(), voice.style_dp.size(),
                                           tok.mask.data(), tok.mask.size(), &duration);
        }
        profile_.duration_predictor_ms += elapsed_ms(t0, SteadyClock::now());
    }
    if (!(duration > 0.0f) || !std::isfinite(duration)) {
        throw std::runtime_error("Supertonic: duration predictor returned invalid/non-finite output");
    }

    const double max_graph_duration_s = static_cast<double>(graph_latent_frames()) * kChunkSamples / kSampleRateConst;
    if (duration > max_graph_duration_s * 1.001) ++profile_.truncated_chunks;

    std::vector<float> text_emb(static_cast<size_t>(256) * kTextT);
    {
        const auto t0 = SteadyClock::now();
        if (backend_ == Backend::Cpu || !external_runner_->supports_encoder()) {
            interp_->encoder.write_input_named("text_mask", {1, 1, kTextT}, tok.mask.data(), tok.mask.size() * sizeof(float));
            interp_->encoder.write_input_named("text_ids", {1, kTextT}, ids64.data(), ids64.size() * sizeof(int64_t));
            interp_->encoder.write_input_named("style_ttl", {1, 50, 256}, voice.style_ttl.data(), voice.style_ttl.size() * sizeof(float));
            interp_->encoder.run("text_encoder Run");
            interp_->encoder.read_output({1, 256, kTextT}, text_emb.data(), text_emb.size() * sizeof(float));
        } else {
            external_runner_->run_encoder(ids64.data(), ids64.size(),
                                          voice.style_ttl.data(), voice.style_ttl.size(),
                                          tok.mask.data(), tok.mask.size(),
                                          text_emb.data(), text_emb.size());
        }
        if (!Graph::all_finite(text_emb.data(), text_emb.size()))
            throw std::runtime_error("Supertonic: text encoder returned non-finite output");
        profile_.text_encoder_ms += elapsed_ms(t0, SteadyClock::now());
    }

    const int chunk_size = kChunkSamples;
    const long long wav_len = static_cast<long long>(duration * kSampleRateConst);
    const int l_true = std::max(1, static_cast<int>((wav_len + chunk_size - 1) / chunk_size));
    const int L = graph_latent_frames();
    const int L_fill = std::min(l_true, L);
    std::vector<float> latent_mask(static_cast<size_t>(L), 0.0f);
    for (int t = 0; t < L_fill; ++t) latent_mask[t] = 1.0f;

    const auto latent_start = SteadyClock::now();
    std::mt19937 rng(seed_used_ + 0x9E3779B9u * static_cast<uint32_t>(chunk_index + 1));
    std::normal_distribution<float> nd(0.0f, 1.0f);
    std::vector<float> xt(static_cast<size_t>(kLatentChannels) * L);
    for (int c = 0; c < kLatentChannels; ++c)
        for (int t = 0; t < L; ++t) xt[static_cast<size_t>(c) * L + t] = nd(rng) * latent_mask[t];
    profile_.latent_setup_ms += elapsed_ms(latent_start, SteadyClock::now());

    const float total_step_f = static_cast<float>(total_step_);
    if (profile_.ve_step_ms.size() != static_cast<size_t>(total_step_))
        profile_.ve_step_ms.assign(static_cast<size_t>(total_step_), 0.0);
    for (int step = 0; step < total_step_; ++step) {
        if (cancelled_.load()) return {};
        const auto t0 = SteadyClock::now();
        const float cur_step_f = static_cast<float>(step);
        if (backend_ == Backend::Cpu || !external_runner_->supports_vector()) {
            interp_->vector.write_input_named("current_step", {1}, &cur_step_f, sizeof(float), 0);
            interp_->vector.write_input_named("style_ttl", {1, 50, 256}, voice.style_ttl.data(), voice.style_ttl.size() * sizeof(float));
            interp_->vector.write_input_named("latent_mask", {1, 1, L}, latent_mask.data(), latent_mask.size() * sizeof(float));
            interp_->vector.write_input_named("noisy_latent", {1, kLatentChannels, L}, xt.data(), xt.size() * sizeof(float));
            interp_->vector.write_input_named("total_step", {1}, &total_step_f, sizeof(float), 1);
            interp_->vector.write_input_named("text_mask", {1, 1, kTextT}, tok.mask.data(), tok.mask.size() * sizeof(float));
            interp_->vector.write_input_named("text_emb", {1, 256, kTextT}, text_emb.data(), text_emb.size() * sizeof(float));
            interp_->vector.run("vector_estimator Run");
            interp_->vector.read_output({1, kLatentChannels, L}, xt.data(), xt.size() * sizeof(float));
        } else {
            external_runner_->run_vector(xt.data(), xt.size(),
                                         text_emb.data(), text_emb.size(),
                                         voice.style_ttl.data(), voice.style_ttl.size(),
                                         latent_mask.data(), latent_mask.size(),
                                         tok.mask.data(), tok.mask.size(),
                                         cur_step_f, total_step_f);
        }
        if (!Graph::all_finite(xt.data(), xt.size()))
            throw std::runtime_error("Supertonic: vector estimator returned non-finite output at step " + std::to_string(step + 1));
        profile_.ve_step_ms[static_cast<size_t>(step)] += elapsed_ms(t0, SteadyClock::now());
    }

    std::vector<float> wav(static_cast<size_t>(chunk_size) * L);
    {
        const auto t0 = SteadyClock::now();
        if (backend_ == Backend::Cpu || !external_runner_->supports_vocoder()) {
            interp_->vocoder.write_input_named("latent", {1, kLatentChannels, L}, xt.data(), xt.size() * sizeof(float));
            interp_->vocoder.run("vocoder Run");
            interp_->vocoder.read_output({1, chunk_size * L}, wav.data(), wav.size() * sizeof(float));
        } else {
            external_runner_->run_vocoder(xt.data(), xt.size(), wav.data(), wav.size());
        }
        if (!Graph::all_finite(wav.data(), wav.size()))
            throw std::runtime_error("Supertonic: vocoder returned non-finite output");
        profile_.vocoder_ms += elapsed_ms(t0, SteadyClock::now());
    }

    size_t n = static_cast<size_t>(std::floor(kSampleRateConst * duration));
    n = std::min(n, static_cast<size_t>(chunk_size) * static_cast<size_t>(L_fill));
    n = std::min(n, wav.size());

    // Some vendor delegates return finite tensors that are nevertheless
    // numerically wrong. The observed Helio G99 NNAPI result passed every
    // all-finite check but produced only wind-like near-silence (peak 0.029,
    // RMS 0.002). Reject only accelerator output and use deliberately
    // conservative two-metric bounds so valid quiet CPU speech is untouched.
    if (backend_ != Backend::Cpu && n >= 4096) {
        double peak = 0.0;
        double sum_sq = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double sample = static_cast<double>(wav[i]);
            peak = std::max(peak, std::fabs(sample));
            sum_sq += sample * sample;
        }
        const double rms = std::sqrt(sum_sq / static_cast<double>(n));
        const bool implausibly_quiet = peak < 0.040 && rms < 0.0040;
        const bool implausibly_large = peak > 4.0 || rms > 1.0;
        if (implausibly_quiet || implausibly_large) {
            std::ostringstream reason;
            reason.setf(std::ios::fixed);
            reason << std::setprecision(6)
                   << "Supertonic: accelerator audio quality guard rejected output"
                   << " (peak=" << peak << ", rms=" << rms << ')';
            throw std::runtime_error(reason.str());
        }
    }
    wav.resize(n);
    return wav;
}

} // namespace speech_core
