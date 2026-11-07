package com.example.audiotranscriber.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start() {
        val file = File(context.filesDir, "recording_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(128000)
            next.setAudioSamplingRate(44100)
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
            recorder = next
            outputFile = file
        } catch (e: Exception) {
            next.release()
            file.delete()
            throw e
        }
    }

    fun stop(): File {
        val active = recorder ?: error("Запись не запущена")
        val file = outputFile ?: error("Файл записи отсутствует")
        recorder = null
        outputFile = null
        try {
            active.stop()
            return file
        } catch (e: RuntimeException) {
            file.delete()
            throw e
        } finally {
            active.release()
        }
    }

    fun release() {
        recorder?.let {
            try { it.stop() } catch (_: RuntimeException) { outputFile?.delete() }
            finally { it.release() }
        }
        recorder = null
        outputFile = null
    }
}
