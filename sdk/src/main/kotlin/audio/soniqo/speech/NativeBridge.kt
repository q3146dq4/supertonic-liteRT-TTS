package audio.soniqo.speech

internal object NativeBridge {
    init { System.loadLibrary("speech_android") }

    external fun nativeCreateSynthesizer(
        modelDir: String,
        useNnapi: Boolean,
        ttsModel: Int,
        voiceId: String,
        totalSteps: Int,
        speed: Float,
        numThreads: Int,
        chunkCap: Int,
    ): Long
    external fun nativeDestroySynthesizer(handle: Long)
    external fun nativeStopSynthesizer(handle: Long)
    external fun nativeSetSynthesizerVoice(handle: Long, voiceId: String)
    external fun nativeSetSynthesizerSpeed(handle: Long, speed: Float)
    external fun nativeSetSynthesizerSteps(handle: Long, totalSteps: Int)
    external fun nativeSetSynthesizerChunkCap(handle: Long, chunkCap: Int)
    external fun nativeSetSynthesizerPreGeneration(handle: Long, enabled: Boolean)
    external fun nativeSetSynthesizerPreGenerationQueue(handle: Long, depth: Int)
    external fun nativeSetSynthesizerChunkGap(handle: Long, minMs: Int, maxMs: Int)
    external fun nativeSetSynthesizerTrailingSilenceTrim(handle: Long, trimMs: Int)
    external fun nativeGetLastProfile(handle: Long): String
    external fun nativeSynthesizerSampleRate(handle: Long): Int
    external fun nativeSynthesize(handle: Long, text: String, language: String): ByteArray
    external fun nativeSynthesizeStreaming(handle: Long, text: String, language: String, callback: SynthesisCallback)

    fun interface SynthesisCallback { fun onChunk(audio: ByteArray, isFinal: Boolean) }
}
