package com.supertonic.tts

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object FullFp16ZipImporter {
    data class InstallResult(
        val installedBytes: Long,
    )

    private const val MODEL_DIR = "supertonic-3-soniqo-full-fp16"
    private const val MODEL_VERSION = 1
    private const val MODEL_SET =
        "v1|local/Soniqo-Supertonic-3-FULL-FP16-W16A16|" +
            "fixed-T128-L64-full-fp16-w16a16-strict"

    private const val MAX_EXTRACTED_BYTES = 260L * 1024L * 1024L

    private val requiredFiles = setOf(
        "duration_predictor.tflite",
        "text_encoder.tflite",
        "vector_estimator.tflite",
        "vocoder.tflite",
        "MODEL_MANIFEST.json",
        "tts.json",
        "unicode_indexer.json",
        "voice_styles/F1.json",
        "voice_styles/F2.json",
        "voice_styles/F3.json",
        "voice_styles/F4.json",
        "voice_styles/F5.json",
        "voice_styles/M1.json",
        "voice_styles/M2.json",
        "voice_styles/M3.json",
        "voice_styles/M4.json",
        "voice_styles/M5.json",
    )

    private val graphFiles = mapOf(
        "duration_predictor" to "duration_predictor.tflite",
        "text_encoder" to "text_encoder.tflite",
        "vector_estimator" to "vector_estimator.tflite",
        "vocoder" to "vocoder.tflite",
    )

    fun install(context: Context, uri: Uri): InstallResult {
        val tempDir = File(
            context.filesDir,
            ".${MODEL_DIR}-import-${System.nanoTime()}",
        )
        val targetDir = File(context.filesDir, MODEL_DIR)
        val backupDir = File(context.filesDir, ".${MODEL_DIR}-backup")

        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            val extractedBytes = extractAndValidateArchive(context, uri, tempDir)
            validateManifestAndHashes(tempDir)

            File(tempDir, "version.txt").writeText(
                MODEL_VERSION.toString(),
                Charsets.UTF_8,
            )
            File(tempDir, "model-set.txt").writeText(
                MODEL_SET,
                Charsets.UTF_8,
            )

            transactionalReplace(
                tempDir = tempDir,
                targetDir = targetDir,
                backupDir = backupDir,
            )

            return InstallResult(installedBytes = extractedBytes)
        } catch (t: Throwable) {
            if (tempDir.exists()) tempDir.deleteRecursively()
            throw t
        }
    }

    private fun extractAndValidateArchive(
        context: Context,
        uri: Uri,
        tempDir: File,
    ): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("선택한 ZIP을 열 수 없습니다.")

        val found = mutableSetOf<String>()
        var totalBytes = 0L
        val tempCanonical = tempDir.canonicalFile
        val tempPrefix = tempCanonical.path + File.separator

        ZipInputStream(BufferedInputStream(input, 256 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val rawName = entry.name.replace('\\', '/')
                val name = rawName.removePrefix("./").trimStart('/')

                if (name.isBlank()) {
                    zip.closeEntry()
                    continue
                }

                val parts = name.split('/').filter { it.isNotEmpty() }
                if (
                    rawName.startsWith("/") ||
                    rawName.startsWith("\\") ||
                    parts.any { it == ".." }
                ) {
                    throw IOException("위험한 ZIP 경로가 포함되어 있습니다: ${entry.name}")
                }

                if (entry.isDirectory) {
                    if (name != "voice_styles" && name != "voice_styles/") {
                        throw IOException("예상하지 못한 ZIP 디렉터리: $name")
                    }
                    File(tempDir, name).mkdirs()
                    zip.closeEntry()
                    continue
                }

                if (name !in requiredFiles) {
                    throw IOException("예상하지 못한 ZIP 파일: $name")
                }
                if (!found.add(name)) {
                    throw IOException("ZIP에 중복 파일이 있습니다: $name")
                }

                val output = File(tempDir, name).canonicalFile
                if (
                    output.path != tempCanonical.path &&
                    !output.path.startsWith(tempPrefix)
                ) {
                    throw IOException("ZIP 경로가 설치 폴더를 벗어납니다: $name")
                }

                output.parentFile?.mkdirs()
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        totalBytes += read
                        if (totalBytes > MAX_EXTRACTED_BYTES) {
                            throw IOException(
                                "압축 해제 크기가 제한을 초과했습니다 " +
                                    "(최대 ${MAX_EXTRACTED_BYTES / 1024 / 1024} MiB)."
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                }

                if (output.length() < 1024L) {
                    throw IOException("파일이 비정상적으로 작습니다: $name")
                }
                zip.closeEntry()
            }
        }

        val missing = requiredFiles - found
        if (missing.isNotEmpty()) {
            throw IOException(
                "필수 파일이 없습니다: " +
                    missing.sorted().joinToString(", ")
            )
        }

        return totalBytes
    }

    private fun validateManifestAndHashes(dir: File) {
        val manifestFile = File(dir, "MODEL_MANIFEST.json")
        val manifest = runCatching {
            JSONObject(manifestFile.readText(Charsets.UTF_8))
        }.getOrElse {
            throw IOException(
                "MODEL_MANIFEST.json을 읽을 수 없습니다: ${it.message}",
                it,
            )
        }

        if (manifest.optString("precision") != "FULL_FP16") {
            throw IOException(
                "FULL_FP16 모델이 아닙니다: precision=" +
                    manifest.optString("precision", "(missing)")
            )
        }
        if (!manifest.optBoolean("no_fp32_graph_fallback", false)) {
            throw IOException(
                "manifest가 no_fp32_graph_fallback=true를 보장하지 않습니다."
            )
        }
        if (!manifest.optBoolean("no_fp32_compute_island", false)) {
            throw IOException(
                "manifest가 no_fp32_compute_island=true를 보장하지 않습니다."
            )
        }

        val graphs = manifest.optJSONObject("graphs")
            ?: throw IOException("manifest에 graphs 정보가 없습니다.")

        for ((graphName, fileName) in graphFiles) {
            val graph = graphs.optJSONObject(graphName)
                ?: throw IOException("manifest에 $graphName 정보가 없습니다.")
            val expected = graph.optString("sha256").lowercase()
            if (expected.length != 64) {
                throw IOException("$graphName SHA-256 정보가 잘못되었습니다.")
            }

            val file = File(dir, fileName)
            val actual = sha256(file)
            if (actual != expected) {
                throw IOException(
                    "$fileName SHA-256 불일치\\n" +
                        "expected=$expected\\n" +
                        "actual=$actual"
                )
            }
        }
    }

    private fun transactionalReplace(
        tempDir: File,
        targetDir: File,
        backupDir: File,
    ) {
        if (backupDir.exists() && !backupDir.deleteRecursively()) {
            throw IOException("기존 임시 백업 폴더를 삭제할 수 없습니다.")
        }

        var oldMoved = false
        if (targetDir.exists()) {
            if (!targetDir.renameTo(backupDir)) {
                throw IOException(
                    "기존 FULL FP16 모델을 백업 위치로 이동할 수 없습니다."
                )
            }
            oldMoved = true
        }

        try {
            if (!tempDir.renameTo(targetDir)) {
                throw IOException(
                    "검증된 FULL FP16 모델을 최종 위치로 이동할 수 없습니다."
                )
            }
            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            }
        } catch (t: Throwable) {
            if (!targetDir.exists() && oldMoved && backupDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            throw t
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
