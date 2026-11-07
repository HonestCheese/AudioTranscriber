package com.example.audiotranscriber.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipInputStream

/**
 * GigaAM weights are imported from the existing model ZIP.
 * Reverb, ERes2NetV2 and DPDFNet2 are downloaded once from the official
 * sherpa-onnx releases; all inference remains on-device afterwards.
 */
object ModelInstaller {
    private const val ERES2NET_SIZE = 71_441_526L
    private const val DPDFNET_SIZE = 10_249_356L
    private const val REVERB_ARCHIVE_SIZE = 10_918_585L

    private const val ERES2NET_SHA256 =
        "bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239"
    private const val DPDFNET_SHA256 =
        "ce35d6025fc71df0ef10d1540e1b7916837bbfe5f6896deb744508d2cad487a9"

    private const val REVERB_ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "speaker-segmentation-models/sherpa-onnx-reverb-diarization-v1.tar.bz2"
    private const val ERES2NET_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "speaker-recongition-models/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx"
    private const val DPDFNET_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "speech-enhancement-models/dpdfnet2.onnx"

    fun directory(context: Context) = File(context.noBackupFilesDir, "models-v1")

    private fun manifest(context: Context) =
        context.assets.open("model_manifest.json").bufferedReader().use {
            JSONObject(it.readText())
        }

    /** Checks the three GigaAM files imported from the existing model archive. */
    fun isReady(context: Context): Boolean = runCatching {
        val dir = directory(context)
        if (!File(dir, "ready").isFile) return@runCatching false
        val expected = manifest(context)
        expected.keys().asSequence().all { name ->
            File(dir, name).length() == expected.getJSONObject(name).getLong("size")
        }
    }.getOrDefault(false)

    fun reverbModel(context: Context) =
        File(directory(context), "diarization/reverb/model.onnx")

    fun eres2netModel(context: Context) =
        File(directory(context), "diarization/eres2netv2.onnx")

    fun dpdfnetModel(context: Context) =
        File(directory(context), "denoiser/dpdfnet2.onnx")

    /**
     * Download only what the selected processing path needs.
     * Files are verified and kept in private app storage for offline use.
     */
    suspend fun ensurePipelineModels(
        context: Context,
        includeDenoiser: Boolean,
        progress: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        check(isReady(context)) {
            "Сначала импортируйте архив с моделями GigaAM."
        }

        val dir = directory(context)
        val additionalBytes = REVERB_ARCHIVE_SIZE + ERES2NET_SIZE +
            (if (includeDenoiser) DPDFNET_SIZE else 0L) + 50L * 1024 * 1024
        check(dir.usableSpace > additionalBytes) {
            "Для дополнительных моделей диаризации нужно ещё около 150 МБ свободного места."
        }
        val reverb = reverbModel(context)
        val reverbLicense = File(reverb.parentFile, "LICENSE")
        val reverbNotice = File(reverb.parentFile, "NOTICE")
        if (!reverb.isFile || reverb.length() <= 1_000_000L ||
            !reverbLicense.isFile || !reverbNotice.isFile
        ) {
            val archive = File(dir, "reverb-diarization-v1.tar.bz2")
            downloadFile(
                REVERB_ARCHIVE_URL,
                archive,
                REVERB_ARCHIVE_SIZE,
                expectedSha256 = null,
                label = "Reverb v1",
                progress = progress
            )
            extractReverbArchive(archive, reverb.parentFile!!)
            archive.delete()
        }

        val embedding = eres2netModel(context)
        if (!isVerifiedFile(embedding, ERES2NET_SIZE, ERES2NET_SHA256)) {
            downloadFile(
                ERES2NET_URL,
                embedding,
                ERES2NET_SIZE,
                ERES2NET_SHA256,
                "3D-Speaker ERes2NetV2",
                progress
            )
        }

        if (includeDenoiser) {
            val denoiser = dpdfnetModel(context)
            if (!isVerifiedFile(denoiser, DPDFNET_SIZE, DPDFNET_SHA256)) {
                downloadFile(
                    DPDFNET_URL,
                    denoiser,
                    DPDFNET_SIZE,
                    DPDFNET_SHA256,
                    "DPDFNet2",
                    progress
                )
            }
        }

        check(reverb.isFile && reverb.length() > 1_000_000L) {
            "Не удалось установить модель Reverb v1."
        }
        check(reverbLicense.isFile && reverbLicense.length() > 0L) {
            "Не удалось сохранить лицензию Reverb v1."
        }
        check(reverbNotice.isFile && reverbNotice.length() > 0L) {
            "Не удалось сохранить NOTICE для модели Reverb v1."
        }
        check(isVerifiedFile(embedding, ERES2NET_SIZE, ERES2NET_SHA256)) {
            "Файл ERes2NetV2 не прошёл проверку целостности."
        }
        if (includeDenoiser) {
            check(isVerifiedFile(dpdfnetModel(context), DPDFNET_SIZE, DPDFNET_SHA256)) {
                "Файл DPDFNet2 не прошёл проверку целостности."
            }
        }
    }

    private fun isVerifiedFile(file: File, size: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        return actual == sha256
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSize: Long,
        expectedSha256: String?,
        label: String,
        progress: suspend (String) -> Unit
    ) {
        val coroutineContext = currentCoroutineContext()
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".download")
        temporary.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Не удалось загрузить $label: HTTP ${connection.responseCode}"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastPercent = -1
            val buffer = ByteArray(128 * 1024)

            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= expectedSize) {
                            "Размер загружаемой модели $label превышает ожидаемый."
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)

                        val percent = (downloaded * 100 / expectedSize).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            progress("Загрузка $label: $percent%")
                        }
                    }
                    output.flush()
                }
            }

            require(downloaded == expectedSize) {
                "Загрузка $label неполная: $downloaded из $expectedSize байт."
            }

            val actualHash = digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
            if (expectedSha256 != null) {
                require(actualHash == expectedSha256) {
                    "Контрольная сумма $label не совпала."
                }
            }

            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) {
                "Не удалось сохранить файл $label."
            }
            progress("$label загружена")
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private suspend fun extractReverbArchive(archive: File, outputDirectory: File) {
        val coroutineContext = currentCoroutineContext()
        outputDirectory.mkdirs()
        val model = File(outputDirectory, "model.onnx")
        val license = File(outputDirectory, "LICENSE")
        model.delete()

        TarArchiveInputStream(
            BZip2CompressorInputStream(
                BufferedInputStream(FileInputStream(archive))
            )
        ).use { tar ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextTarEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val destination = when (name) {
                    "model.onnx" -> model
                    "LICENSE" -> license
                    else -> null
                } ?: continue

                FileOutputStream(destination).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = tar.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }

        check(model.isFile && model.length() > 1_000_000L) {
            "В архиве Reverb v1 не найден model.onnx."
        }
        check(license.isFile && license.length() > 0L) {
            "В архиве Reverb v1 не найден файл лицензии LICENSE."
        }
        File(outputDirectory, "NOTICE").writeText(
            "Licensed by Rev under the Rev Model Non-Production License."
        )
    }

    suspend fun install(
        context: Context,
        uris: List<Uri>,
        progress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Выберите ZIP или все семь частей архива." }

        fun displayName(uri: Uri): String =
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()

        val sorted = uris.map { displayName(it) to it }.sortedBy { it.first }
        if (sorted.size > 1) {
            require(
                sorted.size == 7 &&
                    sorted.map { it.first }.mapIndexed { index, name ->
                        name == "android_models.part%02d".format(index + 1)
                    }.all { it }
            ) {
                "Выберите ровно android_models.part01 … android_models.part07."
            }
        }

        val expected = manifest(context)
        val requiredBytes = expected.keys().asSequence()
            .sumOf { expected.getJSONObject(it).getLong("size") }
        require(context.noBackupFilesDir.usableSpace > requiredBytes + 200L * 1024 * 1024) {
            "Для установки GigaAM и дополнительных моделей нужно около 1.1 ГБ свободного места."
        }

        val staging = File(context.noBackupFilesDir, "models-installing")
        staging.deleteRecursively()
        staging.mkdirs()
        val streams = mutableListOf<InputStream>()

        try {
            sorted.forEach { (_, uri) ->
                streams += context.contentResolver.openInputStream(uri)
                    ?: error("Не удалось открыть архив.")
            }

            val found = mutableSetOf<String>()
            ZipInputStream(
                SequenceInputStream(Collections.enumeration(streams))
            ).use { zip ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && expected.has(name)) {
                        require(found.add(name)) {
                            "Повторяющийся файл в архиве: $name"
                        }
                        val spec = expected.getJSONObject(name)
                        val target = File(staging, name)
                        target.parentFile!!.mkdirs()
                        progress("Установка GigaAM: ${found.size} / ${expected.length()}")

                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        FileOutputStream(target).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= spec.getLong("size")) {
                                    "Неверный размер модели: $name"
                                }
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                            }
                        }
                        val hash = digest.digest().joinToString("") {
                            "%02x".format(it.toInt() and 255)
                        }
                        require(
                            count == spec.getLong("size") &&
                                hash == spec.getString("sha256")
                        ) {
                            "Модель повреждена или отличается от экспорта: $name"
                        }
                    }
                    zip.closeEntry()
                }
            }

            require(found.size == expected.length()) {
                "В архиве найдены не все три модели GigaAM. Выберите полный ZIP или все семь частей."
            }
            currentCoroutineContext().ensureActive()
            File(staging, "ready").writeText("v2")

            val target = directory(context)
            val backup = File(context.noBackupFilesDir, "models-previous")
            backup.deleteRecursively()
            if (target.exists()) {
                check(target.renameTo(backup)) { "Не удалось обновить модели." }
            }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                error("Не удалось установить модели.")
            }
            backup.deleteRecursively()
        } finally {
            streams.forEach { runCatching { it.close() } }
            staging.deleteRecursively()
        }
    }
}
