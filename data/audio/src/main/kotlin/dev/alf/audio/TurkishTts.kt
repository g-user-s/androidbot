package dev.alf.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * The platform speech engine, wrapped so callers can await it instead of juggling callbacks.
 *
 * Turkish is usually installed but never guaranteed: a device without Google's services has no
 * Turkish voice at all — the AOSP engine ships English, German, Spanish, French and Italian — a
 * user may have deleted the voice data to free space, and some Turkish voices are the network
 * only kind, which is useless to an assistant whose whole point is working offline. [start]
 * distinguishes those cases so the caller can send the user somewhere useful rather than
 * failing silently at the first thing alf tries to say.
 */
class TurkishTts(private val context: Context) {

    enum class Status {
        /** A Turkish voice is installed and usable. */
        Ready,

        /** The engine works but has no Turkish voice data; offer ACTION_INSTALL_TTS_DATA. */
        MissingVoice,

        /** No usable engine at all. */
        Unavailable,
    }

    private var engine: TextToSpeech? = null
    private val utteranceCounter = AtomicLong()

    val isReady: Boolean get() = engine != null

    suspend fun start(): Status = suspendCancellableCoroutine { continuation ->
        val holder = arrayOfNulls<TextToSpeech>(1)
        val resumed = AtomicBoolean(false)

        fun finish(status: Status) {
            if (resumed.compareAndSet(false, true)) continuation.resume(status)
        }

        holder[0] = TextToSpeech(context) { initStatus ->
            val tts = holder[0]
            if (initStatus != TextToSpeech.SUCCESS || tts == null) {
                finish(Status.Unavailable)
                return@TextToSpeech
            }
            engine = tts
            finish(
                when (tts.setLanguage(TURKISH)) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Status.MissingVoice
                    else -> Status.Ready
                },
            )
        }

        continuation.invokeOnCancellation { shutdown() }
    }

    /**
     * Turkish voices that can speak without a network.
     *
     * More than one is worth having: every voice added to the reference set is another way of
     * saying the same phrase for the matcher to compare against, which is the cheapest available
     * defence against a synthesised template sounding nothing like the person in the room.
     */
    fun offlineTurkishVoices(): List<Voice> {
        val voices = engine?.voices ?: return emptyList()
        return voices
            .filter { voice ->
                val language = voice.locale.language
                (language.equals("tr", ignoreCase = true) || language.equals("tur", ignoreCase = true)) &&
                    !voice.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
            }
            .sortedBy { it.name }
    }

    suspend fun speak(text: String): Boolean = withUtterance { tts, id ->
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    suspend fun synthesizeToFile(text: String, target: File, voice: Voice? = null): Boolean =
        withUtterance { tts, id ->
            // setVoice returns an int, so it is not a Kotlin property.
            if (voice != null) tts.setVoice(voice)
            tts.synthesizeToFile(text, Bundle(), target, id)
        }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
    }

    /** Runs [action] and suspends until that specific utterance reports done or failed. */
    private suspend fun withUtterance(action: (TextToSpeech, String) -> Int): Boolean =
        suspendCancellableCoroutine { continuation ->
            val tts = engine
            if (tts == null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            val id = "alf-${utteranceCounter.incrementAndGet()}"
            val resumed = AtomicBoolean(false)
            fun finish(success: Boolean) {
                if (resumed.compareAndSet(false, true)) continuation.resume(success)
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) finish(true)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) finish(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id) finish(false)
                }
            })

            if (action(tts, id) != TextToSpeech.SUCCESS) finish(false)
        }

    private companion object {
        val TURKISH: Locale = Locale("tr", "TR")
    }
}
