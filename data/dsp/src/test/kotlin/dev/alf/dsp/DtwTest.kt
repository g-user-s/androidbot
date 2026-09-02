package dev.alf.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DtwTest {

    private val extractor = MfccExtractor()

    private fun sequenceOf(vararg rows: DoubleArray) = FeatureSequence(rows.toList())

    @Test
    fun `a sequence matches itself exactly`() {
        val sequence = sequenceOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0))
        assertEquals(0.0, Dtw.distance(sequence, sequence))
    }

    @Test
    fun `empty input never matches`() {
        val sequence = sequenceOf(doubleArrayOf(1.0))
        assertEquals(Double.POSITIVE_INFINITY, Dtw.distance(sequence, FeatureSequence(emptyList())))
        assertEquals(Double.POSITIVE_INFINITY, Dtw.distance(FeatureSequence(emptyList()), sequence))
    }

    @Test
    fun `mismatched dimensions are a programming error`() {
        assertFailsWith<IllegalArgumentException> {
            Dtw.distance(sequenceOf(doubleArrayOf(1.0)), sequenceOf(doubleArrayOf(1.0, 2.0)))
        }
    }

    @Test
    fun `the same phrase spoken faster still matches`() {
        // This is the property the whole matcher rests on. Same content, 1.6x the speed: the
        // distance has to stay far below what a different phrase scores.
        val normal = extractor.extract(TestSignals.phrase(400.0 to 150, 1200.0 to 150, 600.0 to 150))
        val quick = extractor.extract(
            TestSignals.phrase(400.0 to 150, 1200.0 to 150, 600.0 to 150, speed = 1.6),
        )
        val different = extractor.extract(TestSignals.phrase(2200.0 to 150, 500.0 to 150, 1800.0 to 150))

        val sameContent = Dtw.distance(normal, quick)
        val otherContent = Dtw.distance(normal, different)

        assertTrue(
            sameContent < otherContent / 2,
            "time warped copy scored $sameContent, a different phrase scored $otherContent",
        )
    }

    @Test
    fun `the band still admits sequences of very different length`() {
        val short = extractor.extract(TestSignals.tone(500.0, 120))
        val long = extractor.extract(TestSignals.tone(500.0, 600))

        assertTrue(Dtw.distance(short, long).isFinite(), "band blocked every path")
    }

    @Test
    fun `distance does not depend on argument order`() {
        val a = extractor.extract(TestSignals.phrase(400.0 to 120, 900.0 to 120))
        val b = extractor.extract(TestSignals.phrase(400.0 to 120, 900.0 to 200))

        val forward = Dtw.distance(a, b)
        val backward = Dtw.distance(b, a)
        assertTrue(kotlin.math.abs(forward - backward) < 1e-6, "$forward vs $backward")
    }
}
