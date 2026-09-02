package dev.alf.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplerTest {

    private fun tone(frequencyHz: Double, rate: Int, durationMs: Int): FloatArray {
        val count = rate * durationMs / 1000
        return FloatArray(count) { sin(2.0 * PI * frequencyHz * it / rate).toFloat() }
    }

    /** Dominant frequency, found through the FFT already in the module. */
    private fun peakHz(signal: FloatArray, rate: Int): Double {
        val size = 4096
        val spectrum = DoubleArray(size / 2 + 1)
        val window = DoubleArray(size) { i ->
            if (i < signal.size) signal[i] * (0.54 - 0.46 * kotlin.math.cos(2.0 * PI * i / (size - 1))) else 0.0
        }
        Fft.powerSpectrum(window, size, spectrum)
        val peak = spectrum.indices.maxBy { spectrum[it] }
        return peak.toDouble() * rate / size
    }

    private fun energyAround(signal: FloatArray, rate: Int, centreHz: Double, widthHz: Double): Double {
        val size = 4096
        val spectrum = DoubleArray(size / 2 + 1)
        val window = DoubleArray(size) { i -> if (i < signal.size) signal[i].toDouble() else 0.0 }
        Fft.powerSpectrum(window, size, spectrum)
        val binWidth = rate.toDouble() / size
        return spectrum.indices
            .filter { abs(it * binWidth - centreHz) <= widthHz }
            .sumOf { spectrum[it] }
    }

    @Test
    fun `identical rates are a copy`() {
        val signal = tone(1_000.0, 16_000, 100)
        assertContentEquals(signal, Resampler.resample(signal, 16_000, 16_000))
    }

    @Test
    fun `output length follows the ratio`() {
        val signal = tone(440.0, 24_000, 500)
        val out = Resampler.resample(signal, 24_000, 16_000)

        assertEquals(signal.size * 2 / 3, out.size, "expected two thirds of ${signal.size}")
    }

    @Test
    fun `a tone keeps its pitch when downsampled`() {
        // 24 kHz is what a text to speech engine typically writes; the reference has to end up at
        // the microphone's 16 kHz without changing what was said.
        val out = Resampler.resample(tone(1_000.0, 24_000, 400), 24_000, 16_000)

        assertTrue(abs(peakHz(out, 16_000) - 1_000.0) < 25.0, "peak moved to ${peakHz(out, 16_000)} Hz")
    }

    @Test
    fun `a tone keeps its pitch when upsampled`() {
        val out = Resampler.resample(tone(1_200.0, 8_000, 400), 8_000, 16_000)

        assertTrue(abs(peakHz(out, 16_000) - 1_200.0) < 25.0, "peak moved to ${peakHz(out, 16_000)} Hz")
    }

    @Test
    fun `content above the new Nyquist is filtered out instead of folding back`() {
        // Without the low pass, 10 kHz sampled down to 16 kHz reappears as a 6 kHz tone.
        val out = Resampler.resample(tone(10_000.0, 24_000, 400), 24_000, 16_000)

        val aliasEnergy = energyAround(out, 16_000, centreHz = 6_000.0, widthHz = 200.0)
        val reference = energyAround(
            Resampler.resample(tone(1_000.0, 24_000, 400), 24_000, 16_000),
            16_000,
            centreHz = 1_000.0,
            widthHz = 200.0,
        )

        assertTrue(aliasEnergy < reference / 1_000, "alias energy $aliasEnergy against reference $reference")
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(Resampler.resample(FloatArray(0), 24_000, 16_000).isEmpty())
    }

    @Test
    fun `a resampled reference still matches the original`() {
        // The end to end property: the same phrase, one path through a rate conversion, must not
        // drift far enough to break matching.
        val extractor = MfccExtractor()
        val at16k = TestSignals.phrase(400.0 to 150, 1_200.0 to 150, 700.0 to 150)

        val at24k = FloatArray(at16k.size * 3 / 2) { i ->
            val position = i * 2.0 / 3
            val left = position.toInt().coerceAtMost(at16k.size - 1)
            val right = (left + 1).coerceAtMost(at16k.size - 1)
            val fraction = position - left
            (at16k[left] * (1 - fraction) + at16k[right] * fraction).toFloat()
        }

        val distance = Dtw.distance(
            extractor.extract(at16k),
            extractor.extract(Resampler.resample(at24k, 24_000, 16_000)),
        )
        assertTrue(distance < 0.6, "rate conversion moved the features by $distance")
    }
}
