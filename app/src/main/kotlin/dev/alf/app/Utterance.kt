package dev.alf.app

import dev.alf.dsp.WavWriter
import java.util.Base64

/**
 * Packs a captured utterance the way the model service expects it.
 *
 * The samples are already at the rate the microphone was opened with; they are wrapped in a WAV
 * header because a headerless block of PCM carries no sample rate, and a service told the wrong
 * rate hears a voice at the wrong speed.
 */
internal fun FloatArray.toWavBase64(sampleRate: Int): String {
    val pcm = ByteArray(size * 2)
    forEachIndexed { index, sample ->
        val clamped = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        pcm[index * 2] = (clamped and 0xFF).toByte()
        pcm[index * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
    }
    return Base64.getEncoder().encodeToString(WavWriter.wrap(pcm, sampleRate))
}
