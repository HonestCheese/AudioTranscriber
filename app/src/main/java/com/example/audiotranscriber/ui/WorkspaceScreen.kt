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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.withFrameNanos
import com.example.audiotranscriber.data.SavedTranscript
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
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val latestItems by rememberUpdatedState(transcripts)
    val latestOnMove by rememberUpdatedState(onMove)

    // The dragged row changes its real list position as soon as its centre
    // crosses another row's centre. The row it leaves is immediately filled.
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

    // Keep scrolling while the finger is near the top/bottom edge. Re-evaluate
    // crossed row-centres after every scroll frame so long lists can be reordered.
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
        Text(
            "Готовые расшифровки. Нажмите, чтобы открыть результат на странице аудио.",
            color = AppTextMuted,
            style = MaterialTheme.typography.bodySmall
        )

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
            Text(
                "РАСШИФРОВКИ · ${transcripts.size}  |  Зажмите карточку для действий",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextMuted
            )
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
                                        scaleX = 1.02f
                                        scaleY = 1.02f
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
                                        "${item.segments.size} реплик",
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
