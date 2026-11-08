package com.example.audiotranscriber.ml

import android.content.Context
import android.net.Uri
import com.example.audiotranscriber.model.SpeakerSummary
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Independent TitaNet profiles. Never mixes these vectors with ERes2Net. */
internal object TitaNetEnrollment {
    data class Profile(val id: String, val name: String, val vector: FloatArray)
    private fun folder(context: Context) = File(context.noBackupFilesDir, "voice-profiles-v1").apply { mkdirs() }
    fun load(context: Context): List<Profile> = folder(context).listFiles { f -> f.extension == "json" }.orEmpty()
        .sortedBy { it.name }.mapNotNull { file -> runCatching {
            val j = JSONObject(file.readText())
            check(j.getString("model") == "nemo_en_titanet_small.onnx")
            val v = j.getJSONArray("embedding")
            Profile(j.getString("id"), j.getString("name"), normalize(FloatArray(v.length()) { v.getDouble(it).toFloat() }))
        }.getOrNull() }
    fun delete(context: Context, id: String) {
        require(id.matches(Regex("profile_[a-f0-9-]+")))
        File(folder(context), "$id.json").delete()
    }
    internal fun normalize(v: FloatArray): FloatArray {
        require(v.isNotEmpty() && v.all { it.isFinite() }) { "Некорректный голосовой вектор" }
        val norm = sqrt(v.sumOf { it.toDouble() * it })
        require(norm >= 1e-8)
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }
    internal fun acceptedProfile(query: FloatArray, profiles: List<Profile>): String? {
        val q = normalize(query)
        val ranked = profiles.filter { it.vector.size == q.size }.map { p ->
            p.id to q.indices.sumOf { q[it].toDouble() * p.vector[it] }
        }.sortedByDescending { it.second }
        if (ranked.isEmpty()) return null
        val best = ranked.first()
        return best.first.takeIf { best.second >= 0.30 && best.second - (ranked.getOrNull(1)?.second ?: -1.0) >= 0.06 }
    }
    private class Extractor(model: File) : AutoCloseable {
        val engine = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
            model = model.path, numThreads = 2, provider = "cpu", debug = false
        ))
        fun embedding(audio: FloatArray): FloatArray? {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(audio, 16000)
                stream.inputFinished()
                if (!engine.isReady(stream)) return null
                return normalize(engine.compute(stream))
            } finally { stream.release() }
        }
        override fun close() = engine.release()
    }
    suspend fun register(context: Context, name: String, clips: List<Uri>, progress: suspend (String) -> Unit) = withContext(Dispatchers.Default) {
        require(name.trim().isNotEmpty()) { "Введите имя участника" }
        require(clips.size in 2..4) { "Выберите 2–4 чистые записи одного человека" }
        val model = ModelInstaller.ensureTitaNet(context, progress)
        val vectors = mutableListOf<FloatArray>()
        Extractor(model).use { extractor ->
            clips.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                progress("Голосовой профиль: запись ${index + 1} / ${clips.size}")
                val audio = AudioDecoder.decode(context, uri)
                require(audio.size >= 40000) { "Каждая запись должна быть не короче 2.5 секунды" }
                vectors += extractor.embedding(audio) ?: error("Недостаточно речи в записи ${index + 1}")
            }
        }
        val mean = normalize(FloatArray(vectors.first().size) { i -> vectors.map { it[i] }.average().toFloat() })
        val id = "profile_${UUID.randomUUID()}"
        val j = JSONObject().put("id", id).put("name", name.trim()).put("model", "nemo_en_titanet_small.onnx")
            .put("embedding", JSONArray(mean.map { it.toDouble() }))
        currentCoroutineContext().ensureActive()
        val temp = File(folder(context), "$id.tmp")
        try {
            temp.writeText(j.toString())
            check(temp.renameTo(File(folder(context), "$id.json")))
        } finally { temp.delete() }
    }
    private data class Turn(val cluster: Int, val start: Double, var end: Double, var profile: String? = null)
    suspend fun identify(
        audio: FloatArray, intervals: List<ReverbEres2NetDiarizer.Interval>, raw: WordSpeakerAligner.Result,
        profiles: List<Profile>, model: File, checkCancelled: suspend () -> Unit
    ): WordSpeakerAligner.Result {
        val turns = mutableListOf<Turn>()
        for ((cluster, segments) in intervals.groupBy { it.speaker }) {
            var current: Turn? = null
            for (s in segments.sortedBy { it.start }) {
                val t = current
                if (t != null && s.start - t.end <= 0.8 && intervals.none {
                    it.speaker != cluster && it.start < s.end && it.end > t.start
                }) t.end = max(t.end, s.end)
                else { current = Turn(cluster, s.start, s.end); turns += current }
            }
        }
        Extractor(model).use { extractor ->
            for (t in turns.sortedBy { it.start }) {
                checkCancelled()
                if (t.end - t.start < 1.5) continue
                val start = (t.start * 16000).roundToInt().coerceIn(0, audio.size)
                val end = (t.end * 16000).roundToInt().coerceIn(start, audio.size)
                val embedding = extractor.embedding(audio.copyOfRange(start, end)) ?: continue
                t.profile = acceptedProfile(embedding, profiles)
            }
        }
        val stableClusters = intervals.sortedBy { it.start }.map { it.speaker }.distinct().withIndex().associate { it.value to it.index }
        val labels = raw.words.map { word ->
            val scores = linkedMapOf<String, Double>()
            for (s in intervals) {
                val amount = max(0.0, min(word.endMs / 1000.0, s.end) - max(word.startMs / 1000.0, s.start))
                if (amount <= 0) continue
                val turn = turns.filter { it.cluster == s.speaker && min(it.end, s.end) > max(it.start, s.start) }
                    .maxByOrNull { min(it.end, s.end) - max(it.start, s.start) }
                val label = turn?.profile ?: "cluster_${stableClusters.getValue(s.speaker)}"
                scores[label] = (scores[label] ?: 0.0) + amount
            }
            val best = scores.maxByOrNull { it.value }
            val confidence = min(1.0, (best?.value ?: 0.0) / max(0.0001, (word.endMs - word.startMs) / 1000.0))
            (best?.key?.takeIf { confidence >= 0.15 }) to confidence.toFloat()
        }
        val ids = labels.mapNotNull { it.first }.distinct().withIndex().associate { it.value to it.index }
        val words = raw.words.mapIndexed { index, word ->
            val (label, confidence) = labels[index]
            word.copy(speakerContextId = ids[label] ?: -1, confidence = confidence,
                assignmentSource = if (label == null) "unknown" else if (label.startsWith("profile_")) "titanet_enrollment" else "diarization_cluster")
        }
        val speakers = ids.map { (label, id) ->
            SpeakerSummary(id, words.first { it.speakerContextId == id }.startMs,
                if (label.startsWith("profile_")) "titanet_enrollment" else "diarization_cluster",
                label.takeIf { it.startsWith("profile_") })
        }
        return WordSpeakerAligner.Result(words, speakers)
    }
}
