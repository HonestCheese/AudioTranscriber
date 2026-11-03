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
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
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
    var dropIndex by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    renaming?.let { item ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать запись") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("Название") }) },
            confirmButton = { TextButton(onClick = {
                if (newName.isNotBlank()) { onRename(item, newName); renaming = null }
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } }
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить запись?") },
            text = { Text("Файл «${item.name}» будет удалён. Расшифровки сохранятся, но аудио может стать недоступным.") },
            confirmButton = { TextButton(onClick = { onDelete(item); deleting = null }) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Мои записи", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Нажмите для прослушивания. Зажмите карточку для меню, а значок ⠿ — чтобы перетащить запись.",
            color = AppTextMuted, style = MaterialTheme.typography.bodySmall)
        if (recordings.isEmpty()) {
            Surface(color = AppSurface, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Записей пока нет")
                    Button(onClick = onGoToAudio) { Text("Записать или импортировать") }
                }
            }
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(recordings, key = { it.id }) { item ->
                    val isDragging = draggingId == item.id
                    // DropdownMenu is a Popup: it is drawn OVER the list and doesn't change item sizes.
                    Box(Modifier.fillMaxWidth().zIndex(if (isDragging) 10f else 0f)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .graphicsLayer { if (isDragging) { translationY = dragOffset; shadowElevation = 14f } }
                                .combinedClickable(
                                    onClick = { if (draggingId == null) onOpen(item) },
                                    onLongClick = { if (draggingId == null) menuId = item.id }
                                ),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isDragging) MaterialTheme.colorScheme.secondaryContainer else AppSurface
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text("Зажмите для действий", color = AppTextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                // Handle has its own long-press drag recognizer. We don't open the menu during dragging.
                                Box(
                                    Modifier.size(width = 48.dp, height = 52.dp)
                                        .pointerInput(item.id, recordings.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    menuId = null
                                                    draggingId = item.id
                                                    dragOffset = 0f
                                                    dropIndex = recordings.indexOfFirst { it.id == item.id }
                                                },
                                                onDragEnd = {
                                                    val from = recordings.indexOfFirst { it.id == item.id }
                                                    val to = dropIndex
                                                    draggingId = null
                                                    dragOffset = 0f
                                                    dropIndex = -1
                                                    if (from >= 0 && to >= 0 && to != from) onMove(item, to - from)
                                                },
                                                onDragCancel = { draggingId = null; dragOffset = 0f; dropIndex = -1 },
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
                                ) { Text("⠿", style = MaterialTheme.typography.headlineSmall, color = AppTextMuted) }
                            }
                        }
                        DropdownMenu(expanded = menuId == item.id, onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                                menuId = null; newName = item.name; renaming = item
                            })
                            DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = {
                                menuId = null; deleting = item
                            })
                        }
                    }
                }
            }
        }
    }
}
