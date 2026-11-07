package com.example.audiotranscriber.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
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
    // Cosine similarity is NOT sherpa's previous clustering distance threshold.
    private const val COSINE_THRESHOLD = 0.64f
    // A one-window outlier should not become an extra person in a longer recording.
    private const val MIN_WINDOWS_FOR_OUTLIER_CLEANUP = 6

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

        // Merge isolated one-window clusters into their closest established speaker.
        // Do not collapse a genuinely short 2-speaker exchange.
        if (requestedSpeakers == -1 && vectors.size >= MIN_WINDOWS_FOR_OUTLIER_CLEANUP) {
            val stable = groups.filter { it.size >= 2 }
            if (stable.isNotEmpty()) {
                val outliers = groups.filter { it.size == 1 }.toList()
                for (outlier in outliers) {
                    val target = stable.maxByOrNull { similarity(centroid(it), centroid(outlier)) }!!
                    target.addAll(outlier)
                    groups.remove(outlier)
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
