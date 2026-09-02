package dev.alf.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic stand-ins for speech.
 *
 * Real recordings would make these tests better and unrunnable in CI. Tone sequences are enough
 * to pin down the properties that matter: that two utterances of the same thing at different
 * speeds land close together, that different content lands far apart, and that the segmenter
 * finds the loud part.
 */
object TestSignals {

    const val SAMPLE_RATE = 16_000

    fun tone(frequencyHz: Double, durationMs: Int, amplitude: Double = 0.3): FloatArray {
        val count = SAMPLE_RATE * durationMs / 1000
        return FloatArray(count) { i ->
            (amplitude * sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE)).toFloat()
        }
    }

    fun quiet(durationMs: Int, seed: Int = 7, amplitude: Double = 0.001): FloatArray {
        val random = Random(seed)
        val count = SAMPLE_RATE * durationMs / 1000
        return FloatArray(count) { ((random.nextDouble() - 0.5) * 2 * amplitude).toFloat() }
    }

    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /**
     * A "phrase": a fixed run of tones. [speed] shortens or lengthens every tone by the same
     * factor, which is the same thing a person does when they say something quickly.
     */
    fun phrase(vararg tones: Pair<Double, Int>, speed: Double = 1.0, amplitude: Double = 0.3): FloatArray =
        concat(*tones.map { (frequency, durationMs) ->
            tone(frequency, (durationMs / speed).toInt(), amplitude)
        }.toTypedArray())

    fun frames(signal: FloatArray, frameLength: Int): List<FloatArray> =
        (0..signal.size - frameLength step frameLength).map {
            signal.copyOfRange(it, it + frameLength)
        }
}
