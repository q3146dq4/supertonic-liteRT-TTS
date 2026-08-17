package audio.soniqo.speech

enum class TtsModel(internal val nativeId: Int) { SUPERTONIC(1) }

data class SpeechSynthesizerConfig(
    val modelDir: String = "",
    val useNnapi: Boolean = false,
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

internal class SpeechSynthesizerImpl(config: SpeechSynthesizerConfig) : SpeechSynthesizer {
    private var handle: Long = NativeBridge.nativeCreateSynthesizer(
        config.modelDir,
        config.useNnapi,
        config.ttsModel.nativeId,
        config.voiceId,
        config.totalSteps,
        config.speed,
        config.numThreads,
        config.chunkCap,
    ).also { h ->
        if (h == 0L) throw IllegalStateException("Supertonic native engine could not be created")
        NativeBridge.nativeSetSynthesizerPreGenerationQueue(h, config.preGenerationQueue.coerceIn(2, 3))
        NativeBridge.nativeSetSynthesizerChunkGap(h, config.chunkGapMinMs.coerceIn(0, 2000), config.chunkGapMaxMs.coerceIn(0, 2000).coerceAtLeast(config.chunkGapMinMs.coerceIn(0, 2000)))
        NativeBridge.nativeSetSynthesizerTrailingSilenceTrim(h, config.trailingSilenceTrimMs.coerceIn(0, 500))
    }

    override val sampleRate: Int get() = NativeBridge.nativeSynthesizerSampleRate(handle)

    override fun synthesize(text: String, language: String): SpeechSynthesisResult =
        SpeechSynthesisResult(sampleRate, NativeBridge.nativeSynthesize(handle, text, language), NativeBridge.nativeGetLastProfile(handle))

    override fun synthesizeStreaming(text: String, language: String, onChunk: (ByteArray, Boolean) -> Unit) {
        NativeBridge.nativeSynthesizeStreaming(handle, text, language,
            NativeBridge.SynthesisCallback { audio, final -> onChunk(audio, final) })
    }

    override fun setVoice(voiceId: String) = NativeBridge.nativeSetSynthesizerVoice(handle, voiceId)
    override fun setSpeed(speed: Float) = NativeBridge.nativeSetSynthesizerSpeed(handle, speed)
    override fun setTotalSteps(totalSteps: Int) = NativeBridge.nativeSetSynthesizerSteps(handle, totalSteps.coerceIn(1, 64))
    override fun setChunkCap(chunkCap: Int) = NativeBridge.nativeSetSynthesizerChunkCap(handle, chunkCap.coerceIn(24, 96))
    override fun setPreGeneration(enabled: Boolean) = NativeBridge.nativeSetSynthesizerPreGeneration(handle, enabled)
    override fun setPreGenerationQueue(depth: Int) = NativeBridge.nativeSetSynthesizerPreGenerationQueue(handle, depth.coerceIn(2, 3))
    override fun setChunkGap(minMs: Int, maxMs: Int) = NativeBridge.nativeSetSynthesizerChunkGap(handle, minMs.coerceIn(0, 2000), maxMs.coerceIn(0, 2000).coerceAtLeast(minMs.coerceIn(0, 2000)))
    override fun setTrailingSilenceTrimMs(ms: Int) = NativeBridge.nativeSetSynthesizerTrailingSilenceTrim(handle, ms.coerceIn(0, 500))
    override fun lastProfile(): String = NativeBridge.nativeGetLastProfile(handle)
    override fun stop() { if (handle != 0L) NativeBridge.nativeStopSynthesizer(handle) }
    override fun close() { if (handle != 0L) { NativeBridge.nativeDestroySynthesizer(handle); handle = 0 } }
}
