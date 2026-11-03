package com.example.audiotranscriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audiotranscriber.data.SavedTranscript
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkspaceScreen(
    transcripts: List<SavedTranscript>,
    onOpen: (SavedTranscript) -> Unit,
    onDelete: (SavedTranscript) -> Unit,
    onGoToAudio: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Обработка", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Ваши расшифровки хранятся на устройстве", color = AppTextMuted)
        if (transcripts.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = AppSurface),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пока нет расшифровок", style = MaterialTheme.typography.titleMedium)
                    Text("Откройте вкладку «Аудио», выберите запись и создайте первую расшифровку.", color = AppTextMuted)
                    Button(onClick = onGoToAudio, shape = RoundedCornerShape(10.dp)) { Text("Перейти к аудио") }
                }
            }
        } else {
            Text("ИСТОРИЯ · ${transcripts.size}", style = MaterialTheme.typography.labelMedium, color = AppTextMuted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(transcripts, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(item) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.fileName, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.id))} · ${item.segments.size} реплик",
                                    style = MaterialTheme.typography.bodySmall, color = AppTextMuted)
                                Text("Открыть расшифровку →", color = AppAccent, style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { onDelete(item) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}
