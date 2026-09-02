package dev.alf.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rate conversion for signals that are about to be turned into features.
 *
 * Needed because the two sides of a comparison arrive at different rates: the microphone is
 * opened at 16 kHz, while a text to speech engine writes whatever it likes, commonly 22.05 or
 * 24 kHz. Feeding both into [MfccExtractor] without conversion would put the reference and the
 * live audio in different feature spaces and every distance would be meaningless.
 *
 * Downsampling low-passes first. Skipping that step folds everything above the new Nyquist back
 * into the audible range — a 10 kHz sibilant reappearing as a 6 kHz tone is exactly the kind of
 * artefact that would differ between a synthesised reference and a real voice.
 */
object Resampler {

    private const val FILTER_TAPS = 63

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()

        val filtered = if (toRate < fromRate) {
            // Cutoff sits a little below the new Nyquist to leave the filter room to roll off.
            lowPass(input, cutoffHz = 0.45 * toRate, sampleRate = fromRate)
        } else {
            input
        }

        val ratio = toRate.toDouble() / fromRate
        val outputSize = (input.size * ratio).roundToInt()
        if (outputSize <= 0) return FloatArray(0)

        return FloatArray(outputSize) { i ->
            val position = i / ratio
            val left = position.toInt()
            val right = left + 1
            val fraction = position - left
            when {
                right < filtered.size -> (filtered[left] * (1 - fraction) + filtered[right] * fraction).toFloat()
                left < filtered.size -> filtered[left]
                else -> filtered.last()
            }
        }
    }

    /** Windowed sinc FIR, applied directly. The clips involved are seconds long, not minutes. */
    private fun lowPass(input: FloatArray, cutoffHz: Double, sampleRate: Int): FloatArray {
        val normalisedCutoff = cutoffHz / sampleRate
        val half = FILTER_TAPS / 2
        val taps = DoubleArray(FILTER_TAPS) { i ->
            val n = i - half
            val sinc = if (n == 0) 2.0 * normalisedCutoff else sin(2.0 * PI * normalisedCutoff * n) / (PI * n)
            // Hamming
            sinc * (0.54 - 0.46 * cos(2.0 * PI * i / (FILTER_TAPS - 1)))
        }
        val gain = taps.sum()
        for (i in taps.indices) taps[i] /= gain

        return FloatArray(input.size) { i ->
            var sum = 0.0
            for (t in taps.indices) {
                val index = i + t - half
                if (index in input.indices) sum += input[index] * taps[t]
            }
            sum.toFloat()
        }
    }
}
