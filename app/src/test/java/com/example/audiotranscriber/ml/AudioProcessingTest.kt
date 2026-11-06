package com.example.audiotranscriber.ml

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

class AudioProcessingTest {
    private fun pcm(values: ShortArray): ByteBuffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEach { putShort(it) }; flip()
    }

    @Test fun resamplingPreservesRampAcrossCodecBufferBoundaries() {
        val source = ShortArray(441) { (it * 30).toShort() }
        val one = MonoResampler(44100).apply { accept(pcm(source), 1, 2) }.finish()
        val split = MonoResampler(44100).apply {
            accept(pcm(source.copyOfRange(0, 37)), 1, 2)
            accept(pcm(source.copyOfRange(37, 102)), 1, 2)
            accept(pcm(source.copyOfRange(102, source.size)), 1, 2)
        }.finish()
        assertEquals(160, one.size)
        assertArrayEquals(one, split, 0f)
        one.forEachIndexed { i, value -> assertEquals((i * 44100.0 / 16000 * 30 / 32768).toFloat(), value, 0.000001f) }
    }

    @Test fun stereoDownmixCancelsOppositeChannels() {
        val stereo = ShortArray(160 * 2) { if (it % 2 == 0) 10000 else -10000 }
        val samples = MonoResampler(16000).apply { accept(pcm(stereo), 2, 2) }.finish()
        assertEquals(160, samples.size)
        assertTrue(samples.all { it == 0f })
    }

    @Test fun melSilenceMatchesGigaamLogFloorAndNoCentering() {
        val features = MelFeatures.extract(FloatArray(16000))
        assertEquals(64 * 99, features.size)
        assertTrue(features.all { kotlin.math.abs(it - ln(1e-9).toFloat()) < 0.000001f })
    }
    @Test fun melSineMatchesIndependentNumpyFftReference() {
        val signal = FloatArray(320) { (kotlin.math.sin(2 * Math.PI * 500 * it / 16000) * 0.25).toFloat() }
        val expected = floatArrayOf(
            -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, 2.98631620f, 4.78576136f, 5.89965820f, 4.55893898f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f, -20.72326660f
        )
        assertArrayEquals(expected, MelFeatures.extract(signal), 0.001f)
    }

}
