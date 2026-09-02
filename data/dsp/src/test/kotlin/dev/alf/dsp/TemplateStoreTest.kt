package dev.alf.dsp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateStoreTest {

    private val extractor = MfccExtractor()

    private fun roundTrip(templates: List<PhraseTemplate>): List<PhraseTemplate> {
        val bytes = ByteArrayOutputStream().also { TemplateStore.write(templates, it) }.toByteArray()
        return TemplateStore.read(ByteArrayInputStream(bytes))
    }

    @Test
    fun `templates survive a round trip`() {
        val original = listOf(
            PhraseTemplate(
                phrase = "alarmı yediye kur",
                skillId = "set_alarm",
                params = mapOf("hour" to "7"),
                features = extractor.extract(TestSignals.phrase(400.0 to 150, 900.0 to 150)),
                source = "tr-tr-x-ahmet",
            ),
            PhraseTemplate(
                phrase = "hey alf",
                skillId = "__wake__",
                features = extractor.extract(TestSignals.phrase(700.0 to 200)),
                source = "tr-tr-x-elif",
            ),
        )

        val restored = roundTrip(original)

        assertEquals(original.size, restored.size)
        original.zip(restored).forEach { (before, after) ->
            assertEquals(before.phrase, after.phrase)
            assertEquals(before.skillId, after.skillId)
            assertEquals(before.params, after.params)
            assertEquals(before.source, after.source)
            assertEquals(before.features.size, after.features.size)
            assertEquals(before.features.dimension, after.features.dimension)
        }
    }

    @Test
    fun `float storage stays well inside matching tolerance`() {
        val original = listOf(
            PhraseTemplate("x", "x", features = extractor.extract(TestSignals.phrase(500.0 to 200))),
        )

        val restored = roundTrip(original)

        val drift = Dtw.distance(original[0].features, restored[0].features)
        assertTrue(drift < 1e-4, "storing as float moved the features by $drift")
    }

    @Test
    fun `an empty vocabulary round trips`() {
        assertTrue(roundTrip(emptyList()).isEmpty())
    }

    @Test
    fun `turkish characters survive`() {
        val restored = roundTrip(
            listOf(PhraseTemplate("ışığı aç", "light", features = extractor.extract(TestSignals.phrase(600.0 to 150)))),
        )

        assertEquals("ışığı aç", restored.single().phrase)
    }

    @Test
    fun `a foreign file is rejected`() {
        assertFailsWith<IOException> {
            TemplateStore.read(ByteArrayInputStream(ByteArray(64) { 9 }))
        }
    }

    @Test
    fun `frames keep their values`() {
        val features = extractor.extract(TestSignals.phrase(800.0 to 150))
        val restored = roundTrip(listOf(PhraseTemplate("p", "s", features = features)))

        features.frames.zip(restored.single().features.frames).forEach { (before, after) ->
            before.indices.forEach { i ->
                assertTrue(abs(before[i] - after[i]) < 1e-5, "coefficient $i drifted")
            }
        }
    }
}
