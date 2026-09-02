package dev.alf.dsp

/** What the listener decided about a captured utterance. */
sealed interface ListenerEvent {
    /** The wake phrase was heard. Play a wake response and start the command window. */
    data object Woke : ListenerEvent

    /** A command was recognised inside the command window. */
    data class Command(val match: PhraseMatch) : ListenerEvent

    /** Something was said inside the window but it matched nothing. */
    data object NotUnderstood : ListenerEvent

    /** The window closed with nothing said. Go quiet without announcing it. */
    data object TimedOut : ListenerEvent
}

/**
 * The two stage listening behaviour: asleep until the wake phrase, awake briefly afterwards.
 *
 * Splitting it in two is what keeps false accepts survivable. While asleep, only the wake phrase
 * is in the vocabulary, so the matcher has one thing to be wrong about rather than sixty; the
 * full command vocabulary only becomes reachable once someone has already said "hey alf". It
 * also keeps the expensive comparison small in the state the device spends nearly all its time in.
 *
 * Time is passed in rather than read, so the window can be tested without waiting for it.
 */
class ConversationListener(
    private val wakeMatcher: PhraseMatcher,
    private val commandMatcher: PhraseMatcher,
    private val commandWindowMs: Long = 6_000,
) {
    private var awakeUntilMs: Long? = null

    val isAwake: Boolean get() = awakeUntilMs != null

    /** Feeds one captured utterance. Returns what should happen, or null if nothing should. */
    fun onUtterance(features: FeatureSequence, nowMs: Long): ListenerEvent? {
        expireIfDue(nowMs)?.let { return it }

        if (!isAwake) {
            return if (wakeMatcher.match(features) != null) {
                awakeUntilMs = nowMs + commandWindowMs
                ListenerEvent.Woke
            } else {
                null
            }
        }

        commandMatcher.match(features)?.let { match ->
            awakeUntilMs = null
            return ListenerEvent.Command(match)
        }

        // Saying the wake phrase again restarts the window rather than counting as a failure.
        if (wakeMatcher.match(features) != null) {
            awakeUntilMs = nowMs + commandWindowMs
            return ListenerEvent.Woke
        }

        awakeUntilMs = null
        return ListenerEvent.NotUnderstood
    }

    /**
     * Call while no one is speaking so the window can close on its own.
     * Returns [ListenerEvent.TimedOut] exactly once per expiry.
     */
    fun onTick(nowMs: Long): ListenerEvent? = expireIfDue(nowMs)

    fun reset() {
        awakeUntilMs = null
    }

    private fun expireIfDue(nowMs: Long): ListenerEvent? {
        val deadline = awakeUntilMs ?: return null
        if (nowMs < deadline) return null
        awakeUntilMs = null
        return ListenerEvent.TimedOut
    }
}
