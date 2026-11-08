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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.safeDrawingPadding
import com.example.audiotranscriber.audio.AudioPlayer
import com.example.audiotranscriber.audio.AudioRecorder
import com.example.audiotranscriber.data.RecordingStore
import com.example.audiotranscriber.data.SavedRecording
import com.example.audiotranscriber.data.SavedTranscript
import com.example.audiotranscriber.data.TranscriptStore
import com.example.audiotranscriber.ml.TranscriptionViewModel
import com.example.audiotranscriber.ml.DiarizationAudioVariant
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
    var pendingSeekMs by remember { mutableIntStateOf(-1) }
    var playAfterPendingSeek by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var diarizationAudioVariant by remember { mutableStateOf(DiarizationAudioVariant.ORIGINAL) }
    var showProfileName by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ml.registerProfile(profileName, uris)
    }
    if (showProfileName) {
        AlertDialog(
            onDismissRequest = { showProfileName = false },
            title = { Text("Имя участника") },
            text = { OutlinedTextField(value = profileName, onValueChange = { profileName = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = profileName.isNotBlank(), onClick = {
                showProfileName = false
                player.pause()
                isPlaying = false
                profilePicker.launch(arrayOf("audio/*"))
            }) { Text("Выбрать 2–4 записи") } },
            dismissButton = { TextButton(onClick = { showProfileName = false }) { Text("Отмена") } }
        )
    }


    LaunchedEffect(processing.result) {
        processing.result?.let { result ->
            history = store.loadAll()
            currentTranscript = result
            navigateTo(1)
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
        } catch (e: Exception) {
            status = "Ошибка загрузки аудио: ${e.message}"
        }
    }
    LaunchedEffect(playerReady, pendingSeekMs) {
        if (playerReady && pendingSeekMs >= 0) {
            val target = pendingSeekMs.coerceAtMost(duration)
            player.seekTo(target)
            position = target
            pendingSeekMs = -1
            if (playAfterPendingSeek) {
                player.play()
                isPlaying = true
            }
            playAfterPendingSeek = false
        }
    }

    fun startRecording() {
        if (processing.busy) return
        try {
            currentTranscript = null
            pendingSeekMs = -1
            player.pause()
            isPlaying = false
            recorder.start()
            recordingStart = SystemClock.elapsedRealtime()
            recordingSeconds = 0
            isRecording = true
            status = "Идёт запись..."
        } catch (e: Exception) {
            status = "Ошибка записи: ${e.message}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "Нет разрешения на микрофон"
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentTranscript = null
            pendingSeekMs = -1
            audioUri = uri
            fileName = "Выбран аудиофайл"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) fileName = cursor.getString(0)
            }
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            try {
                val saved = recordingStore.add(uri, fileName)
                recordings = recordingStore.loadAll()
                selectedRecording = saved
                audioUri = recordingStore.uri(saved)
                fileName = saved.name
                status = "Аудиофайл сохранён в приложении"
            } catch (e: Exception) {
                status = "Ошибка импорта: ${e.message}"
            }
        }
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val transcript = currentTranscript
        if (uri != null && transcript != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(store.exportJson(transcript))
                } ?: error("Не удалось создать JSON-файл")
            }.onSuccess {
                status = "JSON экспортирован"
            }.onFailure {
                status = "Ошибка экспорта JSON: ${it.message}"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "AudioTranscriber",
                            style = MaterialTheme.typography.titleLarge,
                            color = AppText
                        )
                        Text(
                            "Локальная расшифровка аудио",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextMuted
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = AppAccentSoft
                    ) {
                        Text(
                            "A",
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = AppAccent
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = AppSurface
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Обработка", "Аудио", "Записи").forEachIndexed { index, label ->
                            val selected = pagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) AppCard else AppSurface)
                                    .clickable { navigateTo(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) AppText else AppTextMuted
                                )
                            }
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                when (page) {
                    0 -> WorkspaceScreen(
                        transcripts = history,
                        onOpen = { item ->
                            currentTranscript = item
                            pendingSeekMs = -1
                            val savedUri = item.audioUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                            audioUri = savedUri
                            val recording = recordings.firstOrNull {
                                recordingStore.uri(it).toString() == item.audioUri
                            }
                            selectedRecording = recording
                            fileName = recording?.name ?: if (savedUri == null) "Исходное аудио недоступно" else item.fileName
                            status = if (savedUri == null) "Расшифровка открыта, исходное аудио недоступно" else "Расшифровка открыта"
                            navigateTo(1)
                        },
                        onRename = { item, name ->
                            store.rename(item.id, name)
                            history = store.loadAll()
                            if (currentTranscript?.id == item.id) {
                                currentTranscript = history.firstOrNull { it.id == item.id }
                            }
                        },
                        onMove = { item, direction ->
                            store.move(item.id, direction)
                            history = store.loadAll()
                        },
                        onDelete = { item ->
                            store.delete(item.id)
                            history = store.loadAll()
                            if (currentTranscript?.id == item.id) currentTranscript = null
                        },
                        onGoToAudio = { navigateTo(1) }
                    )

                    2 -> RecordingsScreen(
                        recordings = recordings,
                        onOpen = { item ->
                            if (processing.busy) return@RecordingsScreen
                            selectedRecording = item
                            audioUri = recordingStore.uri(item)
                            currentTranscript = history.firstOrNull {
                                it.audioUri == recordingStore.uri(item).toString()
                            }
                            pendingSeekMs = -1
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
                                currentTranscript = null
                                fileName = "Файл не выбран"
                            }
                            recordingStore.delete(item.id)
                            recordings = recordingStore.loadAll()
                        },
                        onGoToAudio = { navigateTo(1) }
                    )

                    else -> HomeScreen(
                        fileName = fileName,
                        isRecording = isRecording,
                        recordingSeconds = recordingSeconds,
                        playerReady = playerReady,
                        isPlaying = isPlaying,
                        position = position,
                        duration = duration,
                        status = if (processing.busy || processing.message.isNotBlank()) processing.message else status,
                        hasAudio = audioUri != null,
                        isProcessing = processing.busy,
                        modelsReady = processing.modelsReady,
                        onInstallModels = { ml.installModels(emptyList()) },
                        audioVariant = diarizationAudioVariant,
                        onAudioVariantChange = { diarizationAudioVariant = it },
                        onCancel = ml::cancel,
                        profiles = processing.profiles,
                        onAddProfile = { profileName = ""; showProfileName = true },
                        onDeleteProfile = ml::deleteProfile,
                        onRecord = {
                            if (isRecording) {
                                try {
                                    val file = recorder.stop()
                                    val saved = recordingStore.add(Uri.fromFile(file), file.name)
                                    file.delete()
                                    recordings = recordingStore.loadAll()
                                    selectedRecording = saved
                                    currentTranscript = null
                                    audioUri = recordingStore.uri(saved)
                                    fileName = saved.name
                                    status = "Запись сохранена"
                                } catch (e: Exception) {
                                    status = "Ошибка сохранения: ${e.message}"
                                } finally {
                                    isRecording = false
                                }
                            } else if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                startRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onImport = { audioPicker.launch(arrayOf("audio/*")) },
                        onPlayPause = {
                            if (isPlaying) {
                                player.pause()
                                isPlaying = false
                            } else if (playerReady) {
                                if (position >= duration) {
                                    player.seekTo(0)
                                    position = 0
                                }
                                player.play()
                                isPlaying = true
                            }
                        },
                        onSeek = { time ->
                            if (playerReady) {
                                player.seekTo(time)
                                position = time
                            }
                        },
                        onTranscript = {
                            val uri = audioUri
                            if (uri != null && !processing.busy) {
                                player.pause()
                                isPlaying = false
                                currentTranscript = null
                                ml.transcribe(uri, fileName, diarizationAudioVariant)
                            }
                        },
                        transcript = currentTranscript,
                        onSaveNames = { names ->
                            val opened = currentTranscript
                            if (opened != null) {
                                val updated = opened.copy(speakerNames = names)
                                store.save(updated)
                                history = store.loadAll()
                                currentTranscript = updated
                            }
                        },
                        onSegmentClick = { time ->
                            if (audioUri == null) {
                                status = "Исходное аудио недоступно. Текст расшифровки сохранён."
                            } else if (playerReady) {
                                val target = time.coerceAtMost(duration)
                                player.seekTo(target)
                                position = target
                                player.play()
                                isPlaying = true
                            } else {
                                pendingSeekMs = time
                                playAfterPendingSeek = true
                                status = "Аудио загружается. Реплика начнёт воспроизводиться автоматически."
                            }
                        },
                        onExport = {
                            currentTranscript?.let { jsonExportLauncher.launch("${it.fileName}.json") }
                        }
                    )
                }
            }
        }
    }
}
