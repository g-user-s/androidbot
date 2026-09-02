package dev.alf.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceSegmenterTest {

    private val config = VadConfig()

    private fun run(signal: FloatArray, segmenter: VoiceSegmenter = VoiceSegmenter(config)): List<FloatArray> {
        val captured = mutableListOf<FloatArray>()
        TestSignals.frames(signal, config.frameLength).forEach { frame ->
            segmenter.accept(frame)?.let { captured += it }
        }
        segmenter.flush()?.let { captured += it }
        return captured
    }

    @Test
    fun `a quiet room produces nothing`() {
        assertTrue(run(TestSignals.quiet(4_000)).isEmpty())
    }

    @Test
    fun `one burst becomes one segment`() {
        val signal = TestSignals.concat(
            TestSignals.quiet(1_000),
            TestSignals.tone(500.0, 400),
            TestSignals.quiet(1_500),
        )

        val segments = run(signal)
        assertEquals(1, segments.size)
    }

    @Test
    fun `two bursts separated by silence become two segments`() {
        val signal = TestSignals.concat(
            TestSignals.quiet(800),
            TestSignals.tone(500.0, 300),
            TestSignals.quiet(1_200),
            TestSignals.tone(900.0, 300),
            TestSignals.quiet(1_200),
        )

        assertEquals(2, run(signal).size)
    }

    @Test
    fun `the onset survives the trigger delay`() {
        // Energy only crosses the threshold once the word is under way, so without a pre-roll the
        // captured segment starts mid word and matching falls apart.
        val speechMs = 400
        val signal = TestSignals.concat(
            TestSignals.quiet(1_000),
            TestSignals.tone(500.0, speechMs),
            TestSignals.quiet(1_500),
        )

        val segment = assertNotNull(run(signal).singleOrNull())
        val speechSamples = TestSignals.SAMPLE_RATE * speechMs / 1000
        assertTrue(
            segment.size > speechSamples,
            "segment held ${segment.size} samples, no room for pre-roll before $speechSamples",
        )
    }

    @Test
    fun `a click is too short to be a command`() {
        val signal = TestSignals.concat(
            TestSignals.quiet(1_000),
            TestSignals.tone(500.0, 40),
            TestSignals.quiet(1_500),
        )

        assertTrue(run(signal).isEmpty())
    }

    @Test
    fun `continuous noise cannot grow a segment without end`() {
        val signal = TestSignals.concat(TestSignals.quiet(500), TestSignals.tone(400.0, 20_000))

        val segments = run(signal)
        assertTrue(segments.isNotEmpty())
        val cap = config.maxSegmentFrames * config.frameLength
        assertTrue(segments.all { it.size <= cap }, "a segment ran past the ${cap} sample cap")
    }

    @Test
    fun `capture state is exposed while a segment is open`() {
        val segmenter = VoiceSegmenter(config)
        val quiet = TestSignals.frames(TestSignals.quiet(500), config.frameLength)
        quiet.forEach { segmenter.accept(it) }
        assertTrue(!segmenter.isCapturing)

        TestSignals.frames(TestSignals.tone(500.0, 200), config.frameLength).forEach { segmenter.accept(it) }
        assertTrue(segmenter.isCapturing)
    }

    @Test
    fun `flush closes an open segment`() {
        val segmenter = VoiceSegmenter(config)
        TestSignals.frames(
            TestSignals.concat(TestSignals.quiet(400), TestSignals.tone(500.0, 300)),
            config.frameLength,
        ).forEach { segmenter.accept(it) }

        assertNotNull(segmenter.flush())
        assertNull(segmenter.flush())
    }

    @Test
    fun `frames must be the configured length`() {
        val segmenter = VoiceSegmenter(config)
        kotlin.test.assertFailsWith<IllegalArgumentException> { segmenter.accept(FloatArray(7)) }
    }
}
