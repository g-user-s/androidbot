package dev.alf.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException

/**
 * The microphone, as a stream of fixed size frames.
 *
 * Frames rather than raw buffers because everything downstream — the segmenter first — is defined
 * in frames, and because reads from [AudioRecord] come back in whatever size the driver feels
 * like. Reassembling here means the rest of the pipeline never deals with partial frames.
 */
class MicrophoneSource(
    private val sampleRate: Int = 16_000,
    private val frameLength: Int = 320,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
) {

    /**
     * Emits until the collecting coroutine is cancelled, then releases the device.
     *
     * The buffer is deliberately several frames deep: this runs on a slow CPU alongside feature
     * extraction, and a buffer sized to a single frame would drop audio during any hiccup — which
     * shows up as a clipped wake word rather than as an error.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<FloatArray> = flow {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) throw IOException("this device cannot record ${sampleRate} Hz mono")

        val record = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, frameLength * BYTES_PER_SAMPLE * BUFFERED_FRAMES),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("could not open the microphone")
        }

        try {
            record.startRecording()
            val pcm = ShortArray(frameLength)
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < frameLength) {
                    val read = record.read(pcm, filled, frameLength - filled)
                    if (read <= 0) throw IOException("microphone read failed with $read")
                    filled += read
                }
                emit(FloatArray(frameLength) { pcm[it] / Short.MAX_VALUE.toFloat() })
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val BUFFERED_FRAMES = 8
    }
}
