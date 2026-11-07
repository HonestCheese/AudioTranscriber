package com.example.audiotranscriber.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import com.example.audiotranscriber.data.ProcessingTimings
import com.example.audiotranscriber.model.SpeakerSummary
import com.example.audiotranscriber.model.TranscriptSegment
import com.example.audiotranscriber.model.WordSpeakerAssignment
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Local GigaAM v3 RNNT + Reverb v1 / 3D-Speaker ERes2NetV2 pipeline. */
object LocalTranscriber {
    data class TranscriptionOutput(
        val segments: List<TranscriptSegment>,
        val timings: ProcessingTimings,
        val words: List<WordSpeakerAssignment>,
        val speakers: List<SpeakerSummary>,
        val diarizationAudioVariant: String,
        val durationMs: Int
    )

    private data class Word(val text: String, val start: Double, val end: Double)
    private data class RecognitionResult(
        val words: List<Word>,
        val melMs: Long,
        val inferenceMs: Long
    )

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    suspend fun transcribe(
        context: Context,
        uri: Uri,
        audioVariant: DiarizationAudioVariant,
        progress: suspend (String) -> Unit
    ): TranscriptionOutput = withContext(Dispatchers.Default) {
        val totalStarted = System.nanoTime()
        suspend fun report(message: String) =
            withContext(Dispatchers.Main) { progress(message) }

        check(ModelInstaller.isReady(context)) {
            "Сначала импортируйте архив с тремя моделями GigaAM."
        }

        report("Проверка локальных моделей…")
        ModelInstaller.ensurePipelineModels(
            context = context,
            includeDenoiser = audioVariant.requiresDenoiser
        ) { message -> report(message) }

        val dir = ModelInstaller.directory(context)
        report("Подготовка аудио…")
        val decodeStarted = System.nanoTime()
        val audio = AudioDecoder.decode(context, uri)
        val audioDecodeMs = elapsedMs(decodeStarted)
        require(audio.size >= 320) { "Аудиозапись слишком короткая." }

        // ASR always uses the original, unmodified recording.
        val modelStarted = System.nanoTime()
        val tokens = context.assets.open("tokens.json").bufferedReader().use {
            JSONArray(it.readText())
        }
        val words = mutableListOf<Word>()
        var melMs = 0L
        var gigaamMs = 0L
        val duration = audio.size / 16_000.0

        Asr(dir, tokens).use { asr ->
            gigaamMs += elapsedMs(modelStarted)
            var chunkStart = 0.0
            while (chunkStart < duration) {
                currentCoroutineContext().ensureActive()
                report("GigaAM · распознавание: ${(100 * chunkStart / duration).toInt()}%")

                val chunkEnd = min(chunkStart + 24.0, duration)
                val samples = audio.copyOfRange(
                    (chunkStart * 16_000).toInt(),
                    min(audio.size, (chunkEnd * 16_000).toInt())
                )

                if (samples.size >= 320) {
                    val hasNextChunk = chunkStart + 23.0 < duration
                    val recognition = asr.recognize(samples)
                    melMs += recognition.melMs
                    gigaamMs += recognition.inferenceMs

                    for (word in recognition.words) {
                        val midpoint = (word.start + word.end) / 2.0 + chunkStart
                        // The 24 s windows advance by 23 s. Keep the overlap
                        // midpoint on exactly one side of the 23.5 s boundary.
                        if (chunkStart > 0.0 && midpoint < chunkStart + 0.5) continue
                        if (hasNextChunk && midpoint >= chunkStart + 23.5) continue

                        words += Word(
                            text = word.text,
                            start = word.start + chunkStart,
                            end = min(word.end + chunkStart, duration)
                        )
                    }
                }
                chunkStart += 23.0
            }
        } // Release GigaAM before loading the diarization models into memory.

        require(words.isNotEmpty()) {
            "Речь не распознана. Попробуйте запись с отчётливой речью."
        }

        report("Подготовка аудио для диаризации · ${audioVariant.label}…")
        val preprocessingStarted = System.nanoTime()
        val diarizationAudio = when (audioVariant) {
            DiarizationAudioVariant.ORIGINAL -> audio
            DiarizationAudioVariant.DPDFNET_MILD -> applyDpdfNet(dir, audio)
            DiarizationAudioVariant.DPDFNET_COMPRESSOR ->
                applyGentleCompressor(applyDpdfNet(dir, audio))
        }
        val preprocessingMs = elapsedMs(preprocessingStarted)

        report("Reverb v1 + ERes2NetV2 · диаризация…")
        val coroutineContext = currentCoroutineContext()
        currentCoroutineContext().ensureActive()
        val diarizationStarted = System.nanoTime()
        val diarization = ReverbEres2NetDiarizer.diarize(
            audio = diarizationAudio,
            modelsDirectory = dir
        ) {
            coroutineContext.ensureActive()
        }
        currentCoroutineContext().ensureActive()
        val diarizationMs = elapsedMs(diarizationStarted)

        report("Сопоставление слов и говорящих · Viterbi…")
        val mergeStarted = System.nanoTime()
        val alignment = WordSpeakerAligner.align(
            words = words.map { WordSpeakerAligner.Word(it.text, it.start, it.end) },
            diarizationSegments = diarization.map {
                WordSpeakerAligner.DiarizationInterval(it.start, it.end, it.speaker)
            }
        )
        val segments = WordSpeakerAligner.buildUtterances(alignment.words)
        require(segments.isNotEmpty()) {
            "Не удалось сформировать реплики из распознанных слов."
        }
        val mergeMs = elapsedMs(mergeStarted)

        TranscriptionOutput(
            segments = segments,
            words = alignment.words,
            speakers = alignment.speakers,
            diarizationAudioVariant = audioVariant.label,
            durationMs = (duration * 1000.0).roundToInt(),
            timings = ProcessingTimings(
                audioDecodeMs = audioDecodeMs,
                melFeaturesMs = melMs,
                gigaamMs = gigaamMs,
                preprocessingMs = preprocessingMs,
                diarizationMs = diarizationMs,
                mergeMs = mergeMs,
                saveMs = 0L,
                totalMs = elapsedMs(totalStarted)
            )
        )
    }

    private fun applyDpdfNet(modelsDirectory: File, audio: FloatArray): FloatArray {
        val model = File(modelsDirectory, "denoiser/dpdfnet2.onnx")
        check(model.isFile && model.length() == 10_249_356L) {
            "Не найдена модель DPDFNet2."
        }

        val denoiser = OfflineSpeechDenoiser(
            config = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(
                        model = model.absolutePath,
                        attenuationLimitDb = 12.0f
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
        )

        try {
            // Pass a copy so denoising can never alter the original ASR waveform.
            val output = denoiser.run(audio.copyOf(), 16_000)
            require(output.sampleRate > 0) {
                "DPDFNet2 вернула некорректную частоту дискретизации."
            }
            val denoised = output.samples
            val resampled = if (output.sampleRate == 16_000) {
                denoised
            } else {
                MonoResampler(output.sampleRate).apply { accept(denoised) }.finish()
            }
            return resampled.copyOf(audio.size)
        } finally {
            denoiser.release()
        }
    }

    /** Port of the notebook's gentle compressor; it does not separate speakers. */
    internal fun applyGentleCompressor(
        input: FloatArray,
        sampleRate: Int = 16_000,
        thresholdDb: Double = -30.0,
        ratio: Double = 2.0,
        maxGainDb: Double = 4.0,
        attackMs: Double = 10.0,
        releaseMs: Double = 150.0
    ): FloatArray {
        val audio = input.copyOf()
        if (audio.isEmpty()) return audio

        val frame = max(1, (sampleRate * 0.020).toInt())
        val hop = max(1, (sampleRate * 0.010).toInt())
        val padded = FloatArray(audio.size + frame)
        audio.copyInto(padded, destinationOffset = frame / 2)

        val rmsValues = mutableListOf<Double>()
        val centers = mutableListOf<Int>()
        var start = 0
        while (start < audio.size) {
            var sum = 0.0
            for (i in 0 until frame) {
                val value = padded[start + i].toDouble()
                sum += value * value
            }
            rmsValues += sqrt(sum / frame + 1e-12)
            centers += min(start + frame / 2, audio.lastIndex)
            start += hop
        }

        val desiredGain = DoubleArray(rmsValues.size) { index ->
            val levelDb = 20.0 * kotlin.math.log10(max(rmsValues[index], 1e-8))
            ((thresholdDb - levelDb) * (1.0 - 1.0 / ratio))
                .coerceIn(0.0, maxGainDb)
        }

        val attackAlpha = exp(-hop / max(1.0, sampleRate * attackMs / 1000.0))
        val releaseAlpha = exp(-hop / max(1.0, sampleRate * releaseMs / 1000.0))
        val smoothedGain = DoubleArray(desiredGain.size)
        var previous = 0.0
        for (index in desiredGain.indices) {
            val target = desiredGain[index]
            val alpha = if (target < previous) attackAlpha else releaseAlpha
            previous = alpha * previous + (1.0 - alpha) * target
            smoothedGain[index] = previous
        }

        val output = FloatArray(audio.size)
        var right = 1.coerceAtMost(centers.lastIndex)
        for (index in audio.indices) {
            val gainDb = when {
                index <= centers.first() -> smoothedGain.first()
                index >= centers.last() -> smoothedGain.last()
                else -> {
                    while (right < centers.lastIndex && index > centers[right]) right++
                    val left = right - 1
                    val width = max(1, centers[right] - centers[left])
                    val fraction = (index - centers[left]).toDouble() / width
                    smoothedGain[left] +
                        (smoothedGain[right] - smoothedGain[left]) * fraction
                }
            }
            output[index] = (
                audio[index] * 10.0.pow(gainDb / 20.0)
            ).toFloat()
        }

        val peak = output.maxOfOrNull { kotlin.math.abs(it.toDouble()) } ?: 0.0
        if (peak > 0.98) {
            val scale = (0.98 / peak).toFloat()
            for (index in output.indices) output[index] *= scale
        }
        return output
    }

    private class Asr(
        dir: File,
        private val tokens: JSONArray
    ) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val sessions = mutableListOf<OrtSession>()

        private fun session(dir: File, part: String): OrtSession =
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                env.createSession(
                    File(dir, "gigaam/v3_e2e_rnnt_$part.onnx").path,
                    options
                ).also { sessions += it }
            }

        private val encoder: OrtSession
        private val decoder: OrtSession
        private val joint: OrtSession

        init {
            try {
                encoder = session(dir, "encoder")
                decoder = session(dir, "decoder")
                joint = session(dir, "joint")
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        private fun floatTensor(a: FloatArray, vararg shape: Long) =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape)

        private fun longTensor(a: LongArray, vararg shape: Long) =
            OnnxTensor.createTensor(env, LongBuffer.wrap(a), shape)

        private fun values(result: OrtSession.Result, index: Int): FloatArray =
            (result[index] as OnnxTensor).floatBuffer.let { buffer ->
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }

        private data class Prediction(
            val dec: FloatArray,
            val h: FloatArray,
            val c: FloatArray
        )

        private fun predict(label: Int, h: FloatArray, c: FloatArray): Prediction {
            longTensor(longArrayOf(label.toLong()), 1, 1).use { x ->
                floatTensor(h, 1, 1, 320).use { hi ->
                    floatTensor(c, 1, 1, 320).use { ci ->
                        decoder.run(mapOf("x" to x, "hi" to hi, "ci" to ci)).use { result ->
                            return Prediction(
                                values(result, 0),
                                values(result, 1),
                                values(result, 2)
                            )
                        }
                    }
                }
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
                    encoder.run(mapOf("audio_signal" to x, "length" to n)).use { result ->
                        encoded = values(result, 0)
                        stride = (result[0].info as TensorInfo).shape.last().toInt()
                        length = (result[1] as OnnxTensor).intBuffer.get(0)
                    }
                }
            }

            require(length > 0 && length <= stride) {
                "Модель вернула неверную длину аудио."
            }

            val blankId = tokens.length()
            var prediction = predict(blankId, FloatArray(320), FloatArray(320))
            val ids = mutableListOf<Int>()
            val times = mutableListOf<Int>()

            for (frame in 0 until length) {
                currentCoroutineContext().ensureActive()
                val enc = FloatArray(768) { encoded[it * stride + frame] }
                for (symbol in 0 until 10) {
                    val label = floatTensor(enc, 1, 768, 1).use { x ->
                        floatTensor(prediction.dec, 1, 320, 1).use { decoderOutput ->
                            joint.run(mapOf("enc" to x, "dec" to decoderOutput)).use { result ->
                                val logits = values(result, 0)
                                logits.indices.maxByOrNull { logits[it] }!!
                            }
                        }
                    }
                    if (label == blankId) break
                    ids += label
                    times += frame
                    prediction = predict(label, prediction.h, prediction.c)
                }
            }

            val step = audio.size / 16_000.0 / length
            val words = mutableListOf<Word>()
            val text = StringBuilder()
            var first = 0
            var last = 0

            fun commit() {
                val value = text.toString().trim()
                if (value.isNotEmpty()) {
                    words += Word(value, first * step, (last + 1) * step)
                }
                text.clear()
            }

            for (index in ids.indices) {
                var piece = tokens.getString(ids[index])
                if (piece.startsWith("▁")) {
                    commit()
                    piece = piece.drop(1)
                } else if (piece == " ") {
                    commit()
                    continue
                }
                if (text.isEmpty()) first = times[index]
                last = times[index]
                text.append(piece)
            }
            commit()

            return RecognitionResult(
                words = words,
                melMs = melMs,
                inferenceMs = elapsedMs(inferenceStarted)
            )
        }

        override fun close() {
            sessions.asReversed().forEach { it.close() }
            sessions.clear()
        }
    }
}
