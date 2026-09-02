package dev.alf.voicegen

import dev.alf.dsp.WavReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavWriterTest {

    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            out[i * 2] = (value.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `what it writes is what the reader reads back`() {
        val samples = ShortArray(1_600) { (16_000 * sin(2.0 * PI * 440.0 * it / 16_000)).toInt().toShort() }

        val audio = WavReader.read(WavWriter.wrap(pcm(samples), sampleRate = 16_000))

        assertEquals(16_000, audio.sampleRate)
        assertEquals(samples.size, audio.samples.size)
        samples.indices.forEach { i ->
            assertTrue(abs(audio.samples[i] - samples[i] / 32767f) < 0.001f, "sample $i drifted")
        }
    }

    @Test
    fun `the header is the expected 44 bytes`() {
        assertEquals(44 + 100, WavWriter.wrap(ByteArray(100), sampleRate = 16_000).size)
    }

    @Test
    fun `an empty recording still produces a valid file`() {
        val audio = WavReader.read(WavWriter.wrap(ByteArray(0), sampleRate = 16_000))

        assertEquals(16_000, audio.sampleRate)
        assertTrue(audio.samples.isEmpty())
    }

    @Test
    fun `unsupported bit depths are refused`() {
        assertFailsWith<IllegalArgumentException> {
            WavWriter.wrap(ByteArray(4), sampleRate = 16_000, bitsPerSample = 24)
        }
    }
}
