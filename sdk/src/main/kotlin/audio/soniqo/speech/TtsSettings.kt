package audio.soniqo.speech

import android.content.Context

object TtsSettings {
    private const val PREFS = "supertonic_tts"
    private const val KEY_VOICE = "voice"
    private const val KEY_SPEED = "speed"
    private const val KEY_STEPS = "steps"
    private const val KEY_ALLOW_NA = "allow_na"
    private const val KEY_TEST_LANGUAGE = "test_language"
    private const val KEY_THREADS = "threads"
    private const val KEY_BACKEND = "backend"
    private const val KEY_CPU_DEFAULT_MIGRATION = "cpu_default_backend_migration_20260818_delegate_v3"
    private const val KEY_CHUNK_MODE = "chunk_mode"
    private const val KEY_CHUNK_CAP = "chunk_cap"
    private const val KEY_PREGEN = "pre_generation"
    private const val KEY_PREGEN_QUEUE = "pre_generation_queue"
    private const val KEY_GAP_MIN = "chunk_gap_min_ms"
    private const val KEY_GAP_MAX = "chunk_gap_max_ms"
    private const val KEY_TRAILING_TRIM = "trailing_silence_trim_ms"

    const val CHUNK_CONSERVATIVE = "conservative"
    const val CHUNK_BALANCED = "balanced"
    const val CHUNK_LONG = "long"
    const val CHUNK_MANUAL = "manual"

    const val MIN_CHUNK_CAP = 24
    const val MAX_CHUNK_CAP = 96
    const val MIN_GAP_MS = 0
    const val MAX_GAP_MS = 2000
    const val DEFAULT_GAP_MIN_MS = 0
    const val DEFAULT_GAP_MAX_MS = 250
    const val MIN_TRAILING_TRIM_MS = 0
    const val MAX_TRAILING_TRIM_MS = 500
    const val DEFAULT_TRAILING_TRIM_MS = 0

    fun voice(context: Context): String = prefs(context).getString(KEY_VOICE, "F1") ?: "F1"
    fun speed(context: Context): Float = prefs(context).getFloat(KEY_SPEED, 1.0f)
    fun steps(context: Context): Int = prefs(context).getInt(KEY_STEPS, 4)
    fun allowNa(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_NA, false)
    fun testLanguage(context: Context): String = prefs(context).getString(KEY_TEST_LANGUAGE, "na") ?: "na"
    fun threads(context: Context): Int = prefs(context).getInt(KEY_THREADS, 4)
    fun backend(context: Context): InferenceBackend {
        val p = prefs(context)
        // Earlier acceleration test builds could leave GPU/NPU persisted across APK updates.
        // Migrate exactly once to the safe CPU/XNNPACK default. After this one-time migration,
        // whatever backend the user explicitly selects is persisted normally.
        if (!p.getBoolean(KEY_CPU_DEFAULT_MIGRATION, false)) {
            check(p.edit()
                .putString(KEY_BACKEND, InferenceBackend.CPU_XNNPACK.name)
                .putBoolean(KEY_CPU_DEFAULT_MIGRATION, true)
                .commit()) { "Failed to migrate Supertonic backend default to CPU/XNNPACK" }
            return InferenceBackend.CPU_XNNPACK
        }
        val restored = runCatching {
            InferenceBackend.valueOf(
                p.getString(KEY_BACKEND, InferenceBackend.CPU_XNNPACK.name)
                    ?: InferenceBackend.CPU_XNNPACK.name
            )
        }.getOrDefault(InferenceBackend.CPU_XNNPACK)
        return restored
    }
    fun chunkMode(context: Context): String = prefs(context).getString(KEY_CHUNK_MODE, CHUNK_BALANCED) ?: CHUNK_BALANCED
    fun manualChunkCap(context: Context): Int = prefs(context).getInt(KEY_CHUNK_CAP, 64).coerceIn(MIN_CHUNK_CAP, MAX_CHUNK_CAP)
    fun preGeneration(context: Context): Boolean = prefs(context).getBoolean(KEY_PREGEN, false)
    fun preGenerationQueue(context: Context): Int = prefs(context).getInt(KEY_PREGEN_QUEUE, 2).coerceIn(2, 3)
    fun chunkGapMinMs(context: Context): Int = prefs(context).getInt(KEY_GAP_MIN, DEFAULT_GAP_MIN_MS).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    fun chunkGapMaxMs(context: Context): Int = prefs(context).getInt(KEY_GAP_MAX, DEFAULT_GAP_MAX_MS).coerceIn(MIN_GAP_MS, MAX_GAP_MS).coerceAtLeast(chunkGapMinMs(context))
    fun trailingSilenceTrimMs(context: Context): Int = prefs(context).getInt(KEY_TRAILING_TRIM, DEFAULT_TRAILING_TRIM_MS).coerceIn(MIN_TRAILING_TRIM_MS, MAX_TRAILING_TRIM_MS)

    fun chunkCap(context: Context): Int = when (chunkMode(context)) {
        CHUNK_CONSERVATIVE -> 40
        CHUNK_LONG -> 88
        CHUNK_MANUAL -> manualChunkCap(context)
        else -> 64
    }

    fun save(
        context: Context,
        voice: String,
        speed: Float,
        steps: Int,
        threads: Int = threads(context),
        chunkMode: String = chunkMode(context),
        manualChunkCap: Int = manualChunkCap(context),
        backend: InferenceBackend = backend(context),
    ) {
        // System TTS can be invoked immediately after leaving this app. Commit synchronously
        // so the TextToSpeechService sees the new settings without a disk-write race.
        check(prefs(context).edit()
            .putString(KEY_VOICE, voice)
            .putFloat(KEY_SPEED, speed)
            .putInt(KEY_STEPS, steps)
            .putInt(KEY_THREADS, threads.coerceIn(1, 64))
            .putString(KEY_BACKEND, backend.name)
            .putString(KEY_CHUNK_MODE, normalizeChunkMode(chunkMode))
            .putInt(KEY_CHUNK_CAP, manualChunkCap.coerceIn(MIN_CHUNK_CAP, MAX_CHUNK_CAP))
            .putBoolean(KEY_PREGEN, prefs(context).getBoolean(KEY_PREGEN, false))
            .putBoolean(KEY_ALLOW_NA, prefs(context).getBoolean(KEY_ALLOW_NA, false))
            .commit()) { "Failed to persist Supertonic TTS settings" }
    }


    fun setBackend(context: Context, backend: InferenceBackend) {
        check(prefs(context).edit()
            .putString(KEY_BACKEND, backend.name)
            .putBoolean(KEY_CPU_DEFAULT_MIGRATION, true)
            .commit()) {
            "Failed to persist Supertonic backend setting"
        }
    }

    fun setChunkSettings(context: Context, mode: String, manualCap: Int) {
        check(prefs(context).edit()
            .putString(KEY_CHUNK_MODE, normalizeChunkMode(mode))
            .putInt(KEY_CHUNK_CAP, manualCap.coerceIn(MIN_CHUNK_CAP, MAX_CHUNK_CAP))
            .commit()) { "Failed to persist Supertonic chunk settings" }
    }


    fun setPreGeneration(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PREGEN, enabled).commit()) {
            "Failed to persist Supertonic pre-generation setting"
        }
    }

    fun setStreamingControls(context: Context, pregenQueue: Int, gapMinMs: Int, gapMaxMs: Int, trailingTrimMs: Int) {
        val minGap = gapMinMs.coerceIn(MIN_GAP_MS, MAX_GAP_MS)
        val maxGap = gapMaxMs.coerceIn(MIN_GAP_MS, MAX_GAP_MS).coerceAtLeast(minGap)
        check(prefs(context).edit()
            .putInt(KEY_PREGEN_QUEUE, pregenQueue.coerceIn(2, 3))
            .putInt(KEY_GAP_MIN, minGap)
            .putInt(KEY_GAP_MAX, maxGap)
            .putInt(KEY_TRAILING_TRIM, trailingTrimMs.coerceIn(MIN_TRAILING_TRIM_MS, MAX_TRAILING_TRIM_MS))
            .commit()) { "Failed to persist Supertonic streaming controls" }
    }

    fun setAllowNa(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ALLOW_NA, enabled).commit()) {
            "Failed to persist Supertonic mixed-language setting"
        }
    }

    fun setTestLanguage(context: Context, language: String) {
        val normalized = language.lowercase().ifBlank { "na" }
        check(prefs(context).edit().putString(KEY_TEST_LANGUAGE, normalized).commit()) {
            "Failed to persist Supertonic test language"
        }
    }

    private fun normalizeChunkMode(mode: String): String = when (mode) {
        CHUNK_CONSERVATIVE, CHUNK_BALANCED, CHUNK_LONG, CHUNK_MANUAL -> mode
        else -> CHUNK_BALANCED
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
