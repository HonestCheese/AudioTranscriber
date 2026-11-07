package com.example.audiotranscriber.data

import android.content.Context
import com.example.audiotranscriber.model.SpeakerSummary
import com.example.audiotranscriber.model.TranscriptSegment
import com.example.audiotranscriber.model.WordSpeakerAssignment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ProcessingTimings(
    val audioDecodeMs: Long,
    val melFeaturesMs: Long,
    val gigaamMs: Long,
    val preprocessingMs: Long,
    val diarizationMs: Long,
    val mergeMs: Long,
    val saveMs: Long = 0L,
    val totalMs: Long
)

data class SavedTranscript(
    val id: Long,
    val fileName: String,
    val audioUri: String,
    val segments: List<TranscriptSegment>,
    val speakerNames: Map<Int, String>,
    val engine: String = "gigaam-v3-reverb-eres2netv2-dpdfnet2",
    val processingTimings: ProcessingTimings? = null,
    val durationMs: Int = 0,
    val speakers: List<SpeakerSummary> = emptyList(),
    val wordAssignments: List<WordSpeakerAssignment> = emptyList(),
    val diarizationAudioVariant: String = "Original",
    val recordingId: String = fileName
)

class TranscriptStore(private val context: Context) {
    private val folder get() = File(context.filesDir, "transcripts").apply { mkdirs() }
    private val orderFile get() = File(folder, "order.json")

    fun loadAll(): List<SavedTranscript> {
        val all = folder.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { decode(JSONObject(file.readText())) }.getOrNull()
            }
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
            .put("recordingId", transcript.recordingId)
            .put("audioUri", transcript.audioUri)
            .put("durationMs", transcript.durationMs)
            .put("engine", transcript.engine)
            .put("diarizationAudioVariant", transcript.diarizationAudioVariant)

        transcript.processingTimings?.let { timings ->
            root.put(
                "processingTimings",
                JSONObject()
                    .put("audioDecodeMs", timings.audioDecodeMs)
                    .put("melFeaturesMs", timings.melFeaturesMs)
                    .put("gigaamMs", timings.gigaamMs)
                    .put("preprocessingMs", timings.preprocessingMs)
                    .put("diarizationMs", timings.diarizationMs)
                    .put("mergeMs", timings.mergeMs)
                    .put("saveMs", timings.saveMs)
                    .put("totalMs", timings.totalMs)
            )
        }

        val names = JSONObject()
        transcript.speakerNames.forEach { (id, name) -> names.put(id.toString(), name) }
        root.put("speakerNames", names)

        val segments = JSONArray()
        transcript.segments.forEach { segment ->
            segments.put(
                JSONObject()
                    .put("speakerId", segment.speakerId)
                    .put("startMs", segment.startMs)
                    .put("endMs", segment.endMs)
                    .put("text", segment.text)
            )
        }
        root.put("segments", segments)

        val speakers = JSONArray()
        transcript.speakers.forEach { speaker ->
            speakers.put(
                JSONObject()
                    .put("speakerId", speaker.speakerId)
                    .put("firstSeenMs", speaker.firstSeenMs)
            )
        }
        root.put("speakers", speakers)

        val words = JSONArray()
        transcript.wordAssignments.forEach { word ->
            words.put(
                JSONObject()
                    .put("index", word.index)
                    .put("text", word.text)
                    .put("startMs", word.startMs)
                    .put("endMs", word.endMs)
                    .put("speakerRawId", word.speakerRawId)
                    .put("speakerContextId", word.speakerContextId)
                    .put("confidence", word.confidence.toDouble())
            )
        }
        root.put("words", words)

        val temporary = File(folder, "${transcript.id}.tmp")
        temporary.writeText(root.toString())
        val target = File(folder, "${transcript.id}.json")
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    fun rename(id: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Имя не может быть пустым." }
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

    /** Export the notebook's structured JSON shape for one phone recording. */
    fun exportJson(transcript: SavedTranscript): String {
        fun speakerName(id: Int) =
            if (id < 0) "speaker_unknown" else "speaker_$id"

        val transcriptItems = JSONArray()
        transcript.segments.forEach { segment ->
            transcriptItems.put(
                JSONObject()
                    .put("speaker_id", speakerName(segment.speakerId))
                    .put("start_ms", segment.startMs)
                    .put("end_ms", segment.endMs)
                    .put("text", segment.text)
            )
        }

        val speakers = JSONArray()
        val knownSpeakers = if (transcript.speakers.isNotEmpty()) {
            transcript.speakers
        } else {
            transcript.segments
                .filter { it.speakerId >= 0 }
                .groupBy { it.speakerId }
                .map { (speakerId, segments) ->
                    SpeakerSummary(speakerId, segments.minOf { it.startMs })
                }
        }
        knownSpeakers.sortedBy { it.firstSeenMs }.forEach { speaker ->
            speakers.put(
                JSONObject()
                    .put("speaker_id", speakerName(speaker.speakerId))
                    .put("first_seen_ms", speaker.firstSeenMs)
            )
        }

        val words = JSONArray()
        transcript.wordAssignments.forEach { word ->
            words.put(
                JSONObject()
                    .put("text", word.text)
                    .put("start_ms", word.startMs)
                    .put("end_ms", word.endMs)
                    .put("speaker_id", speakerName(word.speakerContextId))
                    .put("speaker_id_raw", speakerName(word.speakerRawId))
                    .put("speaker_confidence", word.confidence.toDouble())
            )
        }

        val durationMs = transcript.durationMs.takeIf { it > 0 } ?: maxOf(
            transcript.segments.maxOfOrNull { it.endMs } ?: 0,
            transcript.wordAssignments.maxOfOrNull { it.endMs } ?: 0
        )

        val pipeline = JSONObject()
            .put("asr", "GigaAM v3 E2E RNNT")
            .put("asr_audio", "Original")
            .put("asr_chunk_size_sec", 24)
            .put("asr_chunk_step_sec", 23)
            .put("diarization", "Reverb v1 + 3D-Speaker ERes2NetV2 + FastClustering")
            .put("diarization_audio_variant", transcript.diarizationAudioVariant)
            .put("num_speakers", -1)
            .put("cluster_threshold", 0.63)
            .put("window_shift_ratio", 0.1)
            .put("min_duration_on", 0.3)
            .put("min_duration_off", 0.5)
            .put("context_switch_penalty", 0.7)
            .put("context_utterance_gap_seconds", 0.8)
            .put("min_word_overlap", 0.15)
            .put("max_pause_seconds", 0.9)
            .put("wpe_enabled", false)
            .put("source_notebook", "AudioTranscriber_Final_ML_Pipeline_v1_fixed.ipynb")
            .put(
                "denoiser",
                if (transcript.diarizationAudioVariant == "Original") "not_applied" else "DPDFNet2"
            )
            .put(
                "denoiser_attenuation_db",
                if (transcript.diarizationAudioVariant == "Original") 0.0 else 12.0
            )
            .put(
                "compressor_enabled",
                transcript.diarizationAudioVariant == "DPDFNet + gentle compressor"
            )
            .put("wpe_enabled", false)

        return JSONObject()
            .put("recording_id", transcript.recordingId)
            .put("sample_rate", 16_000)
            .put("duration_ms", durationMs)
            .put("speakers", speakers)
            .put("transcript", transcriptItems)
            .put("words", words)
            .put("pipeline", pipeline)
            .toString(2)
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

        val segmentArray = json.getJSONArray("segments")
        val segments = (0 until segmentArray.length()).map { index ->
            val segment = segmentArray.getJSONObject(index)
            TranscriptSegment(
                segment.getInt("speakerId"),
                segment.getInt("startMs"),
                segment.getInt("endMs"),
                segment.getString("text")
            )
        }

        val timingJson = json.optJSONObject("processingTimings")
        val timings = timingJson?.let {
            ProcessingTimings(
                audioDecodeMs = it.optLong("audioDecodeMs"),
                melFeaturesMs = it.optLong("melFeaturesMs"),
                gigaamMs = it.optLong("gigaamMs"),
                preprocessingMs = it.optLong("preprocessingMs"),
                diarizationMs = it.optLong("diarizationMs"),
                mergeMs = it.optLong("mergeMs"),
                saveMs = it.optLong("saveMs"),
                totalMs = it.optLong("totalMs")
            )
        }

        val speakerArray = json.optJSONArray("speakers") ?: JSONArray()
        val speakers = (0 until speakerArray.length()).map { index ->
            val speaker = speakerArray.getJSONObject(index)
            SpeakerSummary(
                speakerId = speaker.getInt("speakerId"),
                firstSeenMs = speaker.getInt("firstSeenMs")
            )
        }

        val wordArray = json.optJSONArray("words") ?: JSONArray()
        val words = (0 until wordArray.length()).map { index ->
            val word = wordArray.getJSONObject(index)
            WordSpeakerAssignment(
                index = word.optInt("index", index),
                text = word.getString("text"),
                startMs = word.getInt("startMs"),
                endMs = word.getInt("endMs"),
                speakerRawId = word.optInt("speakerRawId", -1),
                speakerContextId = word.optInt("speakerContextId", -1),
                confidence = word.optDouble("confidence", 0.0).toFloat()
            )
        }

        return SavedTranscript(
            id = json.getLong("id"),
            fileName = json.getString("fileName"),
            audioUri = json.optString("audioUri"),
            segments = segments,
            durationMs = json.optInt("durationMs", 0),
            speakerNames = names,
            engine = json.optString("engine", "legacy-demo"),
            processingTimings = timings,
            speakers = speakers,
            wordAssignments = words,
            diarizationAudioVariant = json.optString("diarizationAudioVariant", "Original"),
            recordingId = json.optString("recordingId", json.getString("fileName"))
        )
    }
}
