package com.example.audiotranscriber.ml

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Decode Android-supported audio formats, downmix and resample continuously to 16 kHz. */
internal object AudioDecoder {
    suspend fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val resampler = MonoResampler(rate)
            val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() - lastProgress < 30_000_000_000L) { "Декодер аудио не отвечает" }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate == resampler.sourceRate) { "Изменение частоты аудио внутри файла не поддерживается" }
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        lastProgress = System.nanoTime()
                    }
                    index >= 0 -> {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = decoder.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                resampler.accept(buffer, channels, encoding)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = System.nanoTime()
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            return resampler.finish()
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}

/** Interpolation carries its last source sample across codec buffers. */
internal class MonoResampler(val sourceRate: Int) {
    private var sourceIndex = -1L
    private var nextPosition = 0.0
    private var previous = 0f
    private val step = sourceRate / 16000.0
    private var samples = FloatArray(16000 * 10)
    private var size = 0

    init { require(sourceRate > 0) }

    private fun append(value: Float) {
        require(size < 16000 * 60 * 30) { "Запись длиннее 30 минут. Разделите её на части." }
        if (size == samples.size) samples = samples.copyOf((samples.size * 2).coerceAtMost(16000 * 60 * 30))
        samples[size++] = value
    }

    fun accept(buffer: ByteBuffer, channels: Int, encoding: Int) {
        require(channels > 0)
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> error("Неподдерживаемый формат PCM: $encoding")
        }
        require(buffer.remaining() % (channels * bytes) == 0) { "Повреждены PCM-данные" }
        while (buffer.remaining() >= channels * bytes) {
            var mono = 0.0
            repeat(channels) {
                mono += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128.0
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768.0
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.toDouble()
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648.0
                    else -> {
                        val raw = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                        raw / 8388608.0
                    }
                }
            }
            val current = (mono / channels).toFloat().let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
            sourceIndex++
            if (sourceIndex == 0L) previous = current
            while (nextPosition <= sourceIndex) {
                val fraction = (nextPosition - (sourceIndex - 1)).coerceIn(0.0, 1.0)
                append((previous + (current - previous) * fraction).toFloat())
                nextPosition += step
            }
            previous = current
        }
    }

    fun finish(): FloatArray {
        // Repeat the last sample only for the fractional tail of the last input frame.
        val expected = floor((sourceIndex + 1) * 16000.0 / sourceRate).toInt()
        while (size < expected) append(previous)
        return samples.copyOf(size.coerceAtMost(expected))
    }
}
