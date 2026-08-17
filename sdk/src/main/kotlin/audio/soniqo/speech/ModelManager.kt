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

/** Downloads only the Soniqo Supertonic-3 LiteRT TTS bundle. */
object ModelManager {
    private const val TAG = "SupertonicTTS"
    private const val BASE_URL = "https://huggingface.co/soniqo/Supertonic-3-LiteRT/resolve/main/"
    private const val MODEL_ID = "soniqo/Supertonic-3-LiteRT@main"
    private const val MODEL_VERSION = 1
    private const val MODEL_SET_FILENAME = "model-set.txt"
    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 2000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val files = listOf(
        ModelFile("duration_predictor.tflite", 4_000_000L),
        ModelFile("text_encoder.tflite", 37_000_000L),
        ModelFile("vector_estimator.tflite", 245_000_000L),
        ModelFile("vocoder.tflite", 101_000_000L),
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

    private data class ModelFile(val path: String, val estimate: Long)

    fun modelDir(context: Context): File = File(context.filesDir, "supertonic-3-soniqo-litert")

    fun estimatedSizeBytes(): Long = files.sumOf { it.estimate }

    fun areTtsModelsReady(context: Context): Boolean {
        val dir = modelDir(context)
        val version = File(dir, "version.txt").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val set = File(dir, MODEL_SET_FILENAME).takeIf { it.exists() }?.readText()?.trim()
        if (version != MODEL_VERSION || set != modelSetKey()) return false
        return files.all { valid(File(dir, it.path)) }
    }

    suspend fun ensureTtsModels(
        context: Context,
        onProgress: ((completed: Long, total: Long, file: String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        dir.mkdirs()
        val versionFile = File(dir, "version.txt")
        val setFile = File(dir, MODEL_SET_FILENAME)
        val stale = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() != MODEL_VERSION ||
            setFile.takeIf { it.exists() }?.readText()?.trim() != modelSetKey()
        if (stale) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            dir.mkdirs()
        }

        val pending = files.filterNot { valid(File(dir, it.path)) }
        val total = pending.sumOf { it.estimate }.coerceAtLeast(1L)
        var completed = 0L
        onProgress?.invoke(0L, total, "Preparing")

        for (file in pending) {
            val dest = File(dir, file.path)
            dest.parentFile?.mkdirs()
            val url = BASE_URL + file.path
            Log.i(TAG, "Downloading $url")
            download(url, dest) { bytes, actualTotal ->
                val fileTotal = maxOf(actualTotal, file.estimate)
                onProgress?.invoke((completed + bytes).coerceAtMost(total), total, file.path)
                if (fileTotal > 0 && bytes == fileTotal) {
                    onProgress?.invoke((completed + fileTotal).coerceAtMost(total), total, file.path)
                }
            }
            completed = (completed + maxOf(dest.length(), file.estimate)).coerceAtMost(total)
        }

        versionFile.writeText(MODEL_VERSION.toString())
        setFile.writeText(modelSetKey())
        if (!areTtsModelsReady(context)) {
            throw IOException("Soniqo Supertonic-3 LiteRT model verification failed. Tap retry.")
        }
        dir.absolutePath
    }

    private fun modelSetKey() = "v$MODEL_VERSION|$MODEL_ID|four-litert-graphs"

    private fun valid(file: File): Boolean = file.exists() && file.isFile && file.length() >= 1024L

    private fun download(url: String, dest: File, onBytes: (Long, Long) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var last: IOException? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val existing = if (tmp.exists()) tmp.length() else 0L
                val req = Request.Builder().url(url).apply {
                    if (existing > 0L) header("Range", "bytes=$existing-")
                }.build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) throw IOException("HTTP ${response.code} for $url")
                    val body = response.body ?: throw IOException("Empty response for $url")
                    val length = body.contentLength()
                    val range = response.header("Content-Range")
                    val total = if (response.code == 206) {
                        range?.substringAfterLast('/')?.toLongOrNull() ?: (existing + length).coerceAtLeast(existing)
                    } else length
                    val append = response.code == 206 && existing > 0L
                    if (!append && existing > 0L) tmp.delete()
                    FileOutputStream(tmp, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            var done = if (append) existing else 0L
                            onBytes(done, total)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                onBytes(done, total)
                            }
                        }
                    }
                    if (total > 0L && tmp.length() != total) throw IOException("Incomplete download: ${tmp.length()} / $total")
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    return
                }
            } catch (e: IOException) {
                last = e
                Log.e(TAG, "Download attempt $attempt failed: ${dest.name}: ${e.message}")
                if (attempt < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
        throw IOException("Download failed after $MAX_RETRIES attempts: ${last?.message}", last)
    }
}
