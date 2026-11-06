package com.example.audiotranscriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audiotranscriber.data.SavedTranscript
import com.example.audiotranscriber.model.formatAudioTime

@Composable
fun TranscriptScreen(
    transcript: SavedTranscript,
    onBack: () -> Unit,
    onSaveNames: (Map<Int, String>) -> Unit,
    onSegmentClick: (Int) -> Unit
) {
    var names by remember(transcript.id) { mutableStateOf(transcript.speakerNames) }
    var editing by remember { mutableIntStateOf(-1) }
    var editedName by remember { mutableStateOf("") }
    val speakers = transcript.segments.map { it.speakerId }.filter { it >= 0 }.distinct().sorted()

    if (editing >= 0) AlertDialog(
        onDismissRequest = { editing = -1 },
        title = { Text("Имя участника") },
        text = { OutlinedTextField(value = editedName, onValueChange = { editedName = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            if (editedName.isNotBlank()) {
                names = names + (editing to editedName.trim())
                onSaveNames(names)
            }
            editing = -1
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { editing = -1 }) { Text("Отмена") } }
    )

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("← К истории") }
        Text("Расшифровка", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(transcript.fileName, color = AppTextMuted)
        Text(if (transcript.engine == "legacy-demo") "Демонстрационная запись из прежней версии" else "Локальная расшифровка · время слов приблизительное", style = MaterialTheme.typography.labelSmall, color = AppAccent)
        transcript.processingTimings?.let { timings ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("ВРЕМЯ ОБРАБОТКИ", style = MaterialTheme.typography.labelMedium, color = AppTextMuted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Всего", fontWeight = FontWeight.SemiBold)
                        Text(formatElapsed(timings.totalMs), color = AppAccent, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TimingRow("Подготовка аудио", timings.audioDecodeMs)
                    TimingRow("Mel-признаки", timings.melFeaturesMs)
                    TimingRow("GigaAM · распознавание", timings.gigaamMs)
                    TimingRow("Pyannote + Titanet", timings.diarizationMs)
                    TimingRow("Объединение реплик", timings.mergeMs)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            speakers.forEach { speaker ->
                OutlinedButton(onClick = {
                    editing = speaker
                    editedName = names[speaker] ?: if (speaker < 0) "Не определён" else "Участник ${speaker + 1}"
                }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                    Text(names[speaker] ?: if (speaker < 0) "Не определён" else "Участник ${speaker + 1}", maxLines = 1)
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(transcript.segments) { segment ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onSegmentClick(segment.startMs) },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(names[segment.speakerId] ?: if (segment.speakerId < 0) "Не определён" else "Участник ${segment.speakerId + 1}",
                                color = if (segment.speakerId % 2 == 0) AppAccent else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold)
                            Text(formatAudioTime(segment.startMs), color = AppTextMuted)
                        }
                        Text(segment.text)
                        Text("${formatAudioTime(segment.startMs)} – ${formatAudioTime(segment.endMs)} · нажмите для воспроизведения",
                            style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                    }
                }
            }
        }
    }
}


@Composable
private fun TimingRow(label: String, milliseconds: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppTextMuted, style = MaterialTheme.typography.bodySmall)
        Text(formatElapsed(milliseconds), style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatElapsed(milliseconds: Long): String =
    if (milliseconds < 1000L) "${milliseconds} мс" else "%.2f с".format(milliseconds / 1000.0)
