package audio.soniqo.speech

import android.util.Log

enum class TtsModel(internal val nativeId: Int) { SUPERTONIC(1) }

enum class InferenceBackend(internal val nativeId: Int) {
    CPU_XNNPACK(0),
    GPU_LITERT(1),
    // Explicitly experimental: unsafe Encoder/DSP-VE paths stay blocked. The
    // retained QNN HTP vocoder probe automatically retries on CPU when rejected.
    QUALCOMM_NPU(2),
    // Native C++ only distinguishes CPU from an externally-owned runner.
    // Reuse its accelerator id while DelegateSupertonicRunner selects NNAPI.
    NNAPI_DEVICE(1),
}

data class SpeechSynthesizerConfig(
    val modelDir: String = "",
    val useNnapi: Boolean = false,
    val backend: InferenceBackend = InferenceBackend.CPU_XNNPACK,
    val ttsModel: TtsModel = TtsModel.SUPERTONIC,
    val voiceId: String = "F1",
    val speed: Float = 1.0f,
    val totalSteps: Int = 4,
    val numThreads: Int = 4,
    val chunkCap: Int = 64,
    val preGenerationQueue: Int = 2,
    val chunkGapMinMs: Int = 0,
    val chunkGapMaxMs: Int = 250,
    val trailingSilenceTrimMs: Int = 220,
    /** Android's extracted native-library directory (applicationInfo.nativeLibraryDir). */
    val nativeLibraryDir: String = "",
    /** Writable cache directory used for LiteRT GPU/QNN on-device compilation. */
    val acceleratorCacheDir: String = "",
)

data class SpeechSynthesisResult(
    val sampleRate: Int,
    val pcm16: ByteArray,
    val profile: String = "",
)

interface SpeechSynthesizer : AutoCloseable {
    val sampleRate: Int
    fun synthesize(text: String, language: String = "en"): SpeechSynthesisResult
    fun synthesizeStreaming(text: String, language: String = "en", onChunk: (ByteArray, Boolean) -> Unit)
    fun setVoice(voiceId: String)
    fun setSpeed(speed: Float)
    fun setTotalSteps(totalSteps: Int)
    fun setChunkCap(chunkCap: Int)
    fun setPreGeneration(enabled: Boolean)
    fun setPreGenerationQueue(depth: Int)
    fun setChunkGap(minMs: Int, maxMs: Int)
    fun setTrailingSilenceTrimMs(ms: Int)
    fun lastProfile(): String
    fun stop()
    companion object {
        operator fun invoke(config: SpeechSynthesizerConfig): SpeechSynthesizer = SpeechSynthesizerImpl(config)
    }
}

internal class SpeechSynthesizerImpl(
    private val config: SpeechSynthesizerConfig,
) : SpeechSynthesizer {
    companion object {
        private const val TAG = "SupertonicSynth"
    }

    /*
     * The accelerator runner is deliberately isolated from the stable native
     * CPU/XNNPACK runtime. Delegate initialization errors, non-finite tensors,
     * and the native low-energy audio guard all converge here and trigger one
     * clean CPU recreation before any audio is returned to the caller.
     */
    @Volatile private var acceleratorRunner: DelegateSupertonicRunner? = null
    @Volatile private var handle: Long = 0L
    @Volatile private var activeBackend = InferenceBackend.CPU_XNNPACK
    @Volatile private var fallbackReason: String? = null

    private var appliedVoice = config.voiceId
    private var appliedSpeed = config.speed.coerceIn(0.25f, 3.0f)
    private var appliedSteps = config.totalSteps.coerceIn(1, 64)
    private var appliedChunkCap = config.chunkCap.coerceIn(24, 96)
    private var appliedPreGeneration = false
    private var appliedPreGenerationQueue = config.preGenerationQueue.coerceIn(2, 3)
    private var appliedGapMin = config.chunkGapMinMs.coerceIn(0, 2000)
    private var appliedGapMax = config.chunkGapMaxMs.coerceIn(0, 2000).coerceAtLeast(appliedGapMin)
    private var appliedTrailingTrim = config.trailingSilenceTrimMs.coerceIn(0, 500)

    init {
        try {
            installBackend(config.backend)
        } catch (t: Throwable) {
            if (config.backend == InferenceBackend.CPU_XNNPACK) throw t
            fallbackReason = "${config.backend.name} init failed: ${messageOf(t)}"
            Log.w(TAG, "Accelerator init failed; using CPU/XNNPACK", t)
            installBackend(InferenceBackend.CPU_XNNPACK)
        }
    }

    private fun installBackend(backend: InferenceBackend) {
        var madeRunner: DelegateSupertonicRunner? = null
        var madeHandle = 0L
        try {
            if (backend != InferenceBackend.CPU_XNNPACK) {
                madeRunner = DelegateSupertonicRunner(config.copy(backend = backend))
            }
            madeHandle = NativeBridge.nativeCreateSynthesizer(
                config.modelDir,
                config.useNnapi,
                backend.nativeId,
                config.ttsModel.nativeId,
                appliedVoice,
                appliedSteps,
                appliedSpeed,
                config.numThreads,
                appliedChunkCap,
                config.nativeLibraryDir,
                config.acceleratorCacheDir,
                madeRunner,
            )
            check(madeHandle != 0L) { "Supertonic native engine could not be created" }
            applyRuntimeSettings(madeHandle)
            acceleratorRunner = madeRunner
            activeBackend = backend
            handle = madeHandle
        } catch (t: Throwable) {
            if (madeHandle != 0L) runCatching { NativeBridge.nativeDestroySynthesizer(madeHandle) }
            runCatching { madeRunner?.close() }
            throw t
        }
    }

    private fun applyRuntimeSettings(target: Long) {
        NativeBridge.nativeSetSynthesizerVoice(target, appliedVoice)
        NativeBridge.nativeSetSynthesizerSpeed(target, appliedSpeed)
        NativeBridge.nativeSetSynthesizerSteps(target, appliedSteps)
        NativeBridge.nativeSetSynthesizerChunkCap(target, appliedChunkCap)
        NativeBridge.nativeSetSynthesizerPreGeneration(target, appliedPreGeneration)
        NativeBridge.nativeSetSynthesizerPreGenerationQueue(target, appliedPreGenerationQueue)
        NativeBridge.nativeSetSynthesizerChunkGap(target, appliedGapMin, appliedGapMax)
        NativeBridge.nativeSetSynthesizerTrailingSilenceTrim(target, appliedTrailingTrim)
    }

    private fun fallbackToCpu(failure: Throwable) {
        val failedBackend = activeBackend
        if (failedBackend == InferenceBackend.CPU_XNNPACK) throw failure
        fallbackReason = "${failedBackend.name} rejected: ${messageOf(failure)}"
        Log.w(TAG, "Accelerator output rejected; recreating on CPU/XNNPACK", failure)

        val oldHandle = handle
        handle = 0L
        if (oldHandle != 0L) runCatching { NativeBridge.nativeDestroySynthesizer(oldHandle) }
        runCatching { acceleratorRunner?.close() }
        acceleratorRunner = null
        activeBackend = InferenceBackend.CPU_XNNPACK
        installBackend(InferenceBackend.CPU_XNNPACK)
    }

    private fun messageOf(t: Throwable): String =
        (t.message ?: t.javaClass.simpleName).replace(';', ',').replace('=', ':').replace('\n', ' ')

    private fun decoratedProfile(): String {
        val native = if (handle != 0L) NativeBridge.nativeGetLastProfile(handle) else ""
        return buildString {
            if (native.isNotBlank()) append(native).append(';')
            append("requested_backend=").append(config.backend.name)
            append(";active_backend=").append(activeBackend.name)
            append(";accelerator_fallback=").append(fallbackReason ?: "none")
        }
    }

    override val sampleRate: Int
        get() = if (handle != 0L) NativeBridge.nativeSynthesizerSampleRate(handle) else 0

    override fun synthesize(text: String, language: String): SpeechSynthesisResult {
        val pcm = try {
            NativeBridge.nativeSynthesize(handle, text, language)
        } catch (t: Throwable) {
            fallbackToCpu(t)
            NativeBridge.nativeSynthesize(handle, text, language)
        }
        return SpeechSynthesisResult(sampleRate, pcm, decoratedProfile())
    }

    override fun synthesizeStreaming(text: String, language: String, onChunk: (ByteArray, Boolean) -> Unit) {
        var emittedAudio = false
        val callback = NativeBridge.SynthesisCallback { audio, final ->
            if (audio.isNotEmpty()) emittedAudio = true
            onChunk(audio, final)
        }
        try {
            NativeBridge.nativeSynthesizeStreaming(handle, text, language, callback)
        } catch (t: Throwable) {
            if (emittedAudio || activeBackend == InferenceBackend.CPU_XNNPACK) throw t
            fallbackToCpu(t)
            NativeBridge.nativeSynthesizeStreaming(handle, text, language, callback)
        }
    }

    override fun setVoice(voiceId: String) {
        appliedVoice = voiceId
        NativeBridge.nativeSetSynthesizerVoice(handle, voiceId)
    }

    override fun setSpeed(speed: Float) {
        appliedSpeed = speed.coerceIn(0.25f, 3.0f)
        NativeBridge.nativeSetSynthesizerSpeed(handle, appliedSpeed)
    }

    override fun setTotalSteps(totalSteps: Int) {
        appliedSteps = totalSteps.coerceIn(1, 64)
        NativeBridge.nativeSetSynthesizerSteps(handle, appliedSteps)
    }

    override fun setChunkCap(chunkCap: Int) {
        appliedChunkCap = chunkCap.coerceIn(24, 96)
        NativeBridge.nativeSetSynthesizerChunkCap(handle, appliedChunkCap)
    }

    override fun setPreGeneration(enabled: Boolean) {
        appliedPreGeneration = enabled
        NativeBridge.nativeSetSynthesizerPreGeneration(handle, enabled)
    }

    override fun setPreGenerationQueue(depth: Int) {
        appliedPreGenerationQueue = depth.coerceIn(2, 3)
        NativeBridge.nativeSetSynthesizerPreGenerationQueue(handle, appliedPreGenerationQueue)
    }

    override fun setChunkGap(minMs: Int, maxMs: Int) {
        appliedGapMin = minMs.coerceIn(0, 2000)
        appliedGapMax = maxMs.coerceIn(0, 2000).coerceAtLeast(appliedGapMin)
        NativeBridge.nativeSetSynthesizerChunkGap(handle, appliedGapMin, appliedGapMax)
    }

    override fun setTrailingSilenceTrimMs(ms: Int) {
        appliedTrailingTrim = ms.coerceIn(0, 500)
        NativeBridge.nativeSetSynthesizerTrailingSilenceTrim(handle, appliedTrailingTrim)
    }

    override fun lastProfile(): String = decoratedProfile()

    override fun stop() {
        val current = handle
        if (current != 0L) NativeBridge.nativeStopSynthesizer(current)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) NativeBridge.nativeDestroySynthesizer(current)
        acceleratorRunner?.close()
        acceleratorRunner = null
    }
}
