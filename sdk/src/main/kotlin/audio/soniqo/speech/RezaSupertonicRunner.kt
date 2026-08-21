package audio.soniqo.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reza2kn/supertonic-3-litert hybrid adapter.
 *
 * Native LiteRT/XNNPACK:
 *   INT4 duration_predictor
 *   INT4 text_encoder
 *   INT8 vocoder
 *
 * ONNX Runtime CPU:
 *   INT8 vector_estimator at native T_real/L_real
 *
 * A new RezaSupertonicRunner is created for each pre-generation worker so ORT
 * sessions are never concurrently driven by unrelated chunks through one object.
 */
internal class RezaSupertonicRunner(
    private val config: SpeechSynthesizerConfig,
) : SupertonicRunnerBridge {
    companion object {
        private const val FLOAT_BYTES = 4
        private const val LATENT_CHANNELS = 144
        private const val TEXT_EMBED_CHANNELS = 256
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(config.numThreads.coerceIn(1, 64))
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session = env.createSession(
        File(config.modelDir, "vector_estimator_int8.onnx").absolutePath,
        options,
    )
    private val outputName = session.outputNames.firstOrNull()
        ?: error("Reza vector_estimator has no output")

    private data class VectorCache(
        val lReal: Int,
        val tReal: Int,
        val inputs: Map<String, OnnxTensor>,
        val output: OnnxTensor,
    ) : AutoCloseable {
        override fun close() {
            inputs.values.forEach { runCatching { it.close() } }
            runCatching { output.close() }
        }
    }

    private var vectorCache: VectorCache? = null

    override fun hasDuration(): Boolean = false
    override fun hasEncoder(): Boolean = false
    override fun hasVector(): Boolean = true
    override fun hasVocoder(): Boolean = false

    override fun backendReport(): String =
        "Reza2kn Hybrid CPU: INT4 DP+Encoder / INT8 ORT VE / INT8 Vocoder"

    override fun cloneForPreGeneration(): SupertonicRunnerBridge =
        RezaSupertonicRunner(config)

    override fun runDuration(
        ids: ByteBuffer,
        styleDp: ByteBuffer,
        mask: ByteBuffer,
        out: ByteBuffer,
    ): Unit = error("Reza duration_predictor is executed by native LiteRT/XNNPACK")

    override fun runEncoder(
        ids: ByteBuffer,
        styleTtl: ByteBuffer,
        mask: ByteBuffer,
        out: ByteBuffer,
    ): Unit = error("Reza text_encoder is executed by native LiteRT/XNNPACK")

    override fun runVocoder(
        latent: ByteBuffer,
        out: ByteBuffer,
    ): Unit = error("Reza vocoder is executed by native LiteRT/XNNPACK")

    private fun floatBuffer(buffer: ByteBuffer) =
        buffer.duplicate()
            .order(ByteOrder.nativeOrder())
            .apply { position(0) }
            .asFloatBuffer()

    private fun scalar(buffer: ByteBuffer): Float =
        buffer.duplicate()
            .order(ByteOrder.nativeOrder())
            .apply { position(0) }
            .asFloatBuffer()
            .get(0)

    private fun resetVectorCache() {
        vectorCache?.close()
        vectorCache = null
    }

    private fun buildVectorCache(
        noisyLatent: ByteBuffer,
        textEmb: ByteBuffer,
        styleTtl: ByteBuffer,
        latentMask: ByteBuffer,
        textMask: ByteBuffer,
        currentStep: ByteBuffer,
        totalStep: ByteBuffer,
        out: ByteBuffer,
        lReal: Int,
        tReal: Int,
    ): VectorCache {
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors["noisy_latent"] = OnnxTensor.createTensor(
                env,
                floatBuffer(noisyLatent),
                longArrayOf(1, LATENT_CHANNELS.toLong(), lReal.toLong()),
            )
            tensors["text_emb"] = OnnxTensor.createTensor(
                env,
                floatBuffer(textEmb),
                longArrayOf(1, TEXT_EMBED_CHANNELS.toLong(), tReal.toLong()),
            )
            tensors["style_ttl"] = OnnxTensor.createTensor(
                env,
                floatBuffer(styleTtl),
                longArrayOf(1, 50, 256),
            )
            tensors["latent_mask"] = OnnxTensor.createTensor(
                env,
                floatBuffer(latentMask),
                longArrayOf(1, 1, lReal.toLong()),
            )
            tensors["text_mask"] = OnnxTensor.createTensor(
                env,
                floatBuffer(textMask),
                longArrayOf(1, 1, tReal.toLong()),
            )
            // JniSupertonicRunner keeps these scalar backing addresses stable
            // across every ODE step, so wrappers can be cached too.
            tensors["current_step"] = OnnxTensor.createTensor(
                env,
                floatBuffer(currentStep),
                longArrayOf(1),
            )
            tensors["total_step"] = OnnxTensor.createTensor(
                env,
                floatBuffer(totalStep),
                longArrayOf(1),
            )
            val outputTensor = OnnxTensor.createTensor(
                env,
                floatBuffer(out),
                longArrayOf(1, LATENT_CHANNELS.toLong(), lReal.toLong()),
            )
            return VectorCache(
                lReal = lReal,
                tReal = tReal,
                inputs = tensors,
                output = outputTensor,
            )
        } catch (t: Throwable) {
            tensors.values.forEach { runCatching { it.close() } }
            throw t
        }
    }

    override fun runVector(
        noisyLatent: ByteBuffer,
        textEmb: ByteBuffer,
        styleTtl: ByteBuffer,
        latentMask: ByteBuffer,
        textMask: ByteBuffer,
        currentStep: ByteBuffer,
        totalStep: ByteBuffer,
        out: ByteBuffer,
    ) {
        val noisyFloats = noisyLatent.capacity() / FLOAT_BYTES
        require(noisyFloats > 0 && noisyFloats % LATENT_CHANNELS == 0) {
            "Reza VE noisy_latent size is invalid: $noisyFloats floats"
        }
        val lReal = noisyFloats / LATENT_CHANNELS

        val textFloats = textEmb.capacity() / FLOAT_BYTES
        require(textFloats > 0 && textFloats % TEXT_EMBED_CHANNELS == 0) {
            "Reza VE text_emb size is invalid: $textFloats floats"
        }
        val tReal = textFloats / TEXT_EMBED_CHANNELS

        val step = scalar(currentStep)
        var cache = vectorCache
        if (
            step == 0.0f ||
            cache == null ||
            cache.lReal != lReal ||
            cache.tReal != tReal
        ) {
            resetVectorCache()
            cache = buildVectorCache(
                noisyLatent = noisyLatent,
                textEmb = textEmb,
                styleTtl = styleTtl,
                latentMask = latentMask,
                textMask = textMask,
                currentStep = currentStep,
                totalStep = totalStep,
                out = out,
                lReal = lReal,
                tReal = tReal,
            )
            vectorCache = cache
        }

        // Pinned output avoids an ORT-allocated output tensor followed by a Java
        // copy. C++ still keeps input/output separate and performs one controlled
        // memcpy after this call, preserving the existing no-aliasing rule.
        session.run(
            cache.inputs,
            mapOf(outputName to cache.output),
        ).use { /* pinned output is owned by VectorCache, not Result */ }

        val totalSteps = scalar(totalStep)
        if (step >= totalSteps - 1.0f) {
            // Release wrappers before C++ may resize backing buffers next chunk.
            resetVectorCache()
        }
    }

    override fun close() {
        resetVectorCache()
        runCatching { session.close() }
        runCatching { options.close() }
        // OrtEnvironment.getEnvironment() is process-global; do not close it.
    }
}
