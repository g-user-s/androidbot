package dev.alf.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {

    @Test
    fun `matches a naive DFT`() {
        val size = 64
        val random = Random(1)
        val signal = DoubleArray(size) { random.nextDouble() - 0.5 }

        val actual = DoubleArray(size / 2 + 1)
        Fft.powerSpectrum(signal, size, actual)

        for (bin in actual.indices) {
            var re = 0.0
            var im = 0.0
            for (n in 0 until size) {
                val angle = -2.0 * PI * bin * n / size
                re += signal[n] * cos(angle)
                im += signal[n] * sin(angle)
            }
            val expected = re * re + im * im
            assertTrue(
                abs(expected - actual[bin]) < 1e-9 * (1 + abs(expected)),
                "bin $bin: expected $expected but was ${actual[bin]}",
            )
        }
    }

    @Test
    fun `a sinusoid peaks at its own bin`() {
        val size = 256
        val bin = 20
        val signal = DoubleArray(size) { sin(2.0 * PI * bin * it / size) }

        val spectrum = DoubleArray(size / 2 + 1)
        Fft.powerSpectrum(signal, size, spectrum)

        assertEquals(bin, spectrum.indices.maxBy { spectrum[it] })
    }

    @Test
    fun `shorter input is zero padded`() {
        val spectrum = DoubleArray(33)
        Fft.powerSpectrum(DoubleArray(10) { 1.0 }, 64, spectrum)

        // Ten ones and 54 zeros: the DC bin holds the squared sum.
        assertTrue(abs(spectrum[0] - 100.0) < 1e-9)
    }

    @Test
    fun `non power of two sizes are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Fft.transform(DoubleArray(6), DoubleArray(6))
        }
    }
}
