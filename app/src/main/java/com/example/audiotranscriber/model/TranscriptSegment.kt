package com.example.audiotranscriber.model

data class TranscriptSegment(
    val speakerId: Int,
    val startMs: Int,
    val endMs: Int,
    val text: String
)

fun formatAudioTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

