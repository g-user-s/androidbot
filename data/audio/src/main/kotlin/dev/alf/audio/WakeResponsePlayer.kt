package dev.alf.audio

import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Plays alf's answer to the wake word.
 *
 * The clips are rendered once with the speech engine and then played from memory, rather than
 * synthesised each time. The reason is latency: waking the engine, synthesising and taking audio
 * focus adds a few hundred milliseconds, and a wake response that arrives late reads as the
 * device not having heard. A handful of fixed words is exactly the case where a cached clip beats
 * live synthesis, and picking among several keeps it from sounding like a machine repeating itself.
 */
class WakeResponsePlayer(
    private val cacheDir: File,
    private val random: Random = Random.Default,
) {
    private var pool: SoundPool? = null
    private val loaded = mutableListOf<Int>()

    val isReady: Boolean get() = loaded.isNotEmpty()

    /** Renders any clip that is not cached yet, then loads them all into memory. */
    suspend fun prepare(tts: TurkishTts, responses: List<String>) {
        release()
        cacheDir.mkdirs()

        val files = responses.mapIndexedNotNull { index, text ->
            val file = File(cacheDir, "wake_$index.wav")
            if (file.exists() && file.length() > 0) file
            else if (tts.synthesizeToFile(text, file)) file
            else null
        }
        if (files.isEmpty()) return

        val soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        pool = soundPool

        for (file in files) {
            val id = awaitLoad(soundPool, file)
            if (id != null) loaded += id
        }
    }

    /** Returns false when nothing is cached, so the caller can fall back to speaking. */
    fun play(): Boolean {
        val soundPool = pool ?: return false
        if (loaded.isEmpty()) return false
        soundPool.play(loaded[random.nextInt(loaded.size)], 1f, 1f, 1, 0, 1f)
        return true
    }

    fun release() {
        pool?.release()
        pool = null
        loaded.clear()
    }

    private suspend fun awaitLoad(soundPool: SoundPool, file: File): Int? =
        suspendCancellableCoroutine { continuation ->
            val requested = soundPool.load(file.absolutePath, 1)
            if (requested == 0) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            soundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (sampleId == requested && continuation.isActive) {
                    continuation.resume(if (status == 0) sampleId else null)
                }
            }
        }
}
