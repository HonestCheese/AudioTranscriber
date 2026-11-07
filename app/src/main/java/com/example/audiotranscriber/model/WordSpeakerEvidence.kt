package com.example.audiotranscriber.model

/** Relative score is softmax-normalized cosine similarity, NOT probability. */
data class VoiceCandidate(
    val speakerId: Int,
    val cosineSimilarity: Float,
    val relativeScore: Float
)

data class WordSpeakerEvidence(
    val index: Int,
    val text: String,
    val startMs: Int,
    val endMs: Int,
    val candidates: List<VoiceCandidate>,
    val acousticReliability: Float,
    val selectedSpeakerId: Int,
    val changedByLlm: Boolean = false
)
