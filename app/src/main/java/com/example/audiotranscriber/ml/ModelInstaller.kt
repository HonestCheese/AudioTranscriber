package com.example.audiotranscriber.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipInputStream

/** One-time offline install of the original Colab ZIP, or its seven byte-split parts. */
object ModelInstaller {
    fun directory(context: Context) = File(context.noBackupFilesDir, "models-v1")
    private fun manifest(context: Context) = context.assets.open("model_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
    fun isReady(context: Context): Boolean {
        val dir = directory(context)
        if (!File(dir, "ready").isFile) return false
        val expected = manifest(context)
        return expected.keys().asSequence().all { name -> File(dir, name).length() == expected.getJSONObject(name).getLong("size") }
    }

    /** ECAPA is bundled in the APK; older ASR archives remain accepted unchanged. */
    suspend fun prepareEcapa(context: Context): File = withContext(Dispatchers.IO) {
        val destination = File(context.noBackupFilesDir, "ecapa-any-length-v1.onnx")
        val asset = "ecapa/ecapa-any-length.onnx"
        if (destination.isFile && destination.length() > 50L * 1024 * 1024) return@withContext destination
        val temporary = File(context.noBackupFilesDir, "ecapa-installing.tmp")
        try {
            context.assets.open(asset).use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.length() > 50L * 1024 * 1024) { "ECAPA-TDNN model missing or incomplete" }
            currentCoroutineContext().ensureActive()
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Unable to install ECAPA-TDNN model" }
            destination
        } finally { temporary.delete() }
    }

    suspend fun install(context: Context, uris: List<Uri>, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Выберите ZIP или все семь частей" }
        fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val sorted = uris.map { displayName(it) to it }.sortedBy { it.first }
        if (sorted.size > 1) {
            require(sorted.size == 7 && sorted.map { it.first }.mapIndexed { i, name -> name == "android_models.part%02d".format(i + 1) }.all { it }) {
                "Выберите ровно android_models.part01 … android_models.part07"
            }
        }
        val expected = manifest(context)
        val requiredBytes = expected.keys().asSequence().sumOf { expected.getJSONObject(it).getLong("size") }
        require(context.noBackupFilesDir.usableSpace > requiredBytes + 100L * 1024 * 1024) { "Для установки моделей нужен 1 ГБ свободного места" }
        val staging = File(context.noBackupFilesDir, "models-installing")
        staging.deleteRecursively()
        staging.mkdirs()
        val streams = mutableListOf<InputStream>()
        try {
            sorted.forEach { (_, uri) -> streams += (context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть архив")) }
            val found = mutableSetOf<String>()
            ZipInputStream(SequenceInputStream(Collections.enumeration(streams))).use { zip ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && expected.has(name)) {
                        require(found.add(name)) { "Повторяющийся файл в архиве: $name" }
                        val spec = expected.getJSONObject(name)
                        val target = File(staging, name)
                        target.parentFile!!.mkdirs()
                        progress("Установка моделей: ${found.size} / ${expected.length()}")
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        target.outputStream().use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= spec.getLong("size")) { "Неверный размер модели: $name" }
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                            }
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(count == spec.getLong("size") && hash == spec.getString("sha256")) { "Модель повреждена или отличается от переданного экспорта: $name" }
                    }
                    zip.closeEntry()
                }
            }
            require(found.size == expected.length()) { "В архиве найдены не все пять моделей. Выберите полный models.zip или все семь частей." }
            currentCoroutineContext().ensureActive()
            File(staging, "ready").writeText("v1")
            val target = directory(context)
            val backup = File(context.noBackupFilesDir, "models-previous")
            backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "Не удалось обновить модели" }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                error("Не удалось установить модели")
            }
            backup.deleteRecursively()
        } finally {
            streams.forEach { runCatching { it.close() } }
            staging.deleteRecursively()
        }
    }
}

