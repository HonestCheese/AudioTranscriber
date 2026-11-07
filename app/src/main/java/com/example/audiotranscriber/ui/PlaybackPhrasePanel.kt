package com.example.audiotranscriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.audiotranscriber.data.SavedTranscript
import com.example.audiotranscriber.model.TranscriptSegment

/** Half-open intervals keep pauses blank and switch phrases exactly at a boundary. */
internal fun currentPlaybackPhrase(
    segments: List<TranscriptSegment>, positionMs: Int
): TranscriptSegment? = segments.firstOrNull {
    positionMs >= it.startMs && positionMs < it.endMs
}

@Composable
internal fun PlaybackPhrasePanel(transcript: SavedTranscript?, positionMs: Int) {
    val phrase = transcript?.let { currentPlaybackPhrase(it.segments, positionMs) }
    val words = remember(transcript?.id, transcript?.wordAssignments, phrase) {
        if (transcript == null || phrase == null) emptyList()
        else transcript.wordAssignments.filter {
            it.speakerContextId == phrase.speakerId &&
                it.startMs >= phrase.startMs && it.startMs < phrase.endMs
        }.sortedWith(compareBy({ it.startMs }, { it.index }))
    }
    AppPanel {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 76.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("ПОТОК", color = AppAccent, style = MaterialTheme.typography.labelMedium)
            when {
                transcript == null -> Text(
                    "Создайте расшифровку, затем включите воспроизведение.",
                    color = AppTextMuted
                )
                phrase == null -> Text("Пауза · сейчас нет реплики", color = AppTextMuted)
                else -> {
                    Text(
                        transcript.speakerNames[phrase.speakerId]
                            ?: if (phrase.speakerId < 0) "Не определён" else "Участник ${phrase.speakerId + 1}",
                        color = AppTextMuted,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (words.isEmpty()) {
                        Text(phrase.text, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "В этой расшифровке нет временных меток слов. Обработайте запись заново для подсветки.",
                            color = AppTextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            buildAnnotatedString {
                                words.forEachIndexed { index, word ->
                                    if (index > 0) append(" ")
                                    withStyle(SpanStyle(
                                        color = if (positionMs >= word.startMs) AppAccent else AppText
                                    )) { append(word.text) }
                                }
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}
