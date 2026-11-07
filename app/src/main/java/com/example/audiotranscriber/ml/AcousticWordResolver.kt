package com.example.audiotranscriber.ml

import com.example.audiotranscriber.model.WordSpeakerEvidence
import kotlin.math.ln

/** Acoustic fallback: smooth isolated one-word switches without changing the ASR. */
internal object AcousticWordResolver {
    fun resolve(words: List<WordSpeakerEvidence>): IntArray {
        val chosen = IntArray(words.size) { -1 }
        var begin = 0
        while (begin < words.size) {
            if (words[begin].candidates.isEmpty()) { begin++; continue }
            var end = begin + 1
            while (end < words.size && words[end].candidates.isNotEmpty()) end++
            val paths = mutableListOf<Map<Int, Double>>()
            val back = mutableListOf<Map<Int, Int>>()
            for (index in begin until end) {
                val current = mutableMapOf<Int, Double>()
                val parents = mutableMapOf<Int, Int>()
                val word = words[index]
                for (option in word.candidates) {
                    val emission = ln(option.relativeScore.toDouble().coerceAtLeast(1e-7))
                    if (index == begin) { current[option.speakerId] = emission; continue }
                    val gap = word.startMs - words[index - 1].endMs
                    val switchPenalty = if (gap > 800) 0.04 else 0.35
                    val prior = paths.last().maxByOrNull { (previous, score) ->
                        score - if (previous == option.speakerId) 0.0 else switchPenalty
                    }!!
                    parents[option.speakerId] = prior.key
                    current[option.speakerId] = emission + prior.value -
                        if (prior.key == option.speakerId) 0.0 else switchPenalty
                }
                paths.add(current)
                back.add(parents)
            }
            var speaker = paths.last().maxByOrNull { it.value }!!.key
            for (index in end - 1 downTo begin) {
                chosen[index] = speaker
                if (index > begin) speaker = back[index - begin][speaker] ?: speaker
            }
            begin = end
        }
        return chosen
    }

    /** Reject unsupported LLM guesses and strong-acoustic contradictions. */
    fun accept(word: WordSpeakerEvidence, acoustic: Int, proposal: Int): Int {
        if (proposal == acoustic || proposal < 0) return acoustic
        val rank = word.candidates.indexOfFirst { it.speakerId == proposal }
        if (rank !in 0..1 || word.candidates.size < 2) return acoustic
        if (word.candidates[0].relativeScore >= 0.82f && word.acousticReliability >= 0.36f) return acoustic
        if (word.candidates[rank].relativeScore < 0.20f) return acoustic
        return proposal
    }
}
