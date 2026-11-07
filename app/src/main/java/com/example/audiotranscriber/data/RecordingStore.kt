package com.example.audiotranscriber.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns local audio copies, display names and the user-defined list order. */
data class SavedRecording(val id: Long, val name: String, val fileName: String)

class RecordingStore(private val context: Context) {
    private val folder = File(context.filesDir, "saved_recordings").apply { mkdirs() }
    private val index = File(folder, "index.json")

    fun loadAll(): List<SavedRecording> {
        val saved = runCatching {
            val a = JSONArray(index.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SavedRecording(o.getLong("id"), o.getString("name"), o.getString("fileName"))
            }
        }.getOrDefault(emptyList()).filter { File(folder, it.fileName).isFile }
        // Include recordings created by previous versions of the app.
        val indexedFiles = saved.map { it.fileName }.toSet()
        val legacy = context.filesDir.listFiles { f -> f.isFile && f.name.startsWith("recording_") && f.extension == "m4a" }
            .orEmpty().mapNotNull { old ->
                runCatching {
                    val target = File(folder, old.name)
                    if (!target.exists()) old.copyTo(target)
                    SavedRecording(old.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(), old.name, target.name)
                }.getOrNull()
            }.filterNot { it.fileName in indexedFiles }
        val all = saved + legacy
        if (legacy.isNotEmpty()) persist(all)
        return all
    }

    fun add(uri: Uri, originalName: String): SavedRecording {
        val id = System.currentTimeMillis()
        val extension = originalName.substringAfterLast('.', "m4a").lowercase()
            .takeIf { it in setOf("m4a", "mp3", "wav", "ogg", "flac", "aac", "mp4", "opus") } ?: "audio"
        val timestamp = SimpleDateFormat("yy-MM-dd HH:mm", Locale.US).format(Date(id))
        val displayName = timestamp
        val target = File(folder, "audio_${id}_${System.nanoTime()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: error("Не удалось открыть аудиофайл")
            val item = SavedRecording(id, displayName, target.name)
            persist(listOf(item) + loadAll())
            return item
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun uri(item: SavedRecording): Uri = Uri.fromFile(File(folder, item.fileName))

    fun rename(id: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Имя не может быть пустым" }
        persist(loadAll().map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun move(id: Long, direction: Int) {
        val items = loadAll().toMutableList()
        val from = items.indexOfFirst { it.id == id }
        val to = (from + direction).coerceIn(0, items.lastIndex)
        if (from >= 0 && to != from) {
            val moved = items.removeAt(from)
            items.add(to, moved)
            persist(items)
        }
    }

    fun delete(id: Long) {
        val items = loadAll()
        val item = items.firstOrNull { it.id == id } ?: return
        persist(items.filterNot { it.id == id })
        File(folder, item.fileName).delete()
    }

    private fun persist(items: List<SavedRecording>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("fileName", it.fileName)) }
        val temp = File(folder, "index.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(index)) { temp.copyTo(index, overwrite = true); temp.delete() }
    }
}