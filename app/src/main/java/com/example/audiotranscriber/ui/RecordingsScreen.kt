package com.example.audiotranscriber.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audiotranscriber.data.SavedRecording

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(
    recordings: List<SavedRecording>,
    onOpen: (SavedRecording) -> Unit,
    onRename: (SavedRecording, String) -> Unit,
    onMove: (SavedRecording, Int) -> Unit,
    onDelete: (SavedRecording) -> Unit,
    onGoToAudio: () -> Unit
) {
    var menuId by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<SavedRecording?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SavedRecording?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val latestItems by rememberUpdatedState(recordings)
    val latestOnMove by rememberUpdatedState(onMove)

    // Reorder immediately when the dragged row's centre crosses another row's
    // centre. The item left behind is filled by the row that was crossed.
    fun updateDragPosition() {
        val id = draggingId ?: return
        val itemsNow = latestItems
        val from = itemsNow.indexOfFirst { it.id == id }
        if (from < 0) return

        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == id } ?: return
        val draggedCenter = dragged.offset + dragged.size / 2f

        val crossed = when {
            dragPointerY > draggedCenter -> visible
                .filter { it.key != id && it.index > from && dragPointerY >= it.offset + it.size / 2f }
                .maxByOrNull { it.index }
            dragPointerY < draggedCenter -> visible
                .filter { it.key != id && it.index < from && dragPointerY <= it.offset + it.size / 2f }
                .minByOrNull { it.index }
            else -> null
        }

        if (crossed != null) {
            val to = crossed.index
            if (to != from) {
                dragOffset = dragPointerY - crossed.offset - dragged.size / 2f
                latestOnMove(itemsNow[from], to - from)
                return
            }
        }
        dragOffset = dragPointerY - dragged.offset - dragged.size / 2f
    }

    // Auto-scroll at either edge while the drag is held, then recalculate
    // crossed row-centres so items can be moved through a long list.
    LaunchedEffect(draggingId) {
        if (draggingId != null) {
            val edge = with(density) { 76.dp.toPx() }
            while (draggingId != null) {
                val height = listState.layoutInfo.viewportSize.height.toFloat()
                val y = dragPointerY
                val delta = when {
                    y < edge -> -(((edge - y) / edge) * 22f).coerceIn(4f, 22f)
                    y > height - edge -> (((y - (height - edge)) / edge) * 22f).coerceIn(4f, 22f)
                    else -> 0f
                }
                if (delta != 0f) {
                    listState.scrollBy(delta)
                    withFrameNanos { }
                    updateDragPosition()
                } else {
                    withFrameNanos { }
                }
            }
        }
    }

    renaming?.let { item ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать запись") },
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
            title = { Text("Удалить запись?") },
            text = {
                Text("Файл «${item.name}» будет удалён. Расшифровки сохранятся, но аудио может стать недоступным.")
            },
            confirmButton = {
                TextButton(onClick = { onDelete(item); deleting = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Записи", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Ваша аудиотека",
                color = AppTextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (recordings.isEmpty()) {
            AppPanel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Записей пока нет", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Запишите разговор или добавьте аудиофайл — он появится здесь.",
                        color = AppTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onGoToAudio, shape = RoundedCornerShape(14.dp)) {
                        Text("Добавить аудио")
                    }
                }
            }
        } else {
            AppEyebrow("АУДИОФАЙЛЫ · ${recordings.size}")
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(recordings, key = { it.id }) { item ->
                    val isDragging = draggingId == item.id
                    Box(Modifier.fillMaxWidth().zIndex(if (isDragging) 10f else 0f)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = dragOffset
                                        shadowElevation = 14f
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .combinedClickable(
                                    onClick = { if (draggingId == null) onOpen(item) },
                                    onLongClick = { if (draggingId == null) menuId = item.id }
                                ),
                            shape = RoundedCornerShape(18.dp),
                            color = if (isDragging) MaterialTheme.colorScheme.secondaryContainer else AppCard,
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 13.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(13.dp),
                                    color = AppAccentSoft
                                ) {
                                    Text(
                                        "♫",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                        color = AppAccent,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text("Зажмите для действий", color = AppTextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                Box(
                                    Modifier.size(width = 48.dp, height = 52.dp)
                                        .pointerInput(item.id, recordings.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    menuId = null
                                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }
                                                    dragPointerY = info?.let { it.offset + it.size / 2f } ?: 0f
                                                    dragOffset = 0f
                                                    draggingId = item.id
                                                },
                                                onDragEnd = {
                                                    draggingId = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingId = null
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragPointerY += amount.y
                                                    updateDragPosition()
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
                                text = { Text("Переименовать") },
                                onClick = { menuId = null; newName = item.name; renaming = item }
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
