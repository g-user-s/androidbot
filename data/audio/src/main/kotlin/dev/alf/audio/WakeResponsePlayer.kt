package dev.alf.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Plays alf's answer to the wake word.
 *
 * The clips are prepared once and played from memory rather than synthesised each time. The
 * reason is latency: waking the speech engine, synthesising, and taking audio focus adds a few
 * hundred milliseconds, and a wake response that arrives late reads as the device not having
 * heard. Picking among several keeps it from sounding like a machine repeating itself.
 *
 * Clips shipped in `res/raw` are preferred — they are produced by a far better voice than the one
 * on this hardware — and synthesising with the device engine is the fallback for a build that
 * ships none.
 */
class WakeResponsePlayer(
    private val context: Context,
    private val random: Random = Random.Default,
) {
    private var pool: SoundPool? = null
    private val loaded = mutableListOf<Int>()

    /** Where the clips came from, for the log: shipped audio or the device's own engine. */
    var source: String = "none"
        private set

    val isReady: Boolean get() = loaded.isNotEmpty()

    suspend fun prepare(tts: TurkishTts, responses: List<String>) {
        release()
        val soundPool = newPool().also { pool = it }

        val shipped = shippedClipIds(responses.size)
        if (shipped.isNotEmpty()) {
            source = "res/raw"
            shipped.forEach { resourceId ->
                awaitLoad(soundPool) { soundPool.load(context, resourceId, 1) }?.let { loaded += it }
            }
            if (loaded.isNotEmpty()) return
        }

        source = "device tts"
        val cacheDir = File(context.cacheDir, "wake").apply { mkdirs() }
        for ((index, text) in responses.withIndex()) {
            val file = File(cacheDir, "wake_$index.wav")
            val ready = (file.exists() && file.length() > 0) || tts.synthesizeToFile(text, file)
            if (!ready) continue
            awaitLoad(soundPool) { soundPool.load(file.absolutePath, 1) }?.let { loaded += it }
        }
    }

    /** Returns false when nothing is loaded, so the caller can fall back to speaking. */
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
        source = "none"
    }

    /** `wake_0`, `wake_1`, ... in `res/raw`; empty unless every clip is present. */
    private fun shippedClipIds(count: Int): List<Int> {
        val ids = (0 until count).map { index ->
            @Suppress("DiscouragedApi")
            context.resources.getIdentifier("wake_$index", "raw", context.packageName)
        }
        return if (ids.all { it != 0 }) ids else emptyList()
    }

    private fun newPool(): SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    private suspend fun awaitLoad(soundPool: SoundPool, load: () -> Int): Int? =
        suspendCancellableCoroutine { continuation ->
            val requested = load()
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
