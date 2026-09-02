package dev.alf.voicegen

import dev.alf.dsp.Dtw
import dev.alf.dsp.MfccExtractor
import dev.alf.nlu.OfflineVocabulary
import dev.alf.nlu.VocabularyEntry
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stand-in voice: each phrase becomes a tone whose pitch is derived from its text. */
private class TonedVoice(private val failOn: Set<String> = emptySet()) : SpeechSource {
    override fun synthesize(text: String, voiceId: String): ByteArray {
        if (text in failOn) throw IOException("voice refused '$text'")
        val frequency = 300.0 + (abs(text.hashCode()) % 1_200)
        val samples = ShortArray(16_000 / 2) {
            (12_000 * sin(2.0 * PI * frequency * it / 16_000)).toInt().toShort()
        }
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            out[i * 2] = (value.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun abs(value: Int) = if (value < 0) -value else value
}

class TemplateBuilderTest {

    private val entries = listOf(
        VocabularyEntry("__wake__", "hey alf", emptyMap()),
        VocabularyEntry("time_now", "saat kaç", emptyMap()),
        VocabularyEntry("set_alarm", "alarmı yediye kur", mapOf("hour" to "7")),
    )

    @Test
    fun `one template per phrase per voice`() {
        val result = TemplateBuilder(TonedVoice()).build(entries, listOf("voice-a", "voice-b"))

        assertEquals(6, result.templates.size)
        assertEquals(setOf("voice-a", "voice-b"), result.templates.map { it.source }.toSet())
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `skill ids and parameters ride along`() {
        val alarm = TemplateBuilder(TonedVoice()).build(entries, listOf("v"))
            .templates.single { it.phrase == "alarmı yediye kur" }

        assertEquals("set_alarm", alarm.skillId)
        assertEquals(mapOf("hour" to "7"), alarm.params)
    }

    @Test
    fun `a phrase the voice refuses is reported, not fatal`() {
        val result = TemplateBuilder(TonedVoice(failOn = setOf("saat kaç"))).build(entries, listOf("v"))

        assertEquals(2, result.templates.size)
        assertEquals(1, result.failures.size)
        assertEquals("saat kaç", result.failures.single().phrase)
    }

    @Test
    fun `templates land in the same feature space as the microphone`() {
        // The service is asked for 16 kHz precisely so this holds: audio through the builder and
        // the same audio straight off the microphone have to be comparable.
        val voice = TonedVoice()
        val template = TemplateBuilder(voice).build(entries.take(1), listOf("v")).templates.single()

        val pcm = voice.synthesize("hey alf", "v")
        val direct = MfccExtractor().extract(
            FloatArray(pcm.size / 2) { i ->
                val low = pcm[i * 2].toInt() and 0xFF
                val high = pcm[i * 2 + 1].toInt()
                ((high shl 8) or low).toShort() / Short.MAX_VALUE.toFloat()
            },
        )

        assertEquals(0.0, Dtw.distance(template.features, direct))
    }

    @Test
    fun `the whole shipped vocabulary can be built`() {
        val result = TemplateBuilder(TonedVoice()).build(OfflineVocabulary.build(), listOf("v"))

        assertEquals(OfflineVocabulary.build().size, result.templates.size)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `building without a voice is a programming error`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TemplateBuilder(TonedVoice()).build(entries, emptyList())
        }
    }
}
