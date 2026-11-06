package com.example.audiotranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.graphics.toArgb
import com.example.audiotranscriber.audio.AudioPlayer
import com.example.audiotranscriber.audio.AudioRecorder
import com.example.audiotranscriber.data.RecordingStore
import com.example.audiotranscriber.data.SavedRecording
import com.example.audiotranscriber.data.SavedTranscript
import com.example.audiotranscriber.data.TranscriptStore
import com.example.audiotranscriber.ml.TranscriptionViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.WindowManager
import com.example.audiotranscriber.ui.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ may draw the app behind system bars. Compose handles the
        // safe insets below, so status/navigation bars never cover the UI.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { AppStyle { AudioTranscriberApp() } }
    }
}

@Composable
fun AudioTranscriberApp() {
    val context = LocalContext.current
    val ml: TranscriptionViewModel = viewModel()
    val processing by ml.state.collectAsState()
    var speakerCount by remember { mutableIntStateOf(2) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ml.installModels(uris)
    }
    DisposableEffect(processing.busy) {
        val window = (context as? ComponentActivity)?.window
        if (processing.busy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val recorder = remember { AudioRecorder(context) }
    val player = remember { AudioPlayer(context) }
    val store = remember { TranscriptStore(context) }
    val recordingStore = remember { RecordingStore(context) }
    var recordings by remember { mutableStateOf(recordingStore.loadAll()) }
    var selectedRecording by remember { mutableStateOf<SavedRecording?>(null) }
    var history by remember { mutableStateOf(store.loadAll()) }
    var currentTranscript by remember { mutableStateOf<SavedTranscript?>(null) }
    val pagerState = rememberPagerState(pageCount = { 3 }) // Обработка / Аудио / Записи
    val pageScope = rememberCoroutineScope()
    fun navigateTo(page: Int) { pageScope.launch { pagerState.animateScrollToPage(page) } }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("Файл не выбран") }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStart by remember { mutableLongStateOf(0L) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerReady by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(processing.result) {
        processing.result?.let { result ->
            history = store.loadAll()
            currentTranscript = result
            navigateTo(0)
            ml.consumeResult()
        }
    }

    DisposableEffect(recorder, player) {
        player.onCompletion = { isPlaying = false; position = player.duration }
        onDispose { recorder.release(); player.release() }
    }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            recordingSeconds = (SystemClock.elapsedRealtime() - recordingStart) / 1000
            delay(250)
        }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            position = player.currentPosition
            delay(200)
        }
    }
    LaunchedEffect(audioUri) {
        isPlaying = false
        playerReady = false
        duration = 0
        position = 0
        val uri = audioUri ?: return@LaunchedEffect
        try {
            player.load(uri)
            duration = player.duration
            playerReady = true
        } catch (e: Exception) { status = "Ошибка загрузки аудио: ${e.message}" }
    }

    fun startRecording() {
        if (processing.busy) return
        try {
            player.pause()
            isPlaying = false
            recorder.start()
            recordingStart = SystemClock.elapsedRealtime()
            recordingSeconds = 0
            isRecording = true
            status = "Идёт запись..."
        } catch (e: Exception) { status = "Ошибка записи: ${e.message}" }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "Нет разрешения на микрофон"
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            fileName = "Выбран аудиофайл"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) fileName = cursor.getString(0)
            }
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            try {
                val saved = recordingStore.add(uri, fileName)
                recordings = recordingStore.loadAll()
                selectedRecording = saved
                audioUri = recordingStore.uri(saved)
                fileName = saved.name
                status = "Аудиофайл сохранён в приложении"
            } catch (e: Exception) { status = "Ошибка импорта: ${e.message}" }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Surface(color = AppSurface) {
            Column {
                Text("AUDIOTRANSCRIBER", modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.titleMedium, color = AppAccent)
                TabRow(selectedTabIndex = pagerState.currentPage, containerColor = AppSurface, contentColor = AppAccent) {
                    Tab(selected = pagerState.currentPage == 0, onClick = { currentTranscript = null; navigateTo(0) },
                        text = { Text("Обработка") })
                    Tab(selected = pagerState.currentPage == 1, onClick = { currentTranscript = null; navigateTo(1) },
                        text = { Text("Аудио") })
                    Tab(selected = pagerState.currentPage == 2, onClick = { currentTranscript = null; navigateTo(2) },
                        text = { Text("Записи") })
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
        val opened = currentTranscript
        if (opened != null && page == 0) {
            TranscriptScreen(
                transcript = opened,
                onBack = { currentTranscript = null },
                onSaveNames = { names ->
                    val updated = opened.copy(speakerNames = names)
                    store.save(updated)
                    history = store.loadAll()
                    currentTranscript = updated
                },
                onSegmentClick = { time ->
                    // Восстанавливаем файл, если доступен. Для удалённого исходника реплики остаются сохранены.
                    val savedUri = runCatching { Uri.parse(opened.audioUri) }.getOrNull()
                    if (savedUri != null && opened.audioUri.isNotBlank()) {
                        if (audioUri == savedUri && playerReady) {
                            player.seekTo(time.coerceAtMost(duration))
                            position = time.coerceAtMost(duration)
                            player.play()
                            isPlaying = true
                        } else {
                            audioUri = savedUri
                            position = time
                            status = "Аудио загружается. Нажмите воспроизведение."
                        }
                        fileName = opened.fileName
                        navigateTo(1)
                        currentTranscript = null
                    } else {
                        status = "Исходное аудио недоступно"
                    }
                }
            )
        } else if (page == 0) {
            WorkspaceScreen(
                transcripts = history,
                onOpen = { currentTranscript = it },
                onDelete = { store.delete(it.id); history = store.loadAll() },
                onGoToAudio = { navigateTo(1) }
            )
        } else if (page == 2) {
            RecordingsScreen(
                recordings = recordings,
                onOpen = { item ->
                    if (processing.busy) return@RecordingsScreen
                    selectedRecording = item
                    audioUri = recordingStore.uri(item)
                    fileName = item.name
                    status = "Запись открыта"
                    navigateTo(1)
                },
                onRename = { item, name ->
                    recordingStore.rename(item.id, name)
                    recordings = recordingStore.loadAll()
                    if (selectedRecording?.id == item.id) {
                        selectedRecording = recordings.firstOrNull { it.id == item.id }
                        fileName = name.trim()
                    }
                },
                onMove = { item, direction ->
                    recordingStore.move(item.id, direction)
                    recordings = recordingStore.loadAll()
                },
                onDelete = { item ->
                    if (processing.busy) return@RecordingsScreen
                    if (selectedRecording?.id == item.id) {
                        player.pause()
                        isPlaying = false
                        audioUri = null
                        selectedRecording = null
                        fileName = "Файл не выбран"
                    }
                    recordingStore.delete(item.id)
                    recordings = recordingStore.loadAll()
                },
                onGoToAudio = { navigateTo(1) }
            )
        } else {
            HomeScreen(
                fileName = fileName, isRecording = isRecording, recordingSeconds = recordingSeconds,
                playerReady = playerReady, isPlaying = isPlaying, position = position, duration = duration,
                status = if (processing.busy || processing.message.isNotBlank()) processing.message else status,
                hasAudio = audioUri != null, isProcessing = processing.busy, modelsReady = processing.modelsReady,
                onInstallModels = { modelPicker.launch(arrayOf("*/*")) }, onCancel = ml::cancel,
                speakerCount = speakerCount, onSpeakerCount = { speakerCount = it },
                onRecord = {
                    if (isRecording) {
                        try {
                            val file = recorder.stop()
                            val saved = recordingStore.add(Uri.fromFile(file), file.name)
                            file.delete()
                            recordings = recordingStore.loadAll()
                            selectedRecording = saved
                            audioUri = recordingStore.uri(saved)
                            fileName = saved.name
                            status = "Запись сохранена"
                        } catch (e: Exception) { status = "Ошибка сохранения: ${e.message}" }
                        finally { isRecording = false }
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startRecording()
                    } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onImport = { audioPicker.launch(arrayOf("audio/*")) },
                onPlayPause = {
                    if (isPlaying) { player.pause(); isPlaying = false }
                    else if (playerReady) {
                        if (position >= duration) { player.seekTo(0); position = 0 }
                        player.play(); isPlaying = true
                    }
                },
                onSeek = { time -> if (playerReady) { player.seekTo(time); position = time } },
                onTranscript = {
                    val uri = audioUri
                    if (uri != null && !processing.busy) {
                        player.pause(); isPlaying = false
                        ml.transcribe(uri, fileName, speakerCount)
                    }
                }
            )
        }
        } // HorizontalPager
    }
    }
}
