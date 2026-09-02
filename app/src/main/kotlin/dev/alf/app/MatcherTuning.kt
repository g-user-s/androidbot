package dev.alf.app

/**
 * Decision thresholds for the phrase matcher.
 *
 * These are starting points, not measured values. Normalised DTW distance has no meaning in the
 * abstract — it depends on the microphone, the room and the voices the templates were built from
 * — so they have to be calibrated on the device before the numbers here mean anything. The
 * procedure is in docs/PLAN.md; `PhraseMatcher.rank()` exists to support it, and
 * [LOG_RANKINGS] turns on the logging that feeds it.
 */
object MatcherTuning {

    /** Uncalibrated. Too low and alf never wakes; too high and it answers the television. */
    const val WAKE_ACCEPT_DISTANCE = 3.0

    /** Commands can be looser: something has already said the wake word. */
    const val COMMAND_ACCEPT_DISTANCE = 3.5

    /**
     * How far the winning command must beat the closest different one. Meaningless for the wake
     * vocabulary, which holds a single phrase and so has no runner up.
     */
    const val COMMAND_MIN_MARGIN = 0.15

    /** How long alf stays awake after answering, waiting for a command. */
    const val COMMAND_WINDOW_MS = 6_000L

    /** Writes the top few distances for every capture to logcat, for threshold calibration. */
    const val LOG_RANKINGS = true

    const val RANKINGS_LOGGED = 3
}
