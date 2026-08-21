package audio.soniqo.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Keeps the Soniqo and Reza2kn Supertonic bundles side-by-side. */
object ModelManager {
    private const val TAG = "SupertonicTTS"
    private const val MODEL_SET_FILENAME = "model-set.txt"
    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 2000L

    private data class ModelFile(val path: String, val estimate: Long)

    private data class ModelBundle(
        val baseUrl: String,
        val modelId: String,
        val modelVersion: Int,
        val dirName: String,
        val label: String,
        val layoutKey: String,
        val files: List<ModelFile>,
        val localOnly: Boolean = false,
    )

    private val commonFiles = listOf(
        ModelFile("tts.json", 20_000L),
        ModelFile("unicode_indexer.json", 278_000L),
        ModelFile("voice_styles/F1.json", 292_000L),
        ModelFile("voice_styles/F2.json", 292_000L),
        ModelFile("voice_styles/F3.json", 291_000L),
        ModelFile("voice_styles/F4.json", 292_000L),
        ModelFile("voice_styles/F5.json", 292_000L),
        ModelFile("voice_styles/M1.json", 292_000L),
        ModelFile("voice_styles/M2.json", 292_000L),
        ModelFile("voice_styles/M3.json", 290_000L),
        ModelFile("voice_styles/M4.json", 292_000L),
        ModelFile("voice_styles/M5.json", 292_000L),
    )

    private val soniqo = ModelBundle(
        baseUrl = "https://huggingface.co/soniqo/Supertonic-3-LiteRT/resolve/main/",
        modelId = "soniqo/Supertonic-3-LiteRT@main",
        modelVersion = 1,
        dirName = "supertonic-3-soniqo-litert",
        label = "Soniqo Supertonic-3 LiteRT",
        layoutKey = "fixed-T128-L64-four-litert",
        files = listOf(
            ModelFile("duration_predictor.tflite", 4_000_000L),
            ModelFile("text_encoder.tflite", 37_000_000L),
            ModelFile("vector_estimator.tflite", 245_000_000L),
            ModelFile("vocoder.tflite", 101_000_000L),
        ) + commonFiles,
    )

    private val soniqoFullFp16 = ModelBundle(
        baseUrl = "",
        modelId = "local/Soniqo-Supertonic-3-FULL-FP16-W16A16",
        modelVersion = 1,
        dirName = "supertonic-3-soniqo-full-fp16",
        label = "Soniqo FULL FP16 W16A16 (Experimental)",
        layoutKey = "fixed-T128-L64-full-fp16-w16a16-strict",
        files = listOf(
            ModelFile("duration_predictor.tflite", 1_880_376L),
            ModelFile("text_encoder.tflite", 17_994_852L),
            ModelFile("vector_estimator.tflite", 128_230_784L),
            ModelFile("vocoder.tflite", 50_793_256L),
            ModelFile("MODEL_MANIFEST.json", 1_870_960L),
        ) + commonFiles,
        localOnly = true,
    )

    private val reza = ModelBundle(
        baseUrl = "https://huggingface.co/Reza2kn/supertonic-3-litert/resolve/main/",
        modelId = "Reza2kn/supertonic-3-litert@main",
        modelVersion = 1,
        dirName = "supertonic-3-reza-litert",
        label = "Reza2kn Supertonic-3 INT4+INT8 Hybrid",
        layoutKey = "T320-dynamic-VE-L320-vocoder-int4-int8ve",
        files = listOf(
            // Do not download int4/vocoder.tflite: upstream marks it broken.
            ModelFile("int4/duration_predictor.tflite", 2_500_000L),
            ModelFile("int4/text_encoder.tflite", 12_700_000L),
            ModelFile("vector_estimator_int8.onnx", 65_500_000L),
            ModelFile("int8/vocoder.tflite", 26_100_000L),
        ) + commonFiles,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun bundle(model: TtsModel): ModelBundle = when (model) {
        TtsModel.SUPERTONIC -> soniqo
        TtsModel.SUPERTONIC_REZA -> reza
        TtsModel.SUPERTONIC_SONIQO_FULL_FP16 -> soniqoFullFp16
    }

    fun isLocalOnly(model: TtsModel): Boolean = bundle(model).localOnly

    fun modelLabel(model: TtsModel): String = bundle(model).label

    fun modelDir(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
    ): File = File(context.filesDir, bundle(model).dirName)

    fun estimatedSizeBytes(
        model: TtsModel = TtsModel.SUPERTONIC,
    ): Long = bundle(model).files.sumOf { it.estimate }

    fun areTtsModelsReady(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
    ): Boolean {
        val spec = bundle(model)
        val dir = modelDir(context, model)
        val version = File(dir, "version.txt")
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.toIntOrNull() ?: 0
        val set = File(dir, MODEL_SET_FILENAME)
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
        if (version != spec.modelVersion || set != modelSetKey(spec)) return false
        return spec.files.all { valid(File(dir, it.path)) }
    }

    suspend fun ensureTtsModels(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
        onProgress: ((completed: Long, total: Long, file: String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val spec = bundle(model)
        val dir = modelDir(context, model)
        dir.mkdirs()

        if (spec.localOnly) {
            if (areTtsModelsReady(context, model)) {
                return@withContext dir.absolutePath
            }
            throw IOException(
                "${spec.label} is a local model. Use 'FULL FP16 ZIP 가져오기' in the app first."
            )
        }

        val versionFile = File(dir, "version.txt")
        val setFile = File(dir, MODEL_SET_FILENAME)
        val stale =
            versionFile.takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.toIntOrNull() != spec.modelVersion ||
            setFile.takeIf { it.exists() }
                ?.readText()
                ?.trim() != modelSetKey(spec)

        if (stale) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            dir.mkdirs()
        }

        val pending = spec.files.filterNot { valid(File(dir, it.path)) }
        val total = pending.sumOf { it.estimate }.coerceAtLeast(1L)
        var completed = 0L
        onProgress?.invoke(0L, total, "Preparing")

        for (file in pending) {
            val dest = File(dir, file.path)
            dest.parentFile?.mkdirs()
            val url = spec.baseUrl + file.path
            Log.i(TAG, "Downloading $url")

            download(url, dest) { bytes, actualTotal ->
                val fileTotal = maxOf(actualTotal, file.estimate)
                onProgress?.invoke(
                    (completed + bytes).coerceAtMost(total),
                    total,
                    file.path,
                )
                if (fileTotal > 0 && bytes == fileTotal) {
                    onProgress?.invoke(
                        (completed + fileTotal).coerceAtMost(total),
                        total,
                        file.path,
                    )
                }
            }

            completed =
                (completed + maxOf(dest.length(), file.estimate)).coerceAtMost(total)
        }

        versionFile.writeText(spec.modelVersion.toString())
        setFile.writeText(modelSetKey(spec))

        if (!areTtsModelsReady(context, model)) {
            throw IOException("${spec.label} model verification failed. Tap retry.")
        }
        dir.absolutePath
    }

    private fun modelSetKey(spec: ModelBundle): String =
        "v${spec.modelVersion}|${spec.modelId}|${spec.layoutKey}"

    private fun valid(file: File): Boolean =
        file.exists() && file.isFile && file.length() >= 1024L

    private fun download(
        url: String,
        dest: File,
        onBytes: (Long, Long) -> Unit,
    ) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var last: IOException? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val existing = if (tmp.exists()) tmp.length() else 0L
                val request = Request.Builder().url(url).apply {
                    if (existing > 0L) header("Range", "bytes=$existing-")
                }.build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        throw IOException("HTTP ${response.code} for $url")
                    }
                    val body =
                        response.body ?: throw IOException("Empty response for $url")
                    val length = body.contentLength()
                    val range = response.header("Content-Range")
                    val actualTotal = if (response.code == 206) {
                        range?.substringAfterLast('/')?.toLongOrNull()
                            ?: (existing + length).coerceAtLeast(existing)
                    } else {
                        length
                    }

                    val append = response.code == 206 && existing > 0L
                    if (!append && existing > 0L) tmp.delete()

                    FileOutputStream(tmp, append).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            var done = if (append) existing else 0L
                            onBytes(done, actualTotal)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                done += count
                                onBytes(done, actualTotal)
                            }
                        }
                    }

                    if (actualTotal > 0L && tmp.length() != actualTotal) {
                        throw IOException(
                            "Incomplete download: ${tmp.length()} / $actualTotal"
                        )
                    }

                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    return
                }
            } catch (e: IOException) {
                last = e
                Log.e(
                    TAG,
                    "Download attempt $attempt failed: ${dest.name}: ${e.message}"
                )
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                }
            }
        }

        throw IOException(
            "Download failed after $MAX_RETRIES attempts: ${last?.message}",
            last,
        )
    }
}
