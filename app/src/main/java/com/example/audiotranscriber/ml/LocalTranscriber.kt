package com.example.audiotranscriber.ml

import android.content.Context
import android.net.Uri
import ai.onnxruntime.*
import com.example.audiotranscriber.model.TranscriptSegment
import com.example.audiotranscriber.data.ProcessingTimings
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.*

/** Local GigaAM v3 ASR + Pyannote 3.0 / NeMo Titanet hybrid diarization. */
object LocalTranscriber {
    data class TranscriptionOutput(
        val segments: List<TranscriptSegment>,
        val timings: ProcessingTimings,
        val words: List<com.example.audiotranscriber.model.WordSpeakerEvidence>
    )
    private data class Word(val text: String, val start: Double, val end: Double)
    private data class RecognitionResult(val words: List<Word>, val melMs: Long, val inferenceMs: Long)

    private fun elapsedMs(startedAt: Long): Long = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    suspend fun transcribe(context: Context, uri: Uri, speakerCount: Int = -1, progress: suspend (String) -> Unit): TranscriptionOutput = withContext(Dispatchers.Default) {
        val totalStarted = System.nanoTime()
        suspend fun report(s: String) = withContext(Dispatchers.Main) { progress(s) }
        ModelInstaller.requireHybridModels(context)
        val dir = ModelInstaller.directory(context)
        report("Подготовка аудио…")
        val decodeStarted = System.nanoTime()
        val audio = AudioDecoder.decode(context, uri)
        val audioDecodeMs = elapsedMs(decodeStarted)
        require(audio.size >= 320) { "Аудиозапись слишком короткая" }
        val modelStarted = System.nanoTime()
        val tokens = context.assets.open("tokens.json").bufferedReader().use { JSONArray(it.readText()) }
        val words = mutableListOf<Word>()
        var melMs = 0L
        var gigaamMs = 0L
        val duration = audio.size / 16000.0
        Asr(dir, tokens).use { asr ->
            // Includes token loading and creation of the three ONNX sessions.
            gigaamMs += elapsedMs(modelStarted)
            var start = 0.0
            while (start < duration) {
                ensureActive()
                report("Распознавание: ${(100 * start / duration).toInt()}%")
                val end = min(start + 24.0, duration)
                val samples = audio.copyOfRange((start * 16000).toInt(), min(audio.size, (end * 16000).toInt()))
                if (samples.size >= 320) {
                    val next = start + 23.0 < duration
                    val recognition = asr.recognize(samples)
                    melMs += recognition.melMs
                    gigaamMs += recognition.inferenceMs
                    for (w in recognition.words) {
                        val mid = (w.start + w.end) / 2 + start
                        if (start > 0 && mid < start + 0.5) continue
                        if (next && mid >= start + 23.5) continue
                        words += Word(w.text, w.start + start, min(w.end + start, duration))
                    }
                }
                start += 23.0
            }
        } // Release GigaAM before allocating diarization models.
        require(words.isNotEmpty()) { "Речь не распознана. Попробуйте запись с отчётливой речью." }
        report("Hybrid-диаризация: Pyannote + Titanet…")
        val coroutineContext = currentCoroutineContext()
        ensureActive()
        val diarizationStarted = System.nanoTime()
        val sortedWords = words.sortedWith(compareBy<Word> { it.start }.thenBy { it.end })
        val diarization = PyannoteTitanetDiarizer.diarize(
            audio = audio,
            modelsDirectory = dir,
            requestedSpeakers = speakerCount
        ) {
            coroutineContext.ensureActive()
        }
        ensureActive()
        val diarizationMs = elapsedMs(diarizationStarted)

        // Pyannote/Titanet returns speaker time intervals. Assign each GigaAM word
        // to the interval containing its midpoint; keep unmatched words as unknown.
        val speakerMatcher = Stage01Speakers(
            diarization.map {
                Stage01Speakers.Interval(it.start, it.end, it.speaker)
            }
        )

        report("Объединение слов и говорящих…")
        val mergeStarted = System.nanoTime()
        val merged = mutableListOf<TranscriptSegment>()
        for (w in sortedWords) {
            val speaker = speakerMatcher.speakerAt((w.start + w.end) / 2.0)
            val segment = TranscriptSegment(
                speaker,
                (w.start * 1000).roundToInt(),
                (w.end * 1000).roundToInt(),
                w.text
            )
            val previous = merged.lastOrNull()
            if (previous != null && previous.speakerId == speaker &&
                segment.startMs - previous.endMs <= 1000
            ) {
                merged[merged.lastIndex] = previous.copy(
                    endMs = max(previous.endMs, segment.endMs),
                    text = previous.text + " " + segment.text
                )
            } else {
                merged += segment
            }
        }
        require(merged.isNotEmpty()) { "Речь не распознана. Попробуйте запись с отчётливой речью." }
        val mergeMs = elapsedMs(mergeStarted)
        TranscriptionOutput(
            segments = merged,
            words = emptyList(),
            timings = ProcessingTimings(
                audioDecodeMs = audioDecodeMs,
                melFeaturesMs = melMs,
                gigaamMs = gigaamMs,
                diarizationMs = diarizationMs,
                mergeMs = mergeMs,
                saveMs = 0L,
                totalMs = elapsedMs(totalStarted)
            )
        )
    }

    private class Asr(dir: File, private val tokens: JSONArray) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val sessions = mutableListOf<OrtSession>()
        private fun session(dir: File, part: String): OrtSession = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            env.createSession(File(dir, "gigaam/v3_e2e_rnnt_$part.onnx").path, options).also { sessions += it }
        }
        private val encoder: OrtSession
        private val decoder: OrtSession
        private val joint: OrtSession
        init {
            try { encoder = session(dir, "encoder"); decoder = session(dir, "decoder"); joint = session(dir, "joint") }
            catch (e: Throwable) { close(); throw e }
        }
        private fun floatTensor(a: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape)
        private fun longTensor(a: LongArray, vararg shape: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(a), shape)
        private fun values(result: OrtSession.Result, i: Int) = (result[i] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        private data class Prediction(val dec: FloatArray, val h: FloatArray, val c: FloatArray)
        private fun predict(label: Int, h: FloatArray, c: FloatArray): Prediction {
            longTensor(longArrayOf(label.toLong()), 1, 1).use { x ->
                floatTensor(h, 1, 1, 320).use { hi -> floatTensor(c, 1, 1, 320).use { ci ->
                    decoder.run(mapOf("x" to x, "hi" to hi, "ci" to ci)).use { r -> return Prediction(values(r, 0), values(r, 1), values(r, 2)) }
                } }
            }
        }
        suspend fun recognize(audio: FloatArray): RecognitionResult {
            val melStarted = System.nanoTime()
            val features = MelFeatures.extract(audio)
            val melMs = elapsedMs(melStarted)
            val inferenceStarted = System.nanoTime()
            val frames = features.size / 64
            val encoded: FloatArray
            val length: Int
            val stride: Int
            floatTensor(features, 1, 64, frames.toLong()).use { x ->
                longTensor(longArrayOf(frames.toLong()), 1).use { n ->
                    encoder.run(mapOf("audio_signal" to x, "length" to n)).use { r ->
                        encoded = values(r, 0)
                        stride = (r[0].info as TensorInfo).shape.last().toInt()
                        length = (r[1] as OnnxTensor).intBuffer.get(0)
                    }
                }
            }
            require(length > 0 && length <= stride) { "Модель вернула неверную длину аудио" }
            val blankId = tokens.length()
            var prediction = predict(blankId, FloatArray(320), FloatArray(320))
            val ids = mutableListOf<Int>()
            val times = mutableListOf<Int>()
            for (frame in 0 until length) {
                currentCoroutineContext().ensureActive()
                val enc = FloatArray(768) { encoded[it * stride + frame] }
                for (symbol in 0 until 10) {
                    val label = floatTensor(enc, 1, 768, 1).use { x ->
                        floatTensor(prediction.dec, 1, 320, 1).use { d ->
                            joint.run(mapOf("enc" to x, "dec" to d)).use { r ->
                                val logits = values(r, 0)
                                logits.indices.maxByOrNull { logits[it] }!!
                            }
                        }
                    }
                    if (label == blankId) break
                    ids += label; times += frame
                    prediction = predict(label, prediction.h, prediction.c)
                }
            }
            val step = audio.size / 16000.0 / length
            val words = mutableListOf<Word>()
            val text = StringBuilder()
            var first = 0
            var last = 0
            fun commit() {
                val t = text.toString().trim()
                if (t.isNotEmpty()) words += Word(t, first * step, (last + 1) * step)
                text.clear()
            }
            for (i in ids.indices) {
                var piece = tokens.getString(ids[i])
                if (piece.startsWith("▁")) { commit(); piece = piece.drop(1) }
                else if (piece == " ") { commit(); continue }
                if (text.isEmpty()) first = times[i]
                last = times[i]; text.append(piece)
            }
            commit()
            return RecognitionResult(words, melMs, elapsedMs(inferenceStarted))
        }
        override fun close() { sessions.asReversed().forEach { it.close() }; sessions.clear() }
    }
}

