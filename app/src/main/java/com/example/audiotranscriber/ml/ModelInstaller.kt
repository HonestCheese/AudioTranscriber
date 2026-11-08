package com.example.audiotranscriber.ml

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Fully offline APK: every model and its notices are checked and copied from assets. */
object ModelInstaller {
    private val lock = Mutex()
    fun directory(context: Context) = File(context.noBackupFilesDir, "models-v1")
    private fun manifest(context: Context) = context.assets.open("bundled_model_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
    fun isReady(context: Context): Boolean = runCatching {
        val dir = directory(context)
        val expected = manifest(context)
        expected.keys().asSequence().all { name ->
            val file = File(dir, name)
            file.isFile && file.length() == expected.getJSONObject(name).getLong("size")
        }
    }.getOrDefault(false)

    suspend fun ensureBundledModels(context: Context, progress: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val expected = manifest(context)
            val names = expected.keys().asSequence().toList()
            val dir = directory(context).apply { mkdirs() }
            val needed = names.filter { !verified(File(dir, it), expected.getJSONObject(it)) }
            if (needed.isEmpty()) return@withLock
            val bytes = needed.sumOf { expected.getJSONObject(it).getLong("size") }
            check(dir.usableSpace > bytes + 100L * 1024 * 1024) {
                "Для подготовки встроенных моделей нужно ещё ${bytes / 1024 / 1024 + 100} МБ свободного места."
            }
            for ((index, name) in needed.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress("Подготовка встроенных моделей: ${index + 1} / ${needed.size}")
                val spec = expected.getJSONObject(name)
                val target = File(dir, name)
                target.parentFile!!.mkdirs()
                val temp = File(target.parentFile, target.name + ".unpacking")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    context.assets.open("offline_models/$name").use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                check(count <= spec.getLong("size")) { "Неверный размер встроенной модели: $name" }
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                            }
                        }
                    }
                    check(count == spec.getLong("size") && hex(digest.digest()) == spec.getString("sha256")) {
                        "Повреждена встроенная модель $name. Скачайте APK заново."
                    }
                    currentCoroutineContext().ensureActive()
                    check(temp.renameTo(target)) { "Не удалось сохранить модель $name" }
                } finally { temp.delete() }
            }
            File(dir, "ready").writeText("offline-v1")
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun verified(file: File, spec: JSONObject): Boolean {
        if (!file.isFile || file.length() != spec.getLong("size")) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return hex(digest.digest()) == spec.getString("sha256")
    }
    fun reverbModel(context: Context) = File(directory(context), "diarization/reverb/model.onnx")
    fun eres2netModel(context: Context) = File(directory(context), "diarization/eres2netv2.onnx")
    fun dpdfnetModel(context: Context) = File(directory(context), "denoiser/dpdfnet2.onnx")
    suspend fun ensurePipelineModels(context: Context, includeDenoiser: Boolean, progress: suspend (String) -> Unit) {
        ensureBundledModels(context, progress)
    }
    suspend fun ensureTitaNet(context: Context, progress: suspend (String) -> Unit): File {
        ensureBundledModels(context, progress)
        return File(directory(context), "enrollment/titanet-small.onnx")
    }
    suspend fun install(context: Context, uris: List<Uri>, progress: (String) -> Unit) {
        ensureBundledModels(context) { progress(it) }
    }
}
