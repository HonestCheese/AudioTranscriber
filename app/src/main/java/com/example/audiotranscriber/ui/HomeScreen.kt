package com.example.audiotranscriber.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audiotranscriber.model.formatAudioTime

@Composable
fun HomeScreen(
    fileName: String, isRecording: Boolean, recordingSeconds: Long,
    playerReady: Boolean, isPlaying: Boolean, position: Int, duration: Int,
    status: String, hasAudio: Boolean, onRecord: () -> Unit, onImport: () -> Unit,
    onPlayPause: () -> Unit, onSeek: (Int) -> Unit, onTranscript: () -> Unit,
    isProcessing: Boolean, modelsReady: Boolean, onInstallModels: () -> Unit, onCancel: () -> Unit,
    speakerCount: Int, onSpeakerCount: (Int) -> Unit
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(position, dragging) { if (!dragging) sliderPosition = position.toFloat() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Аудио", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Загрузите запись или создайте новую", color = AppTextMuted)
        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = AppSurface),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("ИСТОЧНИК", style = MaterialTheme.typography.labelMedium, color = AppTextMuted)
                Button(onClick = onImport, enabled = !isRecording && !isProcessing, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)) { Text("Выбрать аудиофайл") }
                OutlinedButton(onClick = onRecord, enabled = !isProcessing, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
                    Text(if (isRecording) "■  Остановить запись" else "●  Начать запись")
                }
                if (isRecording) Text("Запись  %02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60),
                    color = MaterialTheme.colorScheme.error)
            }
        }
        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = AppSurface),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ТЕКУЩИЙ ФАЙЛ", style = MaterialTheme.typography.labelMedium, color = AppTextMuted)
                Text(fileName, style = MaterialTheme.typography.titleMedium)
                if (playerReady && !isRecording) {
                    Slider(value = sliderPosition.coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                        onValueChange = { dragging = true; sliderPosition = it },
                        onValueChangeFinished = { onSeek(sliderPosition.toInt()); dragging = false },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatAudioTime(if (dragging) sliderPosition.toInt() else position), color = AppTextMuted)
                        Text(formatAudioTime(duration), color = AppTextMuted)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onSeek((position - 10000).coerceAtLeast(0)) }) { Text("−10 c") }
                        Button(onClick = onPlayPause, shape = RoundedCornerShape(10.dp)) {
                            Text(if (isPlaying) "Пауза" else "▶ Играть")
                        }
                        TextButton(onClick = { onSeek((position + 10000).coerceAtMost(duration)) }) { Text("+10 c") }
                    }
                } else if (!isRecording) Text("Здесь появится плеер", color = AppTextMuted)
            }
        }
        if (!modelsReady) {
            Text("Один раз установите модели из архива Colab или семи частей android_models.part01–07.", color = AppTextMuted)
            OutlinedButton(onClick = onInstallModels, enabled = !isProcessing && !isRecording,
                modifier = Modifier.fillMaxWidth()) { Text("Установить модели") }
        }
        Text("Количество говорящих", style = MaterialTheme.typography.labelMedium, color = AppTextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-1 to "Авто", 1 to "1", 2 to "2", 3 to "3", 4 to "4").forEach { (count, label) ->
                FilterChip(selected = speakerCount == count, onClick = { onSpeakerCount(count) },
                    enabled = !isProcessing, label = { Text(label) })
            }
        }
        if (status.isNotBlank()) Text(status, color = AppTextMuted, style = MaterialTheme.typography.bodySmall)
        if (isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отменить") }
        }
        Button(onClick = onTranscript, enabled = hasAudio && !isRecording && !isProcessing && modelsReady,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(16.dp)) {
            Text("Создать расшифровку")
        }
        Text("GigaAM + Pyannote / Titanet. Аудио обрабатывается на телефоне без отправки на сервер. Оставьте приложение открытым до завершения.",
            style = MaterialTheme.typography.bodySmall, color = AppTextMuted)
    }
}
