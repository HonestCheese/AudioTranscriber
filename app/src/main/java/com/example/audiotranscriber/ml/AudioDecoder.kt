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
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Decode Android-supported audio formats, downmix and polyphase-resample to 16 kHz mono. */
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
                check(System.nanoTime() - lastProgress < 30_000_000_000L) {
                    "Декодер аудио не отвечает"
                }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
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
                        require(rate == resampler.sourceRate) {
                            "Изменение частоты аудио внутри файла не поддерживается"
                        }
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        lastProgress = System.nanoTime()
                    }
                    index >= 0 -> {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = decoder.getOutputBuffer(index)!!
                                    .duplicate()
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                resampler.accept(buffer, channels, encoding)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = System.nanoTime()
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
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

/**
 * Streaming polyphase FIR resampler matching scipy.signal.resample_poly's
 * Kaiser-windowed low-pass approach. It preserves state across MediaCodec buffers.
 */
internal class MonoResampler(val sourceRate: Int) {
    private val divisor: Int
    private val up: Int
    private val down: Int
    private val halfLength: Int
    private val coefficients: FloatArray
    private val ring: FloatArray
    private var sourceIndex = -1L
    private var nextOutputIndex = 0L
    private var samples = FloatArray(16_000 * 10)
    private var size = 0

    private val identity = sourceRate == 16_000

    init {
        require(sourceRate in 8_000..192_000) {
            "Неподдерживаемая частота аудио: $sourceRate Hz"
        }
        divisor = gcd(sourceRate, 16_000)
        up = 16_000 / divisor
        down = sourceRate / divisor

        if (identity) {
            halfLength = 0
            coefficients = floatArrayOf(1f)
            ring = FloatArray(1)
        } else {
            val maxRate = max(up, down)
            halfLength = 10 * maxRate
            val filterLength = 2 * halfLength + 1
            val cutoff = 1.0 / maxRate
            val beta = 5.0
            val i0Beta = besselI0(beta)
            val taps = DoubleArray(filterLength)
            var sum = 0.0

            for (index in taps.indices) {
                val offset = index - halfLength
                val x = cutoff * offset
                val ideal = cutoff * if (x == 0.0) {
                    1.0
                } else {
                    sin(PI * x) / (PI * x)
                }
                val position = offset.toDouble() / halfLength
                val window = besselI0(
                    beta * sqrt(max(0.0, 1.0 - position * position))
                ) / i0Beta
                taps[index] = ideal * window
                sum += taps[index]
            }

            coefficients = FloatArray(filterLength) { index ->
                (taps[index] / sum * up).toFloat()
            }
            val inputSpan = (filterLength - 1 + up - 1) / up
            ring = FloatArray(inputSpan + 2)
        }
    }

    private fun append(value: Float) {
        require(size < 16_000 * 60 * 30) {
            "Запись длиннее 30 минут. Разделите её на части."
        }
        if (size == samples.size) {
            samples = samples.copyOf((samples.size * 2).coerceAtMost(16_000 * 60 * 30))
        }
        samples[size++] = value
    }

    fun accept(samples: FloatArray) {
        samples.forEach { value ->
            val current = if (value.isFinite()) value else 0f
            acceptMono(current)
        }
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
                    AudioFormat.ENCODING_PCM_8BIT ->
                        ((buffer.get().toInt() and 255) - 128) / 128.0
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768.0
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.toDouble()
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648.0
                    else -> {
                        val raw = (buffer.get().toInt() and 255) or
                            ((buffer.get().toInt() and 255) shl 8) or
                            (buffer.get().toInt() shl 16)
                        raw / 8388608.0
                    }
                }
            }

            val current = (mono / channels).toFloat().let {
                if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f
            }
            acceptMono(current)
        }
    }

    private fun acceptMono(current: Float) {
        sourceIndex++
        if (identity) {
            append(current)
            return
        }
        ring[(sourceIndex % ring.size).toInt()] = current
        while (maxInputIndex(nextOutputIndex) <= sourceIndex) {
            append(resampleAt(nextOutputIndex))
            nextOutputIndex++
        }
    }

    fun finish(): FloatArray {
        if (!identity) {
            val inputCount = sourceIndex + 1L
            val expected = if (inputCount <= 0L) 0L else {
                (inputCount * up + down - 1L) / down
            }
            while (nextOutputIndex < expected) {
                append(resampleAt(nextOutputIndex))
                nextOutputIndex++
            }
        }
        return samples.copyOf(size)
    }

    private fun maxInputIndex(outputIndex: Long): Long =
        Math.floorDiv(outputIndex * down + halfLength, up.toLong())

    private fun resampleAt(outputIndex: Long): Float {
        val center = outputIndex * down + halfLength
        val minInput = ceilDiv(center - (coefficients.size - 1L), up.toLong())
        val maxInput = Math.floorDiv(center, up.toLong())
        var sum = 0.0

        var input = minInput
        while (input <= maxInput) {
            if (input >= 0L && input <= sourceIndex) {
                val coefficientIndex = (center - input * up).toInt()
                if (coefficientIndex in coefficients.indices) {
                    val sample = ring[(input % ring.size).toInt()]
                    sum += sample * coefficients[coefficientIndex]
                }
            }
            input++
        }
        return sum.toFloat()
    }

    private fun ceilDiv(value: Long, divisor: Long): Long =
        -Math.floorDiv(-value, divisor)

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x
    }

    private fun besselI0(value: Double): Double {
        val squaredQuarter = value * value / 4.0
        var term = 1.0
        var sum = 1.0
        var index = 1
        while (index < 80) {
            term *= squaredQuarter / (index * index)
            sum += term
            if (term <= sum * 1e-16) break
            index++
        }
        return sum
    }
}
