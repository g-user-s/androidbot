package dev.alf.dsp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavReaderTest {

    /** Builds the kind of file a text to speech engine writes. */
    private fun wav(
        samples: ShortArray,
        sampleRate: Int = 24_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        extraChunkBefore: Boolean = false,
    ): ByteArray {
        val data = ByteArrayOutputStream()
        val dataBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { dataBuffer.putShort(it) }
        data.write(dataBuffer.array())

        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        fun short(value: Int) =
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())

        ascii("RIFF")
        int(0) // patched by the reader's tolerance for a wrong RIFF size
        ascii("WAVE")

        if (extraChunkBefore) {
            // Engines routinely emit LIST/INFO before the format chunk.
            ascii("LIST")
            int(5)
            out.write(byteArrayOf(1, 2, 3, 4, 5))
            out.write(0) // pad byte, chunks are word aligned
        }

        ascii("fmt ")
        int(16)
        short(1)
        short(channels)
        int(sampleRate)
        int(sampleRate * channels * bitsPerSample / 8)
        short(channels * bitsPerSample / 8)
        short(bitsPerSample)

        ascii("data")
        int(data.size())
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    @Test
    fun `reads mono sixteen bit audio`() {
        val audio = WavReader.read(wav(shortArrayOf(0, 16384, -16384, 32767), sampleRate = 22_050))

        assertEquals(22_050, audio.sampleRate)
        assertEquals(4, audio.samples.size)
        assertTrue(abs(audio.samples[1] - 0.5f) < 0.001f)
        assertTrue(abs(audio.samples[2] + 0.5f) < 0.001f)
    }

    @Test
    fun `the engine's sample rate is reported rather than assumed`() {
        assertEquals(24_000, WavReader.read(wav(ShortArray(8), sampleRate = 24_000)).sampleRate)
        assertEquals(16_000, WavReader.read(wav(ShortArray(8), sampleRate = 16_000)).sampleRate)
    }

    @Test
    fun `stereo is mixed down to mono`() {
        val audio = WavReader.read(wav(shortArrayOf(32767, -32767, 16384, 16384), channels = 2))

        assertEquals(2, audio.samples.size)
        assertTrue(abs(audio.samples[0]) < 0.001f, "opposite channels should cancel")
        assertTrue(abs(audio.samples[1] - 0.5f) < 0.001f)
    }

    @Test
    fun `chunks before the format chunk are skipped`() {
        val audio = WavReader.read(wav(shortArrayOf(0, 16384), extraChunkBefore = true))

        assertEquals(24_000, audio.sampleRate)
        assertEquals(2, audio.samples.size)
    }

    @Test
    fun `a non wav file is rejected`() {
        assertFailsWith<IOException> { WavReader.read(ByteArray(64) { 3 }) }
        assertFailsWith<IOException> { WavReader.read(ByteArray(4)) }
    }

    @Test
    fun `an unsupported bit depth is rejected`() {
        assertFailsWith<IOException> { WavReader.read(wav(ShortArray(8), bitsPerSample = 24)) }
    }

    @Test
    fun `synthesised audio survives the whole path into features`() {
        // What the template builder actually does: read what the engine wrote, bring it to the
        // microphone's rate, turn it into features.
        val at24k = ShortArray(24_000 / 2) {
            (16000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * it / 24_000)).toInt().toShort()
        }

        val audio = WavReader.read(wav(at24k, sampleRate = 24_000))
        val features = MfccExtractor().extract(Resampler.resample(audio.samples, audio.sampleRate, 16_000))

        assertTrue(features.size > 40, "expected features from half a second of audio, got ${features.size}")
    }
}
