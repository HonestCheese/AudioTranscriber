package com.example.audiotranscriber.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audiotranscriber.data.SavedTranscript
import com.example.audiotranscriber.model.formatAudioTime
import com.example.audiotranscriber.ml.DiarizationAudioVariant

@Composable
fun HomeScreen(
    fileName: String,
    isRecording: Boolean,
    recordingSeconds: Long,
    playerReady: Boolean,
    isPlaying: Boolean,
    position: Int,
    duration: Int,
    status: String,
    hasAudio: Boolean,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onTranscript: () -> Unit,
    isProcessing: Boolean,
    modelsReady: Boolean,
    onInstallModels: () -> Unit,
    audioVariant: DiarizationAudioVariant,
    onAudioVariantChange: (DiarizationAudioVariant) -> Unit,
    onCancel: () -> Unit,
    profiles: Map<String, String>,
    onAddProfile: () -> Unit,
    onDeleteProfile: (String) -> Unit,
    transcript: SavedTranscript?,
    onSaveNames: (Map<Int, String>) -> Unit,
    onSegmentClick: (Int) -> Unit,
    onExport: () -> Unit
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var streamVisible by remember(fileName, transcript?.id) { mutableStateOf(false) }
    LaunchedEffect(position, dragging) {
        if (!dragging) sliderPosition = position.toFloat()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Аудио", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Запись, прослушивание и расшифровка",
                color = AppTextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AppPanel {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AppEyebrow("ИСТОЧНИК АУДИО")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onImport,
                        enabled = !isRecording && !isProcessing,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Выбрать файл", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = onRecord,
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isRecording) MaterialTheme.colorScheme.error else AppText
                        )
                    ) {
                        Text(
                            if (isRecording) "Остановить" else "●  Записать",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                if (isRecording) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error) {
                            Spacer(Modifier.size(8.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Идёт запись  ·  %02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        AppPanel {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppEyebrow("ТЕКУЩИЙ ФАЙЛ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2
                        )
                        Text(
                            if (playerReady) "Готов к воспроизведению" else "Аудиоплеер",
                            color = AppTextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = AppAccentSoft
                    ) {
                        Text(
                            "♫",
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            color = AppAccent,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                if (playerReady && !isRecording) {
                    Slider(
                        value = sliderPosition.coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                        onValueChange = {
                            dragging = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            onSeek(sliderPosition.toInt())
                            dragging = false
                        },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatAudioTime(if (dragging) sliderPosition.toInt() else position),
                            color = AppTextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            formatAudioTime(duration),
                            color = AppTextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { onSeek((position - 10000).coerceAtLeast(0)) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(44.dp)
                        ) { Text("−10", style = MaterialTheme.typography.labelMedium) }

                        Spacer(Modifier.width(20.dp))

                        Button(
                            onClick = onPlayPause,
                            shape = CircleShape,
                            modifier = Modifier.size(62.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                if (isPlaying) "Ⅱ" else "▶",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        Spacer(Modifier.width(20.dp))

                        FilledTonalButton(
                            onClick = { onSeek((position + 10000).coerceAtMost(duration)) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(44.dp)
                        ) { Text("+10", style = MaterialTheme.typography.labelMedium) }
                    }
                } else if (!isRecording) {
                    Text(
                        "Выберите аудиофайл или начните запись, чтобы открыть плеер.",
                        color = AppTextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { streamVisible = !streamVisible },
            enabled = hasAudio && !isRecording,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (streamVisible) AppAccent else AppText
            )
        ) { Text(if (streamVisible) "Поток · выключить" else "Поток") }

        if (streamVisible) {
            PlaybackPhrasePanel(
                transcript = transcript,
                positionMs = if (dragging) sliderPosition.toInt() else position
            )
        }

        if (!modelsReady) {
            AppPanel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Подготовка моделей", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Все модели входят в APK. При первом запуске они автоматически подготавливаются в хранилище телефона.",
                        color = AppTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = onInstallModels,
                        enabled = !isProcessing && !isRecording,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Подготовить модели") }
                }
            }
        }

        AppPanel {
            Text("Голосовые профили", style = MaterialTheme.typography.titleMedium)
            Text("Необязательно: добавьте 2–4 отдельные чистые записи голоса одного человека, каждая от 2.5 секунды. Профили хранятся на телефоне.", color = AppTextMuted,
                style = MaterialTheme.typography.bodySmall)
            profiles.forEach { (id, name) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDeleteProfile(id) }, enabled = !isProcessing && !isRecording) { Text("Удалить") }
                }
            }
            OutlinedButton(onClick = onAddProfile, enabled = !isProcessing && !isRecording,
                modifier = Modifier.fillMaxWidth()) { Text("Добавить голосовой профиль") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Распознавание", style = MaterialTheme.typography.titleMedium)
                Text("Автоматическое определение голосов", color = AppTextMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            Surface(shape = RoundedCornerShape(50), color = AppAccentSoft) {
                Text(
                    "AUTO",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = AppAccent,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (status.isNotBlank()) {
            AppPanel {
                Text(status, color = AppTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (isProcessing) {
            AppPanel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Обработка аудио", style = MaterialTheme.typography.titleMedium)
                        Text("В процессе", color = AppAccent, style = MaterialTheme.typography.labelMedium)
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Отменить обработку") }
                }
            }
        }

        Button(
            onClick = onTranscript,
            enabled = hasAudio && !isRecording && !isProcessing && modelsReady,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Создать расшифровку", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            "Все модели включены в приложение. Обработка аудио работает полностью локально без интернета.",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextMuted
        )

        HorizontalDivider(color = AppOutline)

        if (transcript != null) {
            TranscriptScreen(
                transcript = transcript,
                onSaveNames = onSaveNames,
                onSegmentClick = onSegmentClick,
                onExport = onExport
            )
        } else {
            AppPanel {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Расшифровка появится здесь", fontWeight = FontWeight.SemiBold)
                    Text(
                        "После обработки результат откроется прямо под плеером. Ранее сохранённые результаты можно выбрать во вкладке «Обработка».",
                        color = AppTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
internal fun AppEyebrow(text: String) {
    Text(
        text,
        color = AppTextMuted,
        style = MaterialTheme.typography.labelMedium
    )
}
