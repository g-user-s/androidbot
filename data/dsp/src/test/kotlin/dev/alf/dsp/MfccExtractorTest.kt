package dev.alf.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MfccExtractorTest {

    private val extractor = MfccExtractor()
    private val config = MfccConfig()

    @Test
    fun `frame count follows the window and hop`() {
        val samples = TestSignals.tone(440.0, durationMs = 500)
        val features = extractor.extract(samples, normalise = false)

        val expected = (samples.size - config.frameLength) / config.frameShift + 1
        assertEquals(expected, features.size)
        assertEquals(config.coefficients, features.dimension)
    }

    @Test
    fun `input shorter than one frame yields nothing`() {
        assertTrue(extractor.extract(FloatArray(100)).isEmpty)
    }

    @Test
    fun `extraction is deterministic`() {
        val samples = TestSignals.tone(700.0, durationMs = 200)

        val first = extractor.extract(samples)
        val second = extractor.extract(samples)

        assertEquals(first.size, second.size)
        first.frames.zip(second.frames).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    @Test
    fun `different tones give different cepstra`() {
        val low = extractor.extract(TestSignals.tone(300.0, 200), normalise = false)
        val high = extractor.extract(TestSignals.tone(2500.0, 200), normalise = false)

        val difference = low.frames[5].zip(high.frames[5]).sumOf { (a, b) -> abs(a - b) }
        assertTrue(difference > 1.0, "cepstra were nearly identical: $difference")
    }

    @Test
    fun `normalisation centres every coefficient`() {
        val features = extractor.extract(
            TestSignals.concat(TestSignals.tone(400.0, 150), TestSignals.tone(1600.0, 150)),
        )

        for (d in 0 until features.dimension) {
            val mean = features.frames.sumOf { it[d] } / features.size
            val variance = features.frames.sumOf { (it[d] - mean) * (it[d] - mean) } / features.size
            assertTrue(abs(mean) < 1e-9, "coefficient $d had mean $mean")
            assertTrue(abs(variance - 1.0) < 1e-6, "coefficient $d had variance $variance")
        }
    }

    @Test
    fun `normalisation removes a constant gain difference`() {
        // The same phrase recorded twice at different volumes has to look the same, or matching
        // a TTS reference against a person across the room is hopeless.
        val quietSignal = TestSignals.tone(500.0, 200, amplitude = 0.05)
        val loudSignal = TestSignals.tone(500.0, 200, amplitude = 0.5)

        val distance = Dtw.distance(extractor.extract(quietSignal), extractor.extract(loudSignal))
        assertTrue(distance < 0.05, "gain alone moved the features by $distance")
    }

    @Test
    fun `mel scale round trips`() {
        for (hz in listOf(0.0, 100.0, 1000.0, 4000.0, 8000.0)) {
            val back = MelFilterBank.toHertz(MelFilterBank.toMel(hz))
            assertTrue(abs(back - hz) < 1e-6, "$hz became $back")
        }
    }
}
