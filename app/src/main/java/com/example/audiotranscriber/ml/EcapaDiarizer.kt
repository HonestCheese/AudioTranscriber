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
    private const val COSINE_THRESHOLD = 0.72f

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

    private fun cluster(vectors: List<FloatArray>, requestedSpeakers: Int): IntArray {
        val centroids = mutableListOf<FloatArray>()
        val counts = mutableListOf<Int>()
        val labels = IntArray(vectors.size)
        for ((index, vector) in vectors.withIndex()) {
            var best = -1
            var score = -2f
            for (i in centroids.indices) {
                val similarity = vector.indices.sumOf { (vector[it] * centroids[i][it]).toDouble() }.toFloat()
                if (similarity > score) { score = similarity; best = i }
            }
            val canAdd = requestedSpeakers == -1 || centroids.size < requestedSpeakers
            if ((best < 0 || score < COSINE_THRESHOLD) && canAdd) {
                best = centroids.size
                centroids.add(vector.copyOf())
                counts.add(1)
            } else {
                if (best < 0) best = 0
                val count = counts[best]
                for (j in vector.indices) centroids[best][j] =
                    (centroids[best][j] * count + vector[j]) / (count + 1)
                counts[best] = count + 1
                val norm = sqrt(centroids[best].sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-8f)
                for (j in vector.indices) centroids[best][j] /= norm
            }
            labels[index] = best
        }
        return labels
    }
}
