package com.example.audiotranscriber.model

data class SpeakerSummary(
    val speakerId: Int,
    val firstSeenMs: Int
)

data class WordSpeakerAssignment(
    val index: Int,
    val text: String,
    val startMs: Int,
    val endMs: Int,
    val speakerRawId: Int,
    val speakerContextId: Int,
    val confidence: Float
)
