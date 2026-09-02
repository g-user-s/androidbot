package dev.alf.dsp

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    override fun equals(other: Any?): Boolean =
        other is PcmAudio && sampleRate == other.sampleRate && samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

/**
 * Reads the WAV files the text to speech engine writes.
 *
 * Only what that produces is supported: uncompressed PCM, 8 or 16 bit. The sample rate is
 * whatever the engine chose — commonly 22.05 or 24 kHz — and is returned rather than assumed, so
 * the caller can hand it to `Resampler` and land on the microphone's rate.
 */
object WavReader {

    fun read(file: File): PcmAudio = read(file.readBytes())

    fun read(bytes: ByteArray): PcmAudio {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12) throw IOException("file is too short to be a WAV")
        if (tag(buffer, 0) != "RIFF" || tag(buffer, 8) != "WAVE") throw IOException("not a RIFF/WAVE file")

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = tag(buffer, cursor)
            val size = buffer.getInt(cursor + 4)
            if (size < 0) throw IOException("chunk '$id' declares a negative size")
            val body = cursor + 8

            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) throw IOException("truncated fmt chunk")
                    val format = buffer.getShort(body).toInt()
                    if (format != PCM && format != EXTENSIBLE) {
                        throw IOException("unsupported WAV encoding $format")
                    }
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bitsPerSample = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataSize = minOf(size, bytes.size - body)
                }
            }
            // Chunks are word aligned, so an odd size carries a pad byte.
            cursor = body + size + (size and 1)
        }

        if (dataOffset < 0) throw IOException("no data chunk")
        if (channels <= 0 || sampleRate <= 0) throw IOException("no usable fmt chunk")

        val samples = when (bitsPerSample) {
            16 -> FloatArray(dataSize / 2) { buffer.getShort(dataOffset + it * 2) / Short.MAX_VALUE.toFloat() }
            8 -> FloatArray(dataSize) { (bytes[dataOffset + it].toInt() and 0xFF) / 128f - 1f }
            else -> throw IOException("unsupported bit depth $bitsPerSample")
        }

        return PcmAudio(if (channels == 1) samples else mixToMono(samples, channels), sampleRate)
    }

    private fun mixToMono(interleaved: FloatArray, channels: Int): FloatArray {
        val frames = interleaved.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
            sum / channels
        }
    }

    private fun tag(buffer: ByteBuffer, at: Int): String =
        String(CharArray(4) { buffer.get(at + it).toInt().toChar() })

    private const val PCM = 1
    private const val EXTENSIBLE = 0xFFFE
}
