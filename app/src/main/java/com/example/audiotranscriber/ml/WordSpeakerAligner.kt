package com.example.audiotranscriber.ml

import com.example.audiotranscriber.model.SpeakerSummary
import com.example.audiotranscriber.model.TranscriptSegment
import com.example.audiotranscriber.model.WordSpeakerAssignment
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Word-to-speaker alignment ported from the final ML notebook:
 * temporal overlap scores followed by conservative Viterbi smoothing.
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

    private const val CONTEXT_UTTERANCE_GAP = 0.8
    private const val CONTEXT_SWITCH_PENALTY = 0.7
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

        for (group in splitWordGroups(enriched)) {
            val run = mutableListOf<MutableWord>()

            fun flush() {
                if (run.isEmpty()) return
                val smoothed = viterbiGroup(run)
                run.forEachIndexed { localIndex, word ->
                    word.speakerContext = smoothed[localIndex]
                }
                run.clear()
            }

            for (index in group) {
                val word = enriched[index]
                if (word.scores.isNotEmpty()) {
                    run += word
                } else {
                    flush()
                    word.speakerContext = -1
                }
            }
            flush()
        }

        val assignments = enriched.map { word ->
            WordSpeakerAssignment(
                index = word.index,
                text = word.word.text,
                startMs = (word.word.start * 1000.0).roundToInt(),
                endMs = (word.word.end * 1000.0).roundToInt(),
                speakerRawId = word.speakerRaw,
                speakerContextId = word.speakerContext,
                confidence = word.confidence.toFloat()
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

        return Result(assignments, speakers)
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

    private fun splitWordGroups(words: List<MutableWord>): List<List<Int>> {
        val groups = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (index in words.indices) {
            if (current.isNotEmpty()) {
                val previous = words[current.last()].word
                val next = words[index].word
                val gap = next.start - previous.end
                val sentenceEnd = previous.text.trim().lastOrNull()?.let { it in setOf('.', '!', '?', '…') } == true
                if (gap > CONTEXT_UTTERANCE_GAP || sentenceEnd) {
                    groups += current
                    current = mutableListOf()
                }
            }
            current += index
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun viterbiGroup(words: List<MutableWord>): List<Int> {
        val states = words.flatMap { it.scores.keys }.distinct().sorted()
        if (states.isEmpty()) return List(words.size) { -1 }

        val n = words.size
        val k = states.size
        val dp = Array(n) { DoubleArray(k) { Double.NEGATIVE_INFINITY } }
        val back = Array(n) { IntArray(k) }

        for (stateIndex in 0 until k) {
            val total = words[0].scores.values.sum()
            val probability = if (total > 0.0) {
                (words[0].scores[states[stateIndex]] ?: 0.0) / total
            } else 0.0
            dp[0][stateIndex] = ln(max(probability, 1e-8))
        }

        for (t in 1 until n) {
            val total = words[t].scores.values.sum()
            for (stateIndex in 0 until k) {
                val probability = if (total > 0.0) {
                    (words[t].scores[states[stateIndex]] ?: 0.0) / total
                } else 0.0
                val emission = ln(max(probability, 1e-8))
                var bestPrevious = 0
                var bestScore = Double.NEGATIVE_INFINITY
                for (previousIndex in 0 until k) {
                    val transition = if (previousIndex == stateIndex) 0.0 else -CONTEXT_SWITCH_PENALTY
                    val candidate = dp[t - 1][previousIndex] + transition
                    if (candidate > bestScore) {
                        bestScore = candidate
                        bestPrevious = previousIndex
                    }
                }
                dp[t][stateIndex] = bestScore + emission
                back[t][stateIndex] = bestPrevious
            }
        }

        var state = (0 until k).maxByOrNull { dp[n - 1][it] } ?: 0
        val path = IntArray(n)
        path[n - 1] = state
        for (t in n - 1 downTo 1) {
            state = back[t][state]
            path[t - 1] = state
        }

        return words.mapIndexed { index, word ->
            if ((word.scores[states[path[index]]] ?: 0.0) < MIN_WORD_OVERLAP) {
                -1
            } else {
                states[path[index]]
            }
        }
    }
}
