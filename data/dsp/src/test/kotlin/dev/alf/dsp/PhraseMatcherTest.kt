package dev.alf.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhraseMatcherTest {

    private val extractor = MfccExtractor()

    private val wake = listOf(400.0 to 150, 1200.0 to 150, 700.0 to 150)
    private val alarm = listOf(2200.0 to 150, 600.0 to 150, 1800.0 to 150)
    private val stranger = listOf(3000.0 to 200, 350.0 to 250)

    private fun templateOf(phrase: String, tones: List<Pair<Double, Int>>, speed: Double = 1.0, source: String = "tts") =
        PhraseTemplate(
            phrase = phrase,
            skillId = phrase,
            features = extractor.extract(TestSignals.phrase(*tones.toTypedArray(), speed = speed)),
            source = source,
        )

    private fun heard(tones: List<Pair<Double, Int>>, speed: Double = 1.0) =
        extractor.extract(TestSignals.phrase(*tones.toTypedArray(), speed = speed))

    private val templates = listOf(
        templateOf("hey alf", wake),
        templateOf("alarmı yediye kur", alarm),
    )

    @Test
    fun `picks the phrase that was said`() {
        val matcher = PhraseMatcher(templates, acceptDistance = 10.0)

        val match = assertNotNull(matcher.match(heard(wake, speed = 1.3)))
        assertEquals("hey alf", match.phrase)
    }

    @Test
    fun `an unknown utterance is refused`() {
        val matcher = PhraseMatcher(templates, acceptDistance = 0.5)

        assertNull(matcher.match(heard(stranger)))
    }

    @Test
    fun `nothing matches an empty capture`() {
        val matcher = PhraseMatcher(templates, acceptDistance = 10.0)

        assertNull(matcher.match(FeatureSequence(emptyList())))
    }

    @Test
    fun `an empty vocabulary matches nothing`() {
        assertNull(PhraseMatcher(emptyList(), acceptDistance = 10.0).match(heard(wake)))
    }

    @Test
    fun `several voices for one phrase do not compete with each other`() {
        // Extra templates for the same phrase must not look like a rival phrase, or the margin
        // check would reject exactly the utterances the extra coverage was added to catch.
        val withVoices = templates + templateOf("hey alf", wake, speed = 1.4, source = "tts-2")
        val matcher = PhraseMatcher(withVoices, acceptDistance = 10.0, minMargin = 0.05)

        val match = assertNotNull(matcher.match(heard(wake, speed = 1.2)))
        assertEquals("hey alf", match.phrase)
    }

    @Test
    fun `an ambiguous capture is refused rather than guessed at`() {
        val matcher = PhraseMatcher(templates, acceptDistance = 100.0, minMargin = 50.0)

        assertNull(matcher.match(heard(wake)), "an impossible margin should reject everything")
    }

    @Test
    fun `ranking exposes one distance per phrase for calibration`() {
        val withVoices = templates + templateOf("hey alf", wake, speed = 1.4, source = "tts-2")
        val ranked = PhraseMatcher(withVoices, acceptDistance = 10.0).rank(heard(wake))

        assertEquals(2, ranked.size, "one entry per phrase, not per template")
        assertEquals("hey alf", ranked.first().first)
        assertTrue(ranked[0].second <= ranked[1].second)
    }

    @Test
    fun `match carries the skill and parameters through`() {
        val matcher = PhraseMatcher(
            listOf(
                PhraseTemplate(
                    phrase = "alarmı yediye kur",
                    skillId = "set_alarm",
                    params = mapOf("hour" to "7"),
                    features = extractor.extract(TestSignals.phrase(*alarm.toTypedArray())),
                ),
            ),
            acceptDistance = 10.0,
        )

        val match = assertNotNull(matcher.match(heard(alarm, speed = 1.1)))
        assertEquals("set_alarm", match.skillId)
        assertEquals(mapOf("hour" to "7"), match.params)
    }
}
