package com.example.audiotranscriber.ml

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * The notebook's exact diarization stack:
 * Reverb v1 segmentation + 3D-Speaker ERes2NetV2 + FastClustering.
 */
internal object ReverbEres2NetDiarizer {
    data class Interval(
        val start: Double,
        val end: Double,
        val speaker: Int
    )

    private const val SAMPLE_RATE = 16_000
    private const val WINDOW_SHIFT_RATIO = 0.1f
    private const val CLUSTER_THRESHOLD = 0.63f
    private const val MIN_DURATION_ON = 0.3f
    private const val MIN_DURATION_OFF = 0.5f

    fun diarize(
        audio: FloatArray,
        modelsDirectory: File,
        checkCancelled: () -> Unit
    ): List<Interval> {
        require(audio.isNotEmpty()) { "Нет аудио для диаризации" }

        val reverbModel = File(modelsDirectory, "diarization/reverb/model.onnx")
        val embeddingModel = File(modelsDirectory, "diarization/eres2netv2.onnx")

        require(reverbModel.isFile && reverbModel.length() > 1_000_000L) {
            "Не найдена модель Reverb v1."
        }
        require(embeddingModel.isFile && embeddingModel.length() == 71_441_526L) {
            "Не найдена или повреждена модель 3D-Speaker ERes2NetV2."
        }

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = reverbModel.absolutePath,
                    windowShiftRatio = WINDOW_SHIFT_RATIO
                ),
                numThreads = 1,
                debug = false,
                provider = "cpu"
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModel.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            clustering = FastClusteringConfig(
                numClusters = -1,
                threshold = CLUSTER_THRESHOLD,
                computeConfidence = false
            ),
            minDurationOn = MIN_DURATION_ON,
            minDurationOff = MIN_DURATION_OFF
        )

        checkCancelled()
        val diarizer = OfflineSpeakerDiarization(config = config)
        try {
            require(diarizer.sampleRate() == SAMPLE_RATE) {
                "Reverb/ERes2NetV2 ожидает 16 kHz, получено: ${diarizer.sampleRate()} Hz"
            }
            checkCancelled()
            val diarizationResult = diarizer.processWithCallback(
                samples = audio,
                callback = { _: Int, _: Int, _: Long ->
                    checkCancelled()
                    0
                }
            )
            return diarizationResult.asSequence()
                .filter { it.end > it.start && it.speaker >= 0 }
                .map {
                    Interval(
                        start = it.start.toDouble(),
                        end = it.end.toDouble(),
                        speaker = it.speaker
                    )
                }
                .sortedBy { it.start }
                .toList()
        } finally {
            diarizer.release()
        }
    }
}
