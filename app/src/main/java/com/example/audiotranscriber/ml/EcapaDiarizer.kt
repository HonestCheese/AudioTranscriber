package com.example.audiotranscriber.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.audiotranscriber.model.WordSpeakerEvidence
import com.example.audiotranscriber.model.VoiceCandidate
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ECAPA-TDNN from SpeechBrain: raw 16 kHz mono waveform -> 192-D speaker embedding.
 * Models are run locally through ONNX Runtime. Clustering uses cosine similarity.
 * Note: this is an experimental fixed-window diarizer. Overlapping speech is not resolved.
 */
internal object EcapaDiarizer {
    data class Interval(val start: Double, val end: Double, val speaker: Int)
    private const val RATE = 16000
    private const val WINDOW_SAMPLES = 2 * RATE
    private const val HOP_SAMPLES = RATE
    // Slightly more permissive threshold reduces false speaker splits caused by
    // natural changes in pitch, loudness and microphone distance.
    private const val COSINE_THRESHOLD = 0.62f
    // Tiny clusters in longer recordings are usually unstable speaker fragments.
    private const val MIN_WINDOWS_FOR_CLUSTER_CLEANUP = 12
    private const val MIN_STABLE_CLUSTER_SIZE = 3
    // Merge a weak cluster only when it is still acoustically close to a stable one.
    private const val WEAK_CLUSTER_MERGE_THRESHOLD = 0.56

    fun diarize(audio: FloatArray, model: File, requestedSpeakers: Int, checkCancelled: () -> Unit): List<Interval> {
        require(audio.isNotEmpty())
        require(requestedSpeakers == -1 || requestedSpeakers > 0)
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(1)
            environment.createSession(model.path, opts).use { session ->
                val vectors = mutableListOf<FloatArray>()
                val ranges = mutableListOf<Pair<Int, Int>>()
                var start = 0
                while (start < audio.size) {
                    checkCancelled()
                    val end = min(start + WINDOW_SAMPLES, audio.size)
                    val span = end - start
                    if (span < RATE / 2) break
                    val clip = FloatArray(max(WINDOW_SAMPLES, span))
                    System.arraycopy(audio, start, clip, 0, span)
                    // Skip windows with no meaningful signal.
                    var energy = 0.0
                    for (i in 0 until span) energy += clip[i] * clip[i]
                    val rms = sqrt(energy / span)
                    if (rms >= 0.003) {
                        val embedding = embed(session, environment, clip, span.toFloat() / clip.size)
                        vectors.add(embedding)
                        ranges.add(start to end)
                    }
                    if (end == audio.size) break
                    start += HOP_SAMPLES
                }
                if (vectors.isEmpty()) return emptyList()
                val labels = cluster(vectors, requestedSpeakers)
                val intervals = mutableListOf<Interval>()
                for (i in ranges.indices) {
                    // Partition overlapping windows at their centers, rather than returning ambiguous overlaps.
                    val left = if (i == 0) ranges[i].first else
                        (ranges[i - 1].first + ranges[i].first) / 2 + WINDOW_SAMPLES / 2
                    val right = if (i == ranges.lastIndex) ranges[i].second else
                        (ranges[i].first + ranges[i + 1].first) / 2 + WINDOW_SAMPLES / 2
                    val from = max(ranges[i].first, left)
                    val to = min(ranges[i].second, right)
                    if (to > from) intervals.add(Interval(from.toDouble() / RATE, to.toDouble() / RATE, labels[i]))
                }
                return intervals
            }
        }
    }


    data class WordSpan(val text: String, val start: Double, val end: Double)
    private data class AcousticWindow(val start: Double, val end: Double, val embedding: FloatArray)

    /**
     * Return relative voice similarities for EACH GigaAM word.
     * ECAPA is evaluated on 2-second context windows (500-ms hop), never on
     * an isolated short word. These are not calibrated speaker probabilities.
     */
    fun scoreWords(
        audio: FloatArray,
        model: File,
        words: List<WordSpan>,
        requestedSpeakers: Int,
        checkCancelled: () -> Unit
    ): List<WordSpeakerEvidence> {
        if (words.isEmpty()) return emptyList()
        val frames = mutableListOf<AcousticWindow>()
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            env.createSession(model.path, options).use { session ->
                var start = 0
                while (start < audio.size) {
                    checkCancelled()
                    val end = min(start + WINDOW_SAMPLES, audio.size)
                    val valid = end - start
                    if (valid < RATE / 2) break
                    val clip = FloatArray(WINDOW_SAMPLES)
                    System.arraycopy(audio, start, clip, 0, valid)
                    var energy = 0.0
                    for (i in 0 until valid) energy += clip[i].toDouble() * clip[i]
                    if (sqrt(energy / valid) > 0.003) {
                        frames += AcousticWindow(
                            start.toDouble() / RATE,
                            end.toDouble() / RATE,
                            embed(session, env, clip, valid.toFloat() / WINDOW_SAMPLES)
                        )
                    }
                    if (end == audio.size) break
                    start += RATE / 2
                }
            }
        }
        fun empty(index: Int, word: WordSpan) =
            WordSpeakerEvidence(index, word.text, (word.start * 1000).toInt(),
                (word.end * 1000).toInt(), emptyList(), 0f, -1)
        if (frames.isEmpty()) return words.mapIndexed(::empty)

        // Previous exhaustive hierarchical clustering was cubic in recording length.
        // Build speaker prototypes from at most 160 evenly spaced windows.
        val selected = if (frames.size <= 72) frames.indices.toList()
            else (0 until 72).map { it * (frames.lastIndex) / 71 }.distinct()
        val sample = selected.map { frames[it].embedding }
        // Automatic mode uses the acoustic threshold and support-based cleanup.
        // Do not force a fixed count (the old fallback forced exactly six speakers
        // whenever noisy embeddings produced more than six provisional clusters).
        val clusterIds = cluster(sample, requestedSpeakers)
        val count = (clusterIds.maxOrNull() ?: 0) + 1
        val prototypes = Array(count) { FloatArray(sample[0].size) }
        for (j in sample.indices) {
            val sum = prototypes[clusterIds[j]]
            for (k in sum.indices) sum[k] += sample[j][k]
        }
        fun normalize(vec: FloatArray) {
            val n = sqrt(vec.sumOf { it.toDouble() * it }).coerceAtLeast(1e-8)
            for (j in vec.indices) vec[j] = (vec[j] / n).toFloat()
        }
        prototypes.forEach(::normalize)
        fun cosine(a: FloatArray, b: FloatArray): Double {
            var score = 0.0
            for (j in a.indices) score += a[j].toDouble() * b[j]
            return score
        }

        // Refine once using every frame to reduce the sampling bias.
        val sums = Array(count) { FloatArray(sample[0].size) }
        for (frame in frames) {
            checkCancelled()
            val id = prototypes.indices.maxByOrNull { cosine(frame.embedding, prototypes[it]) } ?: 0
            for (j in frame.embedding.indices) sums[id][j] += frame.embedding[j]
        }
        for (i in prototypes.indices) {
            if (sums[i].any { it != 0f }) {
                normalize(sums[i])
                prototypes[i] = sums[i]
            }
        }

        return words.mapIndexed { index, word ->
            checkCancelled()
            val mid = (word.start + word.end) / 2
            val local = frames.filter { mid >= it.start && mid <= it.end }
            if (local.isEmpty()) empty(index, word)
            else {
                val similarities = DoubleArray(count)
                var weightSum = 0.0
                for (frame in local) {
                    val center = (frame.start + frame.end) / 2
                    val weight = (1.0 - abs(mid - center) / 1.25).coerceAtLeast(0.1)
                    weightSum += weight
                    for (j in similarities.indices) {
                        similarities[j] += weight * cosine(frame.embedding, prototypes[j])
                    }
                }
                for (j in similarities.indices) similarities[j] /= weightSum
                val peak = similarities.maxOrNull() ?: 0.0
                val weightList = similarities.map { exp(((it - peak) / 0.12).coerceAtLeast(-30.0)) }
                val weightTotal = weightList.sum().coerceAtLeast(1e-8)
                val candidates = similarities.indices.map { j ->
                    VoiceCandidate(j, similarities[j].toFloat(),
                        (weightList[j] / weightTotal).toFloat())
                }.sortedByDescending { it.relativeScore }
                val margin = if (candidates.size > 1)
                    candidates[0].relativeScore - candidates[1].relativeScore else 1f
                val shortWordPenalty = ((word.end - word.start) / 0.5).toFloat().coerceIn(0.15f, 1f)
                WordSpeakerEvidence(index, word.text, (word.start * 1000).toInt(),
                    (word.end * 1000).toInt(), candidates,
                    (margin * shortWordPenalty).coerceIn(0f, 1f), -1)
            }
        }
    }

    private fun embed(session: OrtSession, env: OrtEnvironment, clip: FloatArray, fraction: Float): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(clip), longArrayOf(1, clip.size.toLong())).use { wav ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(fraction)), longArrayOf(1)).use { lens ->
                session.run(mapOf("wav" to wav, "lens" to lens)).use { result ->
                    val buffer = (result[0] as OnnxTensor).floatBuffer
                    val vector = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    require(vector.size == 192) { "Unexpected ECAPA output: ${vector.size}" }
                    val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-8f)
                    for (i in vector.indices) vector[i] /= norm
                    return vector
                }
            }
        }
    }

    /**
     * Offline agglomerative clustering: compare ALL windows before assigning IDs.
     * Unlike online assignment this can merge early outliers with later matching speech.
     * Vectors are already L2-normalized by embed().
     */
    internal fun cluster(vectors: List<FloatArray>, requestedSpeakers: Int): IntArray {
        if (vectors.isEmpty()) return IntArray(0)
        require(requestedSpeakers == -1 || requestedSpeakers > 0)
        val groups = vectors.indices.map { mutableListOf(it) }.toMutableList()

        fun similarity(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i]
            return dot
        }
        fun centroid(members: List<Int>): FloatArray {
            val sum = FloatArray(vectors[0].size)
            for (index in members) for (j in sum.indices) sum[j] += vectors[index][j]
            val norm = sqrt(sum.sumOf { it.toDouble() * it }).coerceAtLeast(1e-9)
            for (j in sum.indices) sum[j] = (sum[j] / norm).toFloat()
            return sum
        }
        while (groups.size > 1) {
            var bestScore = Double.NEGATIVE_INFINITY
            var first = -1
            var second = -1
            val centers = groups.map(::centroid)
            for (i in 0 until groups.size) {
                for (j in i + 1 until groups.size) {
                    val score = similarity(centers[i], centers[j])
                    if (score > bestScore) {
                        bestScore = score
                        first = i
                        second = j
                    }
                }
            }
            // With a specified count, honor that number rather than a similarity cutoff.
            if (requestedSpeakers == -1 && bestScore < COSINE_THRESHOLD) break
            if (requestedSpeakers > 0 && groups.size <= requestedSpeakers) break
            groups[first].addAll(groups[second])
            groups.removeAt(second)
        }

        // In automatic mode, remove weak fragments only in recordings with
        // enough evidence. A weak cluster is merged only if it is reasonably close
        // to a stable speaker; acoustically distinct short speakers are preserved.
        if (requestedSpeakers == -1 && vectors.size >= MIN_WINDOWS_FOR_CLUSTER_CLEANUP) {
            val stable = groups.filter { it.size >= MIN_STABLE_CLUSTER_SIZE }
            if (stable.isNotEmpty()) {
                val weakGroups = groups.filter { it.size < MIN_STABLE_CLUSTER_SIZE }.toList()
                for (weak in weakGroups) {
                    val target = stable.maxByOrNull { similarity(centroid(it), centroid(weak)) }!!
                    val score = similarity(centroid(target), centroid(weak))
                    if (score >= WEAK_CLUSTER_MERGE_THRESHOLD) {
                        target.addAll(weak)
                        groups.remove(weak)
                    }
                }
            }
        }
        // Stable numbering by first appearance, independent of merge order.
        groups.sortBy { it.minOrNull() ?: Int.MAX_VALUE }
        return IntArray(vectors.size).also { ids ->
            for ((speaker, members) in groups.withIndex()) for (index in members) ids[index] = speaker
        }
    }
}
