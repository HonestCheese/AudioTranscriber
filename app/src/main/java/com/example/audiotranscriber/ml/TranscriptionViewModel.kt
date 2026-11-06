package com.example.audiotranscriber.ml

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiotranscriber.data.SavedTranscript
import com.example.audiotranscriber.data.TranscriptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keep an ongoing transcription alive across activity recreation and rotations. */
class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val busy: Boolean = false,
        val message: String = "",
        val modelsReady: Boolean = false,
        val result: SavedTranscript? = null
    )
    private val app = application
    private val mutableState = MutableStateFlow(State(modelsReady = ModelInstaller.isReady(app)))
    val state = mutableState.asStateFlow()
    private var task: Job? = null

    fun installModels(uris: List<Uri>) {
        if (mutableState.value.busy) return
        task = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = "Проверка архива…", result = null)
            try {
                ModelInstaller.install(app, uris) { message -> mutableState.value = mutableState.value.copy(message = message) }
                mutableState.value = mutableState.value.copy(modelsReady = true, message = "Модели установлены. Можно расшифровывать аудио.")
            } catch (e: CancellationException) {
                mutableState.value = mutableState.value.copy(message = "Установка отменена")
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(message = "Ошибка установки: \${e.message}")
            } finally { mutableState.value = mutableState.value.copy(busy = false, modelsReady = ModelInstaller.isReady(app)) }
        }
    }

    fun transcribe(uri: Uri, fileName: String, speakerCount: Int) {
        if (mutableState.value.busy) return
        task = viewModelScope.launch {
            val totalStarted = System.nanoTime()
            mutableState.value = mutableState.value.copy(busy = true, message = "Запуск обработки…", result = null)
            try {
                val output = LocalTranscriber.transcribe(app, uri, speakerCount) { message ->
                    mutableState.value = mutableState.value.copy(message = message)
                }
                val segments = output.segments
                val names = segments.map { it.speakerId }.distinct().associateWith {
                    if (it < 0) "Не определён" else "Участник \${it + 1}"
                }
                val id = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(id))
                val transcriptName = "\${timestamp}_transcript"
                val initial = SavedTranscript(
                    id, transcriptName, uri.toString(), segments, names,
                    processingTimings = output.timings
                )
                val store = TranscriptStore(app)
                val saveStarted = System.nanoTime()
                withContext(Dispatchers.IO) { store.save(initial) }
                val saveMs = (System.nanoTime() - saveStarted) / 1_000_000L
                val saved = initial.copy(
                    processingTimings = output.timings.copy(
                        saveMs = saveMs,
                        totalMs = (System.nanoTime() - totalStarted) / 1_000_000L
                    )
                )
                withContext(Dispatchers.IO) { store.save(saved) }
                mutableState.value = mutableState.value.copy(message = "Расшифровка сохранена", result = saved)
            } catch (e: CancellationException) {
                mutableState.value = mutableState.value.copy(message = "Обработка отменена")
                throw e
            } catch (e: OutOfMemoryError) {
                mutableState.value = mutableState.value.copy(message = "Недостаточно памяти. Закройте другие приложения и попробуйте короткую запись.")
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(message = "Ошибка обработки: \${e.message}")
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun cancel() {
        mutableState.value = mutableState.value.copy(message = "Остановка… Ожидаем завершения текущего шага модели.")
        task?.cancel()
    }

    fun consumeResult() { mutableState.value = mutableState.value.copy(result = null) }
}