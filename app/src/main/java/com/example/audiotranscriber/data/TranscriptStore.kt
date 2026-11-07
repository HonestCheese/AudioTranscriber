package com.example.audiotranscriber.data

import android.content.Context
import com.example.audiotranscriber.model.TranscriptSegment
import com.example.audiotranscriber.model.WordSpeakerEvidence
import com.example.audiotranscriber.model.VoiceCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ProcessingTimings(
    val audioDecodeMs: Long,
    val melFeaturesMs: Long,
    val gigaamMs: Long,
    val diarizationMs: Long,
    val mergeMs: Long,
    val saveMs: Long = 0L,
    val totalMs: Long,
    val contextLlmMs: Long = 0L
)

data class SavedTranscript(
    val id: Long,
    val fileName: String,
    val audioUri: String,
    val segments: List<TranscriptSegment>,
    val speakerNames: Map<Int, String>,
    val engine: String = "gigaam-v3-reverb-titanet-stage01",
    val processingTimings: ProcessingTimings? = null,
    val wordEvidence: List<WordSpeakerEvidence> = emptyList()
)

class TranscriptStore(private val context: Context) {
    private val folder get() = File(context.filesDir, "transcripts").apply { mkdirs() }
    private val orderFile get() = File(folder, "order.json")

    fun loadAll(): List<SavedTranscript> {
        val all = folder.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file -> runCatching { decode(JSONObject(file.readText())) }.getOrNull() }
            .orEmpty()
        if (all.isEmpty()) return emptyList()

        val orderedIds = runCatching {
            val array = JSONArray(orderFile.readText())
            (0 until array.length()).map { array.getLong(it) }
        }.getOrDefault(emptyList())

        val byId = all.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        val extras = all.filterNot { it.id in orderedIds }.sortedByDescending { it.id }
        return extras + ordered
    }

    fun save(transcript: SavedTranscript) {
        val root = JSONObject()
            .put("id", transcript.id)
            .put("fileName", transcript.fileName)
            .put("audioUri", transcript.audioUri)
            .put("engine", transcript.engine)
        transcript.processingTimings?.let { timings ->
            root.put("processingTimings", JSONObject()
                .put("audioDecodeMs", timings.audioDecodeMs)
                .put("melFeaturesMs", timings.melFeaturesMs)
                .put("gigaamMs", timings.gigaamMs)
                .put("diarizationMs", timings.diarizationMs)
                .put("contextLlmMs", timings.contextLlmMs)
                .put("mergeMs", timings.mergeMs)
                .put("saveMs", timings.saveMs)
                .put("totalMs", timings.totalMs))
        }
        val names = JSONObject()
        transcript.speakerNames.forEach { (id, name) -> names.put(id.toString(), name) }
        root.put("speakerNames", names)
        val segments = JSONArray()
        transcript.segments.forEach {
            segments.put(JSONObject().put("speakerId", it.speakerId)
                .put("startMs", it.startMs).put("endMs", it.endMs).put("text", it.text))
        }
        root.put("segments", segments)
        root.put("wordEvidence", encodeEvidence(transcript.wordEvidence))
        val temp = File(folder, "${transcript.id}.tmp")
        temp.writeText(root.toString())
        val target = File(folder, "${transcript.id}.json")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun rename(id: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Имя не может быть пустым" }
        val transcript = loadAll().firstOrNull { it.id == id } ?: return
        save(transcript.copy(fileName = clean))
    }

    /** Move an item by a relative number of positions and persist the new order. */
    fun move(id: Long, direction: Int) {
        val items = loadAll().toMutableList()
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + direction).coerceIn(0, items.lastIndex)
        if (to == from) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        val order = JSONArray()
        items.forEach { order.put(it.id) }
        orderFile.writeText(order.toString())
    }

    /** Export the public interchange format requested by the project. */
    fun exportJson(transcript: SavedTranscript): String {
        val items = JSONArray()
        transcript.segments.forEach { segment ->
            val speakerId = if (segment.speakerId < 0) "speaker_unknown" else "speaker_${segment.speakerId}"
            items.put(JSONObject()
                .put("speaker_id", speakerId)
                .put("start_ms", segment.startMs)
                .put("end_ms", segment.endMs)
                .put("text", segment.text))
        }
        return JSONObject().put("transcript", items)
            .put("word_metadata", encodeEvidence(transcript.wordEvidence)).toString(2)
    }

    fun delete(id: Long) {
        File(folder, "$id.json").delete()
        val remaining = loadAll()
        val order = JSONArray()
        remaining.forEach { order.put(it.id) }
        orderFile.writeText(order.toString())
    }

    private fun decode(json: JSONObject): SavedTranscript {
        val namesJson = json.optJSONObject("speakerNames") ?: JSONObject()
        val names = mutableMapOf<Int, String>()
        val keys = namesJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            key.toIntOrNull()?.let { names[it] = namesJson.getString(key) }
        }
        val array = json.getJSONArray("segments")
        val segments = (0 until array.length()).map { index ->
            val segment = array.getJSONObject(index)
            TranscriptSegment(segment.getInt("speakerId"), segment.getInt("startMs"),
                segment.getInt("endMs"), segment.getString("text"))
        }
        val timingJson = json.optJSONObject("processingTimings")
        val timings = timingJson?.let {
            ProcessingTimings(
                audioDecodeMs = it.optLong("audioDecodeMs"),
                melFeaturesMs = it.optLong("melFeaturesMs"),
                gigaamMs = it.optLong("gigaamMs"),
                diarizationMs = it.optLong("diarizationMs"),
                contextLlmMs = it.optLong("contextLlmMs"),
                mergeMs = it.optLong("mergeMs"),
                saveMs = it.optLong("saveMs"),
                totalMs = it.optLong("totalMs")
            )
        }
        return SavedTranscript(json.getLong("id"), json.getString("fileName"),
            json.optString("audioUri"), segments, names,
            json.optString("engine", "legacy-demo"), timings,
            decodeEvidence(json.optJSONArray("wordEvidence")))
    }
    private fun encodeEvidence(words: List<WordSpeakerEvidence>): JSONArray {
        val array = JSONArray()
        words.forEach { word ->
            val candidates = JSONArray()
            word.candidates.forEach { candidate ->
                candidates.put(JSONObject()
                    .put("speaker_id", candidate.speakerId)
                    .put("cosine_similarity", candidate.cosineSimilarity.toDouble())
                    .put("relative_score", candidate.relativeScore.toDouble()))
            }
            array.put(JSONObject()
                .put("index", word.index)
                .put("word", word.text)
                .put("start_ms", word.startMs)
                .put("end_ms", word.endMs)
                .put("candidates", candidates)
                .put("acoustic_reliability", word.acousticReliability.toDouble())
                .put("speaker_id", word.selectedSpeakerId)
                .put("changed_by_llm", word.changedByLlm))
        }
        return array
    }

    private fun decodeEvidence(array: JSONArray?): List<WordSpeakerEvidence> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val choices = item.optJSONArray("candidates") ?: JSONArray()
                val candidates = (0 until choices.length()).map { j ->
                    val c = choices.getJSONObject(j)
                    VoiceCandidate(c.getInt("speaker_id"),
                        c.optDouble("cosine_similarity").toFloat(),
                        c.optDouble("relative_score").toFloat())
                }
                WordSpeakerEvidence(
                    item.getInt("index"), item.getString("word"),
                    item.getInt("start_ms"), item.getInt("end_ms"), candidates,
                    item.optDouble("acoustic_reliability").toFloat(),
                    item.optInt("speaker_id", -1), item.optBoolean("changed_by_llm")
                )
            }.getOrNull()
        }
    }
}
