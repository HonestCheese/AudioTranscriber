package com.example.audiotranscriber.ml

import com.example.audiotranscriber.model.SpeakerSummary
import com.example.audiotranscriber.model.TranscriptSegment
import com.example.audiotranscriber.model.WordSpeakerAssignment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Word-to-speaker alignment ported from the final ML notebook:
 * raw temporal overlap; Viterbi is disabled in the final baseline.
 */
internal object WordSpeakerAligner {
    data class Word(
        val text: String,
        val start: Double,
        val end: Double
    )

    data class DiarizationInterval(
        val start: Double,
        val end: Double,
        val speaker: Int
    )

    data class Result(
        val words: List<WordSpeakerAssignment>,
        val speakers: List<SpeakerSummary>
    )

    private const val MIN_WORD_OVERLAP = 0.15
    private const val MAX_PAUSE = 0.9

    fun align(
        words: List<Word>,
        diarizationSegments: List<DiarizationInterval>
    ): Result {
        val stableSpeakerMap = makeStableSpeakerMap(diarizationSegments)
        val ordered = words.sortedWith(compareBy<Word> { it.start }.thenBy { it.end })
        val enriched = ordered.mapIndexed { index, word ->
            val scores = getWordScores(word, diarizationSegments, stableSpeakerMap)
            val best = scores.maxByOrNull { it.value }
            val confidence = best?.value ?: 0.0
            val raw = if (confidence >= MIN_WORD_OVERLAP) best!!.key else -1
            MutableWord(index, word, raw, raw, confidence, scores)
        }.toMutableList()

        val assignments = enriched.map { word ->
            WordSpeakerAssignment(
                index = word.index,
                text = word.word.text,
                startMs = (word.word.start * 1000.0).roundToInt(),
                endMs = (word.word.end * 1000.0).roundToInt(),
                speakerRawId = word.speakerRaw,
                speakerContextId = word.speakerContext,
                confidence = word.confidence.toFloat(),
                assignmentSource = if (word.speakerRaw < 0) "unknown" else "diarization_cluster"
            )
        }

        val speakers = stableSpeakerMap.entries
            .mapNotNull { (modelId, stableId) ->
                val firstSeen = diarizationSegments
                    .filter { it.speaker == modelId }
                    .minOfOrNull { it.start } ?: return@mapNotNull null
                SpeakerSummary(stableId, (firstSeen * 1000.0).roundToInt())
            }
            .sortedBy { it.firstSeenMs }

        val ids = assignments.map { it.speakerRawId }.filter { it >= 0 }.distinct()
            .withIndex().associate { it.value to it.index }
        val finalWords = assignments.map { it.copy(
            speakerRawId = ids[it.speakerRawId] ?: -1,
            speakerContextId = ids[it.speakerRawId] ?: -1
        ) }
        val finalSpeakers = ids.map { (_, id) ->
            SpeakerSummary(id, finalWords.first { it.speakerRawId == id }.startMs)
        }
        return Result(finalWords, finalSpeakers)
    }

    fun buildUtterances(words: List<WordSpeakerAssignment>): List<TranscriptSegment> {
        if (words.isEmpty()) return emptyList()

        val ordered = words.sortedWith(compareBy<WordSpeakerAssignment> { it.startMs }.thenBy { it.endMs })
        val output = mutableListOf<TranscriptSegment>()
        var speaker = ordered.first().speakerContextId
        var start = ordered.first().startMs
        var end = ordered.first().endMs
        val text = StringBuilder(ordered.first().text.trim())

        for (word in ordered.drop(1)) {
            val token = word.text.trim()
            if (token.isEmpty()) continue
            val pause = (word.startMs - end) / 1000.0
            if (word.speakerContextId != speaker || pause > MAX_PAUSE) {
                output += TranscriptSegment(speaker, start, end, text.toString())
                speaker = word.speakerContextId
                start = word.startMs
                end = word.endMs
                text.setLength(0)
                text.append(token)
            } else {
                end = max(end, word.endMs)
                if (text.isNotEmpty()) text.append(' ')
                text.append(token)
            }
        }
        output += TranscriptSegment(speaker, start, end, text.toString())
        return output
    }

    private data class MutableWord(
        val index: Int,
        val word: Word,
        val speakerRaw: Int,
        var speakerContext: Int,
        val confidence: Double,
        val scores: Map<Int, Double>
    )

    private fun makeStableSpeakerMap(
        segments: List<DiarizationInterval>
    ): Map<Int, Int> {
        val firstSeen = mutableMapOf<Int, Double>()
        for (segment in segments) {
            if (segment.speaker >= 0) {
                firstSeen[segment.speaker] = min(
                    firstSeen[segment.speaker] ?: Double.POSITIVE_INFINITY,
                    segment.start
                )
            }
        }
        return firstSeen.entries
            .sortedBy { it.value }
            .mapIndexed { stableId, entry -> entry.key to stableId }
            .toMap()
    }

    private fun getWordScores(
        word: Word,
        segments: List<DiarizationInterval>,
        speakerMap: Map<Int, Int>
    ): Map<Int, Double> {
        val duration = max(1e-4, word.end - word.start)
        val overlap = mutableMapOf<Int, Double>()
        for (segment in segments) {
            val stableSpeaker = speakerMap[segment.speaker] ?: continue
            val amount = max(
                0.0,
                min(word.end, segment.end) - max(word.start, segment.start)
            )
            if (amount > 0.0) {
                overlap[stableSpeaker] = (overlap[stableSpeaker] ?: 0.0) + amount
            }
        }
        return overlap.mapValues { (_, amount) -> min(1.0, amount / duration) }
    }

}
