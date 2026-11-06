package com.example.audiotranscriber.ml

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

/** Torchaudio-compatible HTK log-mel, periodic Hann, 320 FFT, 160 hop, no centering. */
internal object MelFeatures {
    private val window = DoubleArray(320) { 0.5 - 0.5 * cos(2 * PI * it / 320) }
    private val filters: Array<DoubleArray> = run {
        val maxMel = 2595.0 * log10(1 + 8000.0 / 700)
        val hz = DoubleArray(66) { 700 * (10.0.pow(maxMel * it / 65 / 2595) - 1) }
        Array(64) { m -> DoubleArray(161) { bin ->
            val f = bin * 50.0
            max(0.0, min((f - hz[m]) / (hz[m + 1] - hz[m]), (hz[m + 2] - f) / (hz[m + 2] - hz[m + 1])))
        } }
    }
    fun extract(audio: FloatArray): FloatArray {
        require(audio.size >= 320)
        val frames = 1 + (audio.size - 320) / 160
        val result = FloatArray(64 * frames)
        val fft = DoubleFFT_1D(320)
        val buffer = DoubleArray(640)
        val power = DoubleArray(161)
        for (t in 0 until frames) {
            buffer.fill(0.0)
            for (i in 0 until 320) buffer[2 * i] = audio[t * 160 + i] * window[i]
            fft.complexForward(buffer)
            for (i in 0..160) power[i] = buffer[2*i].pow(2) + buffer[2*i+1].pow(2)
            for (m in 0 until 64) {
                var sum = 0.0
                for (i in 0..160) sum += power[i] * filters[m][i]
                result[m * frames + t] = ln(sum.coerceIn(1e-9, 1e9)).toFloat()
            }
        }
        return result
    }
}
