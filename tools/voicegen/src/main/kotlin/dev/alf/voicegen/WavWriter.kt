package dev.alf.voicegen

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps raw PCM in a WAV header.
 *
 * The speech service returns headerless 16 bit little endian PCM, while everything downstream —
 * the wake clip player on the device, `WavReader` in the feature pipeline — expects a file it can
 * open. This is the 44 byte difference between the two.
 */
object WavWriter {

    fun wrap(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(channels > 0) { "channel count must be positive" }
        require(bitsPerSample == 8 || bitsPerSample == 16) { "unsupported bit depth $bitsPerSample" }

        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        fun short(value: Int) =
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())

        ascii("RIFF")
        int(36 + pcm.size)
        ascii("WAVE")

        ascii("fmt ")
        int(16)
        short(1) // PCM
        short(channels)
        int(sampleRate)
        int(byteRate)
        short(blockAlign)
        short(bitsPerSample)

        ascii("data")
        int(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
