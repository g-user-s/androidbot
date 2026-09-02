package dev.alf.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationListenerTest {

    private val extractor = MfccExtractor()

    private val wakeTones = listOf(400.0 to 150, 1200.0 to 150, 700.0 to 150)
    private val commandTones = listOf(2200.0 to 150, 600.0 to 150, 1800.0 to 150)
    private val strangerTones = listOf(3000.0 to 200, 350.0 to 250)

    private fun say(tones: List<Pair<Double, Int>>, speed: Double = 1.0) =
        extractor.extract(TestSignals.phrase(*tones.toTypedArray(), speed = speed))

    private fun listener(windowMs: Long = 6_000) = ConversationListener(
        wakeMatcher = PhraseMatcher(
            listOf(PhraseTemplate("hey alf", "__wake__", features = say(wakeTones))),
            acceptDistance = 1.0,
        ),
        commandMatcher = PhraseMatcher(
            listOf(PhraseTemplate("saat kaç", "time_now", features = say(commandTones))),
            acceptDistance = 1.0,
        ),
        commandWindowMs = windowMs,
    )

    @Test
    fun `a command spoken while asleep is ignored`() {
        // The whole point of the wake phrase: the command vocabulary is unreachable until it lands.
        val listener = listener()

        assertNull(listener.onUtterance(say(commandTones), nowMs = 0))
        assertFalse(listener.isAwake)
    }

    @Test
    fun `wake then command`() {
        val listener = listener()

        assertIs<ListenerEvent.Woke>(listener.onUtterance(say(wakeTones), nowMs = 0))
        assertTrue(listener.isAwake)

        val event = assertIs<ListenerEvent.Command>(listener.onUtterance(say(commandTones, speed = 1.2), nowMs = 1_000))
        assertEquals("time_now", event.match.skillId)
        assertFalse(listener.isAwake, "the window closes once a command lands")
    }

    @Test
    fun `room noise while asleep wakes nothing`() {
        val listener = listener()

        assertNull(listener.onUtterance(say(strangerTones), nowMs = 0))
    }

    @Test
    fun `something unrecognised inside the window is reported`() {
        val listener = listener()
        listener.onUtterance(say(wakeTones), nowMs = 0)

        assertIs<ListenerEvent.NotUnderstood>(listener.onUtterance(say(strangerTones), nowMs = 500))
        assertFalse(listener.isAwake)
    }

    @Test
    fun `the window closes on its own`() {
        val listener = listener(windowMs = 6_000)
        listener.onUtterance(say(wakeTones), nowMs = 0)

        assertNull(listener.onTick(nowMs = 5_999))
        assertIs<ListenerEvent.TimedOut>(listener.onTick(nowMs = 6_000))
        assertNull(listener.onTick(nowMs = 6_001), "the timeout is reported once, not forever")
        assertFalse(listener.isAwake)
    }

    @Test
    fun `a command arriving after the window is not run`() {
        val listener = listener(windowMs = 6_000)
        listener.onUtterance(say(wakeTones), nowMs = 0)

        assertIs<ListenerEvent.TimedOut>(listener.onUtterance(say(commandTones), nowMs = 7_000))
        assertFalse(listener.isAwake)
    }

    @Test
    fun `saying the wake phrase again extends the window`() {
        val listener = listener(windowMs = 6_000)
        listener.onUtterance(say(wakeTones), nowMs = 0)

        assertIs<ListenerEvent.Woke>(listener.onUtterance(say(wakeTones, speed = 1.1), nowMs = 3_000))
        assertNull(listener.onTick(nowMs = 8_000), "the deadline should have moved to 9000")
        assertIs<ListenerEvent.TimedOut>(listener.onTick(nowMs = 9_000))
    }

    @Test
    fun `reset puts the listener back to sleep`() {
        val listener = listener()
        listener.onUtterance(say(wakeTones), nowMs = 0)

        listener.reset()

        assertFalse(listener.isAwake)
        assertNull(listener.onTick(nowMs = 100_000))
    }
}
