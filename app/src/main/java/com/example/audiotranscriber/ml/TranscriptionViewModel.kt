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
            mutableState.value = mutableState.value.copy(
                busy = true,
                message = "Проверка архива GigaAM…",
                result = null
            )
            try {
                ModelInstaller.install(app, uris) { message ->
                    mutableState.value = mutableState.value.copy(message = message)
                }
                mutableState.value = mutableState.value.copy(
                    modelsReady = true,
                    message = "GigaAM установлен. Reverb и ERes2NetV2 загрузятся при первой обработке; DPDFNet2 — если выберете его вариант."
                )
            } catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(message = "Установка отменена.")
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    message = "Ошибка установки: ${error.message}"
                )
            } finally {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    modelsReady = ModelInstaller.isReady(app)
                )
            }
        }
    }

    fun transcribe(
        uri: Uri,
        fileName: String,
        audioVariant: DiarizationAudioVariant
    ) {
        if (mutableState.value.busy) return
        task = viewModelScope.launch {
            val totalStarted = System.nanoTime()
            mutableState.value = mutableState.value.copy(
                busy = true,
                message = "Запуск обработки…",
                result = null
            )
            try {
                val output = LocalTranscriber.transcribe(app, uri, audioVariant) { message ->
                    mutableState.value = mutableState.value.copy(message = message)
                }
                val names = output.speakers.associate { speaker ->
                    speaker.speakerId to "Участник ${speaker.speakerId + 1}"
                } + (output.segments.filter { it.speakerId < 0 }
                    .takeIf { it.isNotEmpty() }?.let { mapOf(-1 to "Не определён") } ?: emptyMap())

                val id = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("yy-MM-dd HH:mm", Locale.US).format(Date(id))
                val initial = SavedTranscript(
                    id = id,
                    fileName = "${timestamp}_transcript",
                    audioUri = uri.toString(),
                    segments = output.segments,
                    durationMs = output.durationMs,
                    speakerNames = names,
                    engine = "gigaam-v3-reverb-eres2netv2-dpdfnet2",
                    processingTimings = output.timings,
                    speakers = output.speakers,
                    wordAssignments = output.words,
                    diarizationAudioVariant = output.diarizationAudioVariant,
                    recordingId = fileName
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
                mutableState.value = mutableState.value.copy(
                    message = "Расшифровка сохранена",
                    result = saved
                )
            } catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(message = "Обработка отменена.")
                throw error
            } catch (error: OutOfMemoryError) {
                mutableState.value = mutableState.value.copy(
                    message = "Недостаточно памяти. Закройте другие приложения и попробуйте короткую запись."
                )
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    message = "Ошибка обработки: ${error.message}"
                )
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun cancel() {
        mutableState.value = mutableState.value.copy(
            message = "Остановка… Ожидаем завершения текущего шага модели."
        )
        task?.cancel()
    }

    fun consumeResult() {
        mutableState.value = mutableState.value.copy(result = null)
    }
}
