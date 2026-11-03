package com.example.audiotranscriber.model

data class TranscriptSegment(
    val speakerId: Int,
    val startMs: Int,
    val endMs: Int,
    val text: String
)

// Демонстрационные данные. Позже заменим результатом ML.
val demoTranscript = listOf(
    TranscriptSegment(0, 0, 2800, "Привет! Как дела?"),
    TranscriptSegment(1, 3000, 5900, "Привет, всё отлично. А у тебя?"),
    TranscriptSegment(0, 6200, 10000, "Тоже хорошо. Давай обсудим наш проект."),
    TranscriptSegment(1, 10400, 14800, "Давай! Начнём с распознавания речи.")
)

fun formatAudioTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
