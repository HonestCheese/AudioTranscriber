package com.example.audiotranscriber.ml

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MonoResamplerTest {
    @Test
    fun keeps16KhzPcmUnchanged() {
        val pcm = ShortArray(16_000) { index ->
            (12_000.0 * sin(2.0 * PI * 440.0 * index / 16_000)).toInt().toShort()
        }
        val output = resample(pcm, 16_000, chunkSamples = 333)
        val expected = pcm.map { it / 32768.0f }.toFloatArray()

        assertArrayEquals(expected, output, 0.000001f)
    }

    @Test
    fun converts44100HzToOneSecondAt16Khz() {
        val pcm = sinePcm(44_100, 44_100, 440.0)
        val output = resample(pcm, 44_100)

        assertEquals(16_000, output.size)
        assertTrue(rms(output.drop(100).dropLast(100).toFloatArray()) in 0.34..0.37)
    }

    @Test
    fun converts8000HzTo16Khz() {
        val pcm = sinePcm(8_000, 8_000, 440.0)
        val output = resample(pcm, 8_000)

        assertEquals(16_000, output.size)
        assertTrue(rms(output.drop(100).dropLast(100).toFloatArray()) in 0.34..0.37)
    }

    @Test
    fun attenuatesFrequenciesAboveThe16KhzNyquistLimit() {
        val pcm = sinePcm(48_000, 48_000, 12_000.0)
        val output = resample(pcm, 48_000)
        val measured = rms(output.drop(100).dropLast(100).toFloatArray())

        assertTrue("Aliased high-frequency energy was $measured", measured < 0.01)
    }

    @Test
    fun resultDoesNotDependOnMediaCodecBufferBoundaries() {
        val pcm = sinePcm(44_100, 44_100, 1_000.0)
        val oneBuffer = resample(pcm, 44_100, chunkSamples = pcm.size)
        val manyBuffers = resample(pcm, 44_100, chunkSamples = 997)

        assertArrayEquals(oneBuffer, manyBuffers, 0.000001f)
    }

    private fun sinePcm(sampleRate: Int, count: Int, frequency: Double) =
        ShortArray(count) { index ->
            (16_000.0 * sin(2.0 * PI * frequency * index / sampleRate)).toInt().toShort()
        }

    private fun resample(
        pcm: ShortArray,
        sampleRate: Int,
        chunkSamples: Int = pcm.size
    ): FloatArray {
        val bytes = ByteBuffer.allocate(pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putShort(it) }
        val raw = bytes.array()
        val resampler = MonoResampler(sampleRate)
        var sampleOffset = 0
        while (sampleOffset < pcm.size) {
            val count = minOf(chunkSamples, pcm.size - sampleOffset)
            val chunk = ByteBuffer.wrap(raw, sampleOffset * 2, count * 2)
                .slice()
                .order(ByteOrder.LITTLE_ENDIAN)
            resampler.accept(chunk, 1, AudioFormat.ENCODING_PCM_16BIT)
            sampleOffset += count
        }
        return resampler.finish()
    }

    private fun rms(values: FloatArray): Double =
        sqrt(values.sumOf { it.toDouble() * it.toDouble() } / values.size)
}
