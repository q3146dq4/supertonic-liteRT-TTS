package audio.soniqo.speech

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Build
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Android-owned accelerator runner for Supertonic's four LiteRT graphs.
 *
 * Qualcomm HTP/NPU remains available as an explicitly experimental path.
 * Encoder and VE produced non-finite HTP/FP16 output, while a QNN DSP VE probe
 * terminated the process. Those unsafe stages stay blocked; only the HTP
 * vocoder probe is retained and the SDK retries the request on CPU if its
 * numerical/audio-quality guard rejects the result.
 *
 * GPU: Snapdragon uses QNN GPU/hybrid precision for compatible heavy graphs. Other devices
 * keep the incompatible VE on CPU and probe vocoder with the classic LiteRT
 * GpuDelegate. NNAPI is a separate experimental vendor-driver path for
 * MediaTek/Samsung/Tensor devices and probes VE/Vocoder. Delegate creation and
 * invokes stay on one HandlerThread.
 */
class DelegateSupertonicRunner(
    private val config: SpeechSynthesizerConfig,
) : AutoCloseable {
    companion object {
        private const val TAG = "SupertonicAccel"

        private fun isQualcommDevice(): Boolean {
            val hints = listOf(Build.HARDWARE, Build.BOARD, Build.DEVICE)
            return hints.any { value ->
                val s = value.orEmpty().trim()
                s.contains("qcom", ignoreCase = true) ||
                    s.contains("qualcomm", ignoreCase = true) ||
                    s.contains("snapdragon", ignoreCase = true)
            }
        }
    }

    private val thread = HandlerThread("Supertonic-Accelerator").apply { start() }
    private val handler = Handler(thread.looper)
    private val graphs = AtomicReference<GraphSet?>()
    private val report = AtomicReference("Accelerator not initialized")

    private enum class Kind { LITERT_GPU, QNN_GPU, QNN_NPU, NNAPI }

    private class GraphRunner(
        modelFile: File,
        private val graphName: String,
        kind: Kind,
        nativeLibraryDir: String,
        cacheDir: String,
    ) : AutoCloseable {
        private val delegate: Delegate
        private val interpreter: Interpreter
        private val signatureKey: String
        private val signatureInputs: Set<String>
        private val signatureOutput: String

        init {
            require(modelFile.isFile) { "Supertonic[$graphName]: model missing: ${modelFile.absolutePath}" }
            val options = Interpreter.Options().apply {
                // A hidden XNNPACK fallback would make accelerator benchmarks
                // look better than they really are. Keep fallback kernels plain.
                setUseXNNPACK(false)
                setNumThreads(1)
            }

            val madeDelegate: Delegate = when (kind) {
                Kind.QNN_NPU, Kind.QNN_GPU -> {
                    require(nativeLibraryDir.isNotBlank()) {
                        "Qualcomm QNN requires applicationInfo.nativeLibraryDir"
                    }
                    val qnnOptions = QnnDelegate.Options().apply {
                        if (kind == Kind.QNN_NPU) {
                            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                            setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                            setHtpPerformanceMode(
                                QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE,
                            )
                            setSkelLibraryDir(nativeLibraryDir)
                        } else {
                            // QNN GPU FP32 was numerically correct but slower
                            // than XNNPACK on the measured Snapdragon 8 Elite
                            // Gen 5 device. Hybrid precision is the speed profile.
                            setBackendType(QnnDelegate.Options.BackendType.GPU_BACKEND)
                            setGpuPrecision(QnnDelegate.Options.GpuPrecision.GPU_PRECISION_HYBRID)
                            setGpuPerformanceMode(QnnDelegate.Options.GpuPerformanceMode.GPU_PERFORMANCE_HIGH)
                        }
                        setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_INFO)
                        setProfiling(QnnDelegate.Options.ProfilingOptions.BASIC_PROFILING)
                        if (cacheDir.isNotBlank()) {
                            setCacheDir(cacheDir)
                            setModelToken("supertonic_v01_${graphName}_${kind.name.lowercase()}")
                        }
                    }
                    QnnDelegate(qnnOptions)
                }
                Kind.LITERT_GPU -> {
                    val gpuOptions = GpuDelegateFactory.Options().apply {
                        setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                        // This path is used only for the vocoder on Mali-class
                        // devices. FP16 storage is required for a realistic
                        // speed win; native code still blocks non-finite audio.
                        setPrecisionLossAllowed(true)
                        setQuantizedModelsAllowed(true)
                        if (cacheDir.isNotBlank()) {
                            setSerializationParams(cacheDir, "supertonic_litert_v01_${graphName}_gpu_delegate")
                        }
                    }
                    GpuDelegate(gpuOptions)
                }
                Kind.NNAPI -> {
                    val nnapiOptions = NnApiDelegate.Options().apply {
                        setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        // MediaTek/Samsung/Tensor accelerators generally need
                        // relaxed FP16 to leave the CPU. Output checks remain
                        // active after every VE step and after the vocoder.
                        setAllowFp16(true)
                        setUseNnapiCpu(false)
                        if (cacheDir.isNotBlank()) {
                            setCacheDir(cacheDir)
                            setModelToken("supertonic_v01_${graphName}_nnapi")
                        }
                    }
                    NnApiDelegate(nnapiOptions)
                }
            }
            var madeInterpreter: Interpreter? = null
            try {
                options.addDelegate(madeDelegate)
                val ready = Interpreter(modelFile, options)
                madeInterpreter = ready
                val keys = ready.signatureKeys
                require(keys.isNotEmpty()) { "Supertonic[$graphName]: model has no LiteRT signature" }
                val key = keys[0]
                val inputs = ready.getSignatureInputs(key).toSet()
                val outputs = ready.getSignatureOutputs(key)
                require(outputs.isNotEmpty()) { "Supertonic[$graphName]: signature has no output" }

                delegate = madeDelegate
                interpreter = ready
                signatureKey = key
                signatureInputs = inputs
                signatureOutput = outputs[0]
            } catch (t: Throwable) {
                runCatching { madeInterpreter?.close() }
                when (madeDelegate) {
                    is QnnDelegate -> runCatching { madeDelegate.close() }
                    is GpuDelegate -> runCatching { madeDelegate.close() }
                    is NnApiDelegate -> runCatching { madeDelegate.close() }
                }
                throw t
            }
        }

        private fun inputName(semantic: String, argIndex: Int): String {
            if (semantic in signatureInputs) return semantic
            val generic = "args_$argIndex"
            if (generic in signatureInputs) return generic
            throw IllegalStateException(
                "Supertonic[$graphName]: input '$semantic' missing; signature names=${signatureInputs.joinToString(",")}",
            )
        }

        private fun prep(buffer: ByteBuffer): ByteBuffer = buffer.apply {
            order(ByteOrder.nativeOrder())
            position(0)
        }

        fun duration(ids: ByteBuffer, styleDp: ByteBuffer, mask: ByteBuffer, out: ByteBuffer) {
            interpreter.runSignature(
                mapOf(
                    inputName("text_ids", 0) to prep(ids),
                    inputName("style_dp", 1) to prep(styleDp),
                    inputName("text_mask", 2) to prep(mask),
                ),
                mapOf(signatureOutput to prep(out)),
                signatureKey,
            )
        }

        fun encoder(ids: ByteBuffer, styleTtl: ByteBuffer, mask: ByteBuffer, out: ByteBuffer) {
            interpreter.runSignature(
                mapOf(
                    inputName("text_ids", 0) to prep(ids),
                    inputName("style_ttl", 1) to prep(styleTtl),
                    inputName("text_mask", 2) to prep(mask),
                ),
                mapOf(signatureOutput to prep(out)),
                signatureKey,
            )
        }

        fun vector(
            noisyLatent: ByteBuffer,
            textEmb: ByteBuffer,
            styleTtl: ByteBuffer,
            latentMask: ByteBuffer,
            textMask: ByteBuffer,
            currentStep: ByteBuffer,
            totalStep: ByteBuffer,
            out: ByteBuffer,
        ) {
            interpreter.runSignature(
                mapOf(
                    inputName("noisy_latent", 0) to prep(noisyLatent),
                    inputName("text_emb", 1) to prep(textEmb),
                    inputName("style_ttl", 2) to prep(styleTtl),
                    inputName("latent_mask", 3) to prep(latentMask),
                    inputName("text_mask", 4) to prep(textMask),
                    inputName("current_step", 5) to prep(currentStep),
                    inputName("total_step", 6) to prep(totalStep),
                ),
                mapOf(signatureOutput to prep(out)),
                signatureKey,
            )
        }

        fun vocoder(latent: ByteBuffer, out: ByteBuffer) {
            interpreter.runSignature(
                mapOf(inputName("latent", 0) to prep(latent)),
                mapOf(signatureOutput to prep(out)),
                signatureKey,
            )
        }

        override fun close() {
            interpreter.close()
            when (delegate) {
                is QnnDelegate -> delegate.close()
                is GpuDelegate -> delegate.close()
                is NnApiDelegate -> delegate.close()
            }
        }
    }

    private data class GraphSet(
        val duration: GraphRunner?,
        val encoder: GraphRunner?,
        val vector: GraphRunner?,
        val vocoder: GraphRunner?,
    ) : AutoCloseable {
        override fun close() {
            vocoder?.let { runCatching { it.close() } }
            vector?.let { runCatching { it.close() } }
            encoder?.let { runCatching { it.close() } }
            duration?.let { runCatching { it.close() } }
        }
    }

    init {
        require(config.backend != InferenceBackend.CPU_XNNPACK)
        callOnRunner {
            val kind = when (config.backend) {
                InferenceBackend.QUALCOMM_NPU -> Kind.QNN_NPU
                InferenceBackend.GPU_LITERT -> if (isQualcommDevice()) Kind.QNN_GPU else Kind.LITERT_GPU
                InferenceBackend.NNAPI_DEVICE -> Kind.NNAPI
                InferenceBackend.CPU_XNNPACK -> error("CPU backend must not create an accelerator runner")
            }
            val made = ArrayList<GraphRunner>(4)
            val failures = ArrayList<String>()
            val stageKinds = LinkedHashMap<String, Kind>()
            try {
                fun graph(name: String, file: String, graphKind: Kind): GraphRunner = GraphRunner(
                    File(config.modelDir, file), name, graphKind,
                    config.nativeLibraryDir, config.acceleratorCacheDir,
                ).also { made += it }

                fun tryGraph(name: String, file: String, graphKind: Kind = kind): GraphRunner? = try {
                    graph(name, file, graphKind).also {
                        stageKinds[name] = graphKind
                        Log.i(TAG, "$graphKind initialized $name")
                    }
                } catch (t: Throwable) {
                    val reason = t.message ?: t.javaClass.simpleName
                    failures += "$graphKind/$name: $reason"
                    Log.w(TAG, "$graphKind rejected $name: $reason")
                    null
                }

                // Device logs established these model-specific constraints:
                // 1) HTP FP16 makes text_encoder emit NaN/Inf.
                // 2) HTP FP16 makes vector_estimator emit NaN/Inf at step 1.
                // 3) HTP FP16 makes vocoder emit NaN/Inf.
                // 4) Classic LiteRT GPU cannot prepare vector_estimator because
                //    its 3D CONCAT and GATHER_ND/BROADCAST_TO path is unsupported.
                // A QNN DSP VE probe also caused a process-level device crash.
                // Keep the process-crashing DSP VE path blocked. The HTP
                // vocoder probe is retained for continued NPU development; its
                // known invalid output is caught by the numerical guard and the
                // SDK then recreates the request on CPU.
                val set = when (kind) {
                    Kind.QNN_NPU -> GraphSet(
                        null, null, null,
                        tryGraph("vocoder", "vocoder.tflite"),
                    )
                    Kind.QNN_GPU -> GraphSet(
                        null, null,
                        tryGraph("vector_estimator", "vector_estimator.tflite"),
                        tryGraph("vocoder", "vocoder.tflite")
                            ?: tryGraph("vocoder", "vocoder.tflite", Kind.LITERT_GPU),
                    )
                    Kind.LITERT_GPU -> GraphSet(
                        null, null, null,
                        tryGraph("vocoder", "vocoder.tflite"),
                    )
                    Kind.NNAPI -> GraphSet(
                        null, null,
                        tryGraph("vector_estimator", "vector_estimator.tflite"),
                        tryGraph("vocoder", "vocoder.tflite"),
                    )
                }
                if (set.vector == null && set.vocoder == null) {
                    throw IllegalStateException(
                        "No Supertonic graph is compatible with $kind" +
                            failures.joinToString(prefix = ": ", separator = " | "),
                    )
                }
                graphs.set(set)

                val accelerated = buildList {
                    if (set.duration != null) add("DP")
                    if (set.encoder != null) add("Enc")
                    if (set.vector != null) add("VE")
                    if (set.vocoder != null) add("Voc")
                }
                val cpu = listOf("DP", "Enc", "VE", "Voc").filterNot(accelerated::contains)
                val label = when (kind) {
                    Kind.QNN_NPU -> "Qualcomm QNN HTP/FP16 experimental (Voc only)"
                    Kind.QNN_GPU -> "GPU auto (QNN GPU/hybrid precision)"
                    Kind.LITERT_GPU -> "LiteRT GPU/FP16 vocoder"
                    Kind.NNAPI -> "NNAPI vendor accelerator/relaxed FP16"
                }
                val stageDetail = stageKinds.entries.joinToString(",") { (name, graphKind) ->
                    "${if (name == "vector_estimator") "VE" else "Voc"}=$graphKind"
                }
                report.set(
                    "$label requested; accelerated=${accelerated.joinToString("+")}; " +
                        "CPU/XNNPACK=${cpu.joinToString("+")}; stages=$stageDetail" +
                        if (failures.isEmpty()) "" else "; rejected=${failures.joinToString(" | ")}",
                )
                Log.i(TAG, report.get())
            } catch (t: Throwable) {
                made.asReversed().forEach { runCatching { it.close() } }
                throw t
            }
        }
    }

    fun hasDuration(): Boolean = graphs.get()?.duration != null
    fun hasEncoder(): Boolean = graphs.get()?.encoder != null
    fun hasVector(): Boolean = graphs.get()?.vector != null
    fun hasVocoder(): Boolean = graphs.get()?.vocoder != null
    fun backendReport(): String = report.get()

    private fun <T> callOnRunner(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val result = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        if (!handler.post {
                try { result.set(block()) } catch (t: Throwable) { error.set(t) } finally { latch.countDown() }
            }) {
            throw IllegalStateException("Supertonic accelerator thread is not accepting work")
        }
        latch.await()
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    fun runDuration(ids: ByteBuffer, styleDp: ByteBuffer, mask: ByteBuffer, out: ByteBuffer) =
        callOnRunner {
            (graphs.get()?.duration ?: error("Accelerator runner does not execute duration"))
                .duration(ids, styleDp, mask, out)
        }

    fun runEncoder(ids: ByteBuffer, styleTtl: ByteBuffer, mask: ByteBuffer, out: ByteBuffer) =
        callOnRunner {
            (graphs.get()?.encoder ?: error("Accelerator runner does not execute encoder"))
                .encoder(ids, styleTtl, mask, out)
        }

    fun runVector(
        noisyLatent: ByteBuffer,
        textEmb: ByteBuffer,
        styleTtl: ByteBuffer,
        latentMask: ByteBuffer,
        textMask: ByteBuffer,
        currentStep: ByteBuffer,
        totalStep: ByteBuffer,
        out: ByteBuffer,
    ) = callOnRunner {
        (graphs.get()?.vector ?: error("Accelerator runner does not execute vector_estimator")).vector(
            noisyLatent, textEmb, styleTtl, latentMask, textMask,
            currentStep, totalStep, out,
        )
    }

    fun runVocoder(latent: ByteBuffer, out: ByteBuffer) =
        callOnRunner {
            (graphs.get()?.vocoder ?: error("Accelerator runner does not execute vocoder")).vocoder(latent, out)
        }

    override fun close() {
        runCatching { callOnRunner { graphs.getAndSet(null)?.close() } }
        // All graph close work above is synchronous, so no queued accelerator
        // work remains and an immediate looper quit is safe here.
        thread.quit()
        if (Thread.currentThread() !== thread) runCatching { thread.join(3000) }
    }
}
