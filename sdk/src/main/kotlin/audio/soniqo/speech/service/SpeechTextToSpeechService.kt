package audio.soniqo.speech.service

import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.InferenceBackend
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsSettings
import audio.soniqo.speech.TtsModel
import audio.soniqo.speech.rules.PronunciationRules
import audio.soniqo.speech.audio.AudioSpeedProcessor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

/** Android system TextToSpeechService backed by Soniqo Supertonic-3 LiteRT. */
class SpeechTextToSpeechService : TextToSpeechService() {
    private val lock = Any()
    private val synthesisLock = Any()
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    @Volatile private var synthesisWakeLock: PowerManager.WakeLock? = null
    private val wakeLockRelease = Runnable { releaseSynthesisWakeLock() }
    private val wakeLockLingerMs = 5000L
    @Volatile private var synthesizer: SpeechSynthesizer? = null
    @Volatile private var stopped = false
    @Volatile private var selectedVoice = "F1"
    @Volatile private var loadedLang3 = "eng"
    @Volatile private var loadedCountry3 = "USA"
    @Volatile private var loadedThreads = 0
    @Volatile private var loadedBackend = InferenceBackend.CPU_XNNPACK
    @Volatile private var loadedTtsModel = TtsModel.SUPERTONIC

    override fun onCreate() {
        super.onCreate()
        selectedVoice = TtsSettings.voice(applicationContext)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        synthesisWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${packageName}:TTS-Synthesis"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireSynthesisWakeLock() {
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        val wl = synthesisWakeLock ?: return
        if (!wl.isHeld) {
            wl.acquire()
            Log.d(TAG, "TTS_WAKELOCK acquired")
        }
    }

    private fun releaseSynthesisWakeLock() {
        val wl = synthesisWakeLock ?: return
        if (wl.isHeld) {
            wl.release()
            Log.d(TAG, "TTS_WAKELOCK released")
        }
    }

    private fun releaseSynthesisWakeLockAfterLinger() {
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        wakeLockHandler.postDelayed(wakeLockRelease, wakeLockLingerMs)
    }

    override fun onGetLanguage(): Array<String> = arrayOf(loadedLang3, loadedCountry3, "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = languageAvailability(lang)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val normalized = normalizeLanguage(lang)
        if (normalized !in LANGS) return TextToSpeech.LANG_NOT_SUPPORTED
        loadedLang3 = lang?.takeIf { it.length == 3 } ?: toIso3(normalized)
        loadedCountry3 = country?.takeIf { it.isNotBlank() } ?: defaultCountry(normalized)
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetVoices(): MutableList<Voice> {
        val result = mutableListOf<Voice>()
        for (id in builtinVoiceIds()) {
            // networkConnectionRequired=false is the API 21+ way to expose an
            // offline voice. KEY_FEATURE_EMBEDDED_SYNTHESIS is deprecated.
            result += Voice(
                voiceNameForId(id), Locale.ROOT, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, emptySet(),
            )
        }
        for (id in customVoiceIds()) {
            result += Voice(
                voiceNameForId(id), Locale.ROOT, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, emptySet(),
            )
        }
        return result
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (languageAvailability(lang) == TextToSpeech.LANG_NOT_SUPPORTED) return null
        return voiceNameForId(TtsSettings.voice(applicationContext))
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceIdFromName(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        val id = voiceIdFromName(voiceName) ?: return TextToSpeech.ERROR
        synchronized(lock) {
            selectedVoice = id
            synthesizer?.close()
            synthesizer = null
        }
        TtsSettings.save(applicationContext, selectedVoice, TtsSettings.speed(applicationContext), TtsSettings.steps(applicationContext), TtsSettings.threads(applicationContext))
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        stopped = true
        synthesizer?.stop()
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        releaseSynthesisWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val rawText = request.charSequenceText?.toString()?.trim().orEmpty()
        if (rawText.isEmpty()) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return }
        stopped = false
        acquireSynthesisWakeLock()
        synchronized(synthesisLock) {
            try {
                val text = PronunciationRules.apply(applicationContext, rawText)
                val languageRequested = normalizeLanguage(request.language)
                if (languageRequested !in LANGS) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return }
                val language = when {
                    languageRequested == "na" -> "na"
                    PronunciationRules.isMixedScript(text) && TtsSettings.allowNa(applicationContext) -> "na"
                    else -> languageRequested
                }

                // Stored engine preference is authoritative for this custom TTS engine.
                val configuredVoice = TtsSettings.voice(applicationContext)
                selectedVoice = configuredVoice
                val configuredSteps = TtsSettings.steps(applicationContext).coerceIn(1, 64)
                val configuredThreads = TtsSettings.threads(applicationContext).coerceIn(1, 64)
                val configuredTtsModel = TtsSettings.ttsModel(applicationContext)
                val configuredBackend = when (configuredTtsModel) {
                    TtsModel.SUPERTONIC_REZA ->
                        InferenceBackend.CPU_XNNPACK
                    TtsModel.SUPERTONIC_SONIQO_FULL_FP16 -> {
                        val saved = TtsSettings.backend(applicationContext)
                        if (
                            saved == InferenceBackend.GPU_LITERT ||
                            saved == InferenceBackend.QUALCOMM_NPU
                        ) saved else InferenceBackend.GPU_LITERT
                    }
                    else -> TtsSettings.backend(applicationContext)
                }
                val configuredChunkCap = TtsSettings.chunkCap(applicationContext)
                val configuredPregen = TtsSettings.preGeneration(applicationContext)
                val configuredPregenQueue = TtsSettings.preGenerationQueue(applicationContext)
                val configuredGapMin = TtsSettings.chunkGapMinMs(applicationContext)
                val configuredGapMax = TtsSettings.chunkGapMaxMs(applicationContext)
                val configuredTrailingTrim = TtsSettings.trailingSilenceTrimMs(applicationContext)
                val requestRate = request.speechRate.coerceIn(10, 400) / 100f
                val configuredSpeed = TtsSettings.speed(applicationContext).coerceIn(0.25f, 3.0f)
                val effectiveSpeed = (configuredSpeed * requestRate).coerceIn(0.25f, 3.0f)

                if (
                    loadedThreads != configuredThreads ||
                    loadedBackend != configuredBackend ||
                    loadedTtsModel != configuredTtsModel
                ) {
                    synchronized(lock) { synthesizer?.close(); synthesizer = null }
                }
                var synth = getOrCreateSynthesizer(
                    configuredVoice,
                    configuredSteps,
                    configuredThreads,
                    configuredBackend,
                    configuredTtsModel,
                )
                try {
                    synth.setVoice(configuredVoice)
                } catch (_: Throwable) {
                    // A custom voice may have been imported after this service instance created
                    // its native voice table. Recreate once so the new JSON is loaded.
                    synchronized(lock) { synthesizer?.close(); synthesizer = null }
                    synth = getOrCreateSynthesizer(
                        configuredVoice,
                        configuredSteps,
                        configuredThreads,
                        configuredBackend,
                        configuredTtsModel,
                    )
                    synth.setVoice(configuredVoice)
                }
                synth.setSpeed(1.0f)
                synth.setTotalSteps(configuredSteps)
                synth.setChunkCap(configuredChunkCap)
                synth.setPreGeneration(configuredPregen)
                synth.setPreGenerationQueue(configuredPregenQueue)
                synth.setChunkGap(configuredGapMin, configuredGapMax)
                synth.setTrailingSilenceTrimMs(configuredTrailingTrim)

                Log.i(TAG, "SYNTH_APPLIED model=$configuredTtsModel backend=$configuredBackend voice=$configuredVoice speed=$effectiveSpeed steps=$configuredSteps chunk=$configuredChunkCap pregen=$configuredPregen queue=$configuredPregenQueue gap=$configuredGapMin-$configuredGapMax trailingTrim=$configuredTrailingTrim lang=$language requestVoice=${request.voiceName} requestRate=$requestRate rules=${PronunciationRules.count(applicationContext)}")

                // The model always synthesizes at 1x. At the common 1.00x setting we use
                // the native chunk callback so Android receives the first audio as soon as
                // the first chunk is ready. For other rates we keep the previous whole-wave
                // Sonic pass, because independently time-stretching every chunk can create
                // audible seams and changes the established speed semantics.
                val requestStartNs = SystemClock.elapsedRealtimeNanos()
                var firstAudioNs = 0L
                callback.start(synth.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

                if (effectiveSpeed == 1.0f) {
                    var streamFinalSignaled = false
                    synth.synthesizeStreaming(text, language) { pcm, finalChunk ->
                        if (stopped) return@synthesizeStreaming
                        if (pcm.isNotEmpty()) {
                            var offset = 0
                            while (offset < pcm.size && !stopped) {
                                val count = minOf(callback.maxBufferSize.coerceAtLeast(1024), pcm.size - offset)
                                if (callback.audioAvailable(pcm, offset, count) != TextToSpeech.SUCCESS) {
                                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                                    synth.stop()
                                    return@synthesizeStreaming
                                }
                                if (firstAudioNs == 0L) {
                                    firstAudioNs = SystemClock.elapsedRealtimeNanos()
                                    val ttfaMs = (firstAudioNs - requestStartNs) / 1_000_000.0
                                    Log.i(TAG, "SYNTH_TTFA ${String.format(java.util.Locale.US, "%.1f", ttfaMs)} ms mode=stream chunk=$configuredChunkCap")
                                }
                                offset += count
                            }
                        }
                        if (finalChunk && !streamFinalSignaled && !stopped) {
                            streamFinalSignaled = true
                            callback.done()
                        }
                    }
                    Log.i(TAG, "SYNTH_PROFILE ${synth.lastProfile()}")
                    if (streamFinalSignaled) {
                        // Final PCM was already handed to Android. Native cleanup may still
                        // be joining speculative pre-generation workers, but chapter navigation
                        // is allowed to proceed without waiting for that teardown.
                        releaseSynthesisWakeLockAfterLinger()
                        return
                    }
                } else {
                    val result = synth.synthesize(text, language)
                    val adjusted = AudioSpeedProcessor.apply(result.pcm16, result.sampleRate, effectiveSpeed)
                    Log.i(TAG, "SYNTH_PROFILE ${result.profile}; speed_process_ms=${adjusted.processingMs}")
                    if (!stopped && adjusted.pcm16.isNotEmpty()) {
                        var offset = 0
                        while (offset < adjusted.pcm16.size && !stopped) {
                            val count = minOf(callback.maxBufferSize.coerceAtLeast(1024), adjusted.pcm16.size - offset)
                            if (callback.audioAvailable(adjusted.pcm16, offset, count) != TextToSpeech.SUCCESS) {
                                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                                return
                            }
                            if (firstAudioNs == 0L) {
                                firstAudioNs = SystemClock.elapsedRealtimeNanos()
                                val ttfaMs = (firstAudioNs - requestStartNs) / 1_000_000.0
                                Log.i(TAG, "SYNTH_TTFA ${String.format(java.util.Locale.US, "%.1f", ttfaMs)} ms mode=full speed=$effectiveSpeed")
                            }
                            offset += count
                        }
                    }
                }
                if (stopped) callback.error(TextToSpeech.ERROR_SYNTHESIS) else callback.done()
            } catch (t: Throwable) {
                Log.e(TAG, "TTS synthesis failed", t)
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
            } finally {
                releaseSynthesisWakeLockAfterLinger()
            }
        }
    }

    override fun onDestroy() {
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        releaseSynthesisWakeLock()
        synchronized(lock) { synthesizer?.close(); synthesizer = null }
        super.onDestroy()
    }

    private fun getOrCreateSynthesizer(
        voiceId: String,
        totalSteps: Int,
        numThreads: Int,
        backend: InferenceBackend,
        ttsModel: TtsModel,
    ): SpeechSynthesizer {
        synchronized(lock) { synthesizer?.let { return it } }
        val modelDir = runBlocking {
            ModelManager.ensureTtsModels(applicationContext, ttsModel)
        }
        return synchronized(lock) {
            synthesizer ?: SpeechSynthesizer(
                SpeechSynthesizerConfig(
                    modelDir = modelDir,
                    useNnapi = false,
                    backend = backend,
                    ttsModel = ttsModel,
                    voiceId = voiceId,
                    speed = 1.0f,
                    totalSteps = totalSteps,
                    numThreads = numThreads,
                    chunkCap = TtsSettings.chunkCap(applicationContext),
                    preGenerationQueue = TtsSettings.preGenerationQueue(applicationContext),
                    chunkGapMinMs = TtsSettings.chunkGapMinMs(applicationContext),
                    chunkGapMaxMs = TtsSettings.chunkGapMaxMs(applicationContext),
                    trailingSilenceTrimMs = TtsSettings.trailingSilenceTrimMs(applicationContext),
                    nativeLibraryDir = applicationInfo.nativeLibraryDir,
                    acceleratorCacheDir = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath,
                )
            ).also {
                loadedThreads = numThreads
                loadedBackend = backend
                loadedTtsModel = ttsModel
                synthesizer = it
            }
        }
    }

    private fun builtinVoiceIds(): List<String> = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")

    private fun customVoiceIds(): List<String> = runCatching {
        val dir = File(
            ModelManager.modelDir(applicationContext, TtsSettings.ttsModel(applicationContext)),
            "voice_styles",
        )
        dir.listFiles()?.asSequence()
            ?.filter { it.extension.equals("json", true) }
            ?.map { it.nameWithoutExtension }
            ?.filter { it !in builtinVoiceIds() }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun voiceNameForId(id: String): String = when (id.uppercase(Locale.ROOT)) {
        "F1", "F2", "F3", "F4", "F5" -> "supertonic-f${id.substring(1)}"
        "M1", "M2", "M3", "M4", "M5" -> "supertonic-m${id.substring(1)}"
        else -> "supertonic-custom-${id.removePrefix("custom_")}"
    }

    private fun voiceIdFromName(name: String?): String? {
        if (name == null) return null
        builtinVoiceIds().firstOrNull { voiceNameForId(it) == name }?.let { return it }
        if (name.startsWith("supertonic-custom-")) {
            val id = "custom_" + name.removePrefix("supertonic-custom-")
            return if (id in customVoiceIds()) id else null
        }
        return null
    }

    companion object {
        private const val TAG = "SupertonicTTS"
        private val LANGS = setOf(
            "na", "en", "ko", "ja", "zh", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv",
            "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi"
        )
        fun normalizeLanguage(lang: String?): String {
            val l = lang?.lowercase(Locale.ROOT).orEmpty()
            return when (l) {
                "eng" -> "en"; "kor" -> "ko"; "jpn" -> "ja"; "ara" -> "ar"; "bul" -> "bg"; "ces" -> "cs"; "dan" -> "da"; "deu" -> "de";
                "ell" -> "el"; "spa" -> "es"; "est" -> "et"; "fin" -> "fi"; "fra" -> "fr"; "hin" -> "hi"; "hrv" -> "hr"; "hun" -> "hu";
                "ind" -> "id"; "ita" -> "it"; "lit" -> "lt"; "lav" -> "lv"; "nld" -> "nl"; "pol" -> "pl"; "por" -> "pt"; "ron" -> "ro";
                "rus" -> "ru"; "slk" -> "sk"; "slv" -> "sl"; "swe" -> "sv"; "tur" -> "tr"; "ukr" -> "uk"; "vie" -> "vi"; "zho", "chi", "cmn" -> "zh";
                "" -> "en"; else -> l.substringBefore('-').ifBlank { "en" }
            }
        }
        private fun languageAvailability(lang: String?): Int = if (normalizeLanguage(lang) in LANGS) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
        private fun toIso3(lang: String): String = when (lang) {
            "na" -> "eng"; "ko" -> "kor"; "ja" -> "jpn"; "zh" -> "zho"; "de" -> "deu"; "fr" -> "fra"; "es" -> "spa"; "en" -> "eng"
            else -> Locale.forLanguageTag(lang).isO3Language.ifBlank { lang }
        }
        private fun defaultCountry(lang: String): String = when (lang) {
            "ko" -> "KOR"; "ja" -> "JPN"; "zh" -> "CHN"; "en", "na" -> "USA"; else -> ""
        }
    }
}
