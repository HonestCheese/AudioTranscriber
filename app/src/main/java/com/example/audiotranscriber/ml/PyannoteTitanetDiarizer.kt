package com.example.audiotranscriber.ml

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Hybrid offline diarization:
 * Pyannote Segmentation 3.0 detects speaker activity,
 * NeMo Titanet Small extracts speaker embeddings,
 * sherpa-onnx clusters embeddings into speaker identities.
 */
internal object PyannoteTitanetDiarizer {
    data class Interval(
        val start: Double,
        val end: Double,
        val speaker: Int,
        val confidence: Float
    )

    private const val SAMPLE_RATE = 16_000
    private const val WINDOW_SHIFT_RATIO = 0.1f
    private const val CLUSTER_THRESHOLD = 0.5f
    private const val MIN_DURATION_ON = 0.3f
    private const val MIN_DURATION_OFF = 0.5f

    fun diarize(
        audio: FloatArray,
        modelsDirectory: File,
        requestedSpeakers: Int,
        checkCancelled: () -> Unit
    ): List<Interval> {
        require(audio.isNotEmpty()) { "Нет аудио для диаризации" }
        require(requestedSpeakers == -1 || requestedSpeakers > 0)

        val segmentationModel = File(modelsDirectory, "diarization/segmentation.onnx")
        val embeddingModel = File(modelsDirectory, "diarization/titanet.onnx")

        require(segmentationModel.isFile && segmentationModel.length() > 1_000_000L) {
            "Не найдена Pyannote Segmentation 3.0. Переустановите комплект моделей."
        }
        require(embeddingModel.isFile && embeddingModel.length() > 10_000_000L) {
            "Не найдена NeMo Titanet Small. Переустановите комплект моделей."
        }

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = segmentationModel.absolutePath,
                    windowShiftRatio = WINDOW_SHIFT_RATIO
                ),
                numThreads = 2,
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
                numClusters = if (requestedSpeakers > 0) requestedSpeakers else -1,
                threshold = CLUSTER_THRESHOLD,
                computeConfidence = true
            ),
            minDurationOn = MIN_DURATION_ON,
            minDurationOff = MIN_DURATION_OFF
        )

        checkCancelled()
        val diarizer = OfflineSpeakerDiarization(config = config)
        try {
            require(diarizer.sampleRate() == SAMPLE_RATE) {
                "Pyannote/Titanet ожидает 16 kHz, получено: ${diarizer.sampleRate()} Hz"
            }
            checkCancelled()
            return diarizer.processWithCallback(audio) { _, _, _ ->
                checkCancelled()
                0
            }
                .asSequence()
                .filter { it.end > it.start && it.speaker >= 0 }
                .map {
                    Interval(
                        start = it.start.toDouble(),
                        end = it.end.toDouble(),
                        speaker = it.speaker,
                        confidence = it.confidence
                    )
                }
                .sortedBy { it.start }
                .toList()
        } finally {
            diarizer.release()
        }
    }
}
