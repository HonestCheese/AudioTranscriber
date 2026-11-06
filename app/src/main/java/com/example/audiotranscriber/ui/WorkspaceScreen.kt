package com.example.audiotranscriber.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.audiotranscriber.data.SavedTranscript
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(
    transcripts: List<SavedTranscript>,
    onOpen: (SavedTranscript) -> Unit,
    onRename: (SavedTranscript, String) -> Unit,
    onMove: (SavedTranscript, Int) -> Unit,
    onDelete: (SavedTranscript) -> Unit,
    onGoToAudio: () -> Unit
) {
    var menuId by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<SavedTranscript?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SavedTranscript?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dropIndex by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    renaming?.let { item ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать расшифровку") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Название") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        onRename(item, newName.trim())
                        renaming = null
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } }
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить расшифровку?") },
            text = { Text("«${item.fileName}» будет удалена с устройства. Исходная аудиозапись останется.") },
            confirmButton = {
                TextButton(onClick = { onDelete(item); deleting = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Обработка", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Готовые расшифровки. Нажмите, чтобы открыть результат на странице аудио.",
            color = AppTextMuted, style = MaterialTheme.typography.bodySmall)

        if (transcripts.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пока нет расшифровок", style = MaterialTheme.typography.titleMedium)
                    Text("Откройте вкладку «Аудио», выберите запись и запустите обработку.", color = AppTextMuted)
                    Button(onClick = onGoToAudio, shape = RoundedCornerShape(10.dp)) {
                        Text("Перейти к аудио")
                    }
                }
            }
        } else {
            Text("РАСШИФРОВКИ · ${transcripts.size}  |  Зажмите карточку для действий",
                style = MaterialTheme.typography.labelMedium, color = AppTextMuted)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(transcripts, key = { it.id }) { item ->
                    val isDragging = draggingId == item.id
                    Box(Modifier.fillMaxWidth().zIndex(if (isDragging) 10f else 0f)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = dragOffset
                                        shadowElevation = 14f
                                    }
                                }
                                .combinedClickable(
                                    onClick = { if (draggingId == null) onOpen(item) },
                                    onLongClick = { if (draggingId == null) menuId = item.id }
                                ),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isDragging) MaterialTheme.colorScheme.secondaryContainer else AppSurface
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(item.fileName, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text(
                                        "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.id))} · ${item.segments.size} реплик",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppTextMuted
                                    )
                                    Text("Открыть в аудио →", color = AppAccent, style = MaterialTheme.typography.labelMedium)
                                }
                                Box(
                                    Modifier.size(width = 48.dp, height = 52.dp)
                                        .pointerInput(item.id, transcripts.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    menuId = null
                                                    draggingId = item.id
                                                    dragOffset = 0f
                                                    dropIndex = transcripts.indexOfFirst { it.id == item.id }
                                                },
                                                onDragEnd = {
                                                    val from = transcripts.indexOfFirst { it.id == item.id }
                                                    val to = dropIndex
                                                    draggingId = null
                                                    dragOffset = 0f
                                                    dropIndex = -1
                                                    if (from >= 0 && to >= 0 && to != from) onMove(item, to - from)
                                                },
                                                onDragCancel = {
                                                    draggingId = null
                                                    dragOffset = 0f
                                                    dropIndex = -1
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                    val current = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }
                                                    if (current != null) {
                                                        val center = current.offset + current.size / 2f + dragOffset
                                                        val hovered = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
                                                            center >= other.offset && center < other.offset + other.size
                                                        }
                                                        if (hovered != null) dropIndex = hovered.index
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⠿", style = MaterialTheme.typography.headlineSmall, color = AppTextMuted)
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = menuId == item.id,
                            onDismissRequest = { menuId = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Открыть") },
                                onClick = { menuId = null; onOpen(item) }
                            )
                            DropdownMenuItem(
                                text = { Text("Переименовать") },
                                onClick = { menuId = null; newName = item.fileName; renaming = item }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuId = null; deleting = item }
                            )
                        }
                    }
                }
            }
        }
    }
}