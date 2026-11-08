package com.example.audiotranscriber.model

data class SpeakerSummary(
    val speakerId: Int,
    val firstSeenMs: Int,
    val source: String = "diarization_cluster",
    val profileId: String? = null
)

data class WordSpeakerAssignment(
    val index: Int,
    val text: String,
    val startMs: Int,
    val endMs: Int,
    val speakerRawId: Int,
    val speakerContextId: Int,
    val confidence: Float,
    val assignmentSource: String = "diarization_cluster"
)

