package dev.alf.dsp

/**
 * A reference recording of one phrase.
 *
 * [source] names where it came from — a particular TTS voice, or a person during enrolment — so
 * that a template which turns out to mislead can be identified and dropped without regenerating
 * the rest.
 */
data class PhraseTemplate(
    val phrase: String,
    val skillId: String,
    val params: Map<String, String> = emptyMap(),
    val features: FeatureSequence,
    val source: String = "",
)

data class PhraseMatch(
    val phrase: String,
    val skillId: String,
    val params: Map<String, String>,
    val distance: Double,
    /** Distance to the closest *different* phrase, minus [distance]. Higher is less ambiguous. */
    val margin: Double,
)

/**
 * Picks the phrase a captured utterance is closest to, or refuses to pick one.
 *
 * Refusing matters more than choosing here. The wake matcher runs against every noise in the
 * room, so an over eager threshold means alf answers the television; the two gates below are
 * both about making that harder. Absolute distance rejects things that resemble nothing in the
 * vocabulary, and the margin rejects things that resemble two phrases equally well — which is
 * how a genuinely ambiguous sound gets turned into a confident wrong answer.
 */
class PhraseMatcher(
    private val templates: List<PhraseTemplate>,
    /** Upper bound on normalised DTW distance. Calibrate on device; see docs/PLAN.md. */
    private val acceptDistance: Double,
    /** How far ahead of the runner up the winner has to be. Zero disables the check. */
    private val minMargin: Double = 0.0,
    private val bandRatio: Double = 0.25,
) {
    init {
        require(acceptDistance > 0) { "acceptDistance must be positive" }
    }

    fun match(features: FeatureSequence): PhraseMatch? {
        if (features.isEmpty || templates.isEmpty()) return null

        val distances = DoubleArray(templates.size) {
            Dtw.distance(features, templates[it].features, bandRatio)
        }

        var bestIndex = 0
        for (i in distances.indices) {
            if (distances[i] < distances[bestIndex]) bestIndex = i
        }
        val winner = templates[bestIndex]
        val bestDistance = distances[bestIndex]
        if (!bestDistance.isFinite() || bestDistance > acceptDistance) return null

        var runnerUp = Double.POSITIVE_INFINITY
        for (i in distances.indices) {
            if (templates[i].phrase != winner.phrase && distances[i] < runnerUp) runnerUp = distances[i]
        }

        // Infinite when the vocabulary holds a single phrase: there is nothing to confuse it with.
        val margin = runnerUp - bestDistance
        if (minMargin > 0.0 && margin < minMargin) return null

        return PhraseMatch(winner.phrase, winner.skillId, winner.params, bestDistance, margin)
    }

    /**
     * Every phrase with its best distance, closest first. Not used in the hot path — this is for
     * calibrating [acceptDistance] and [minMargin] against real recordings on the device.
     */
    fun rank(features: FeatureSequence): List<Pair<String, Double>> =
        templates
            .groupBy { it.phrase }
            .map { (phrase, group) -> phrase to group.minOf { Dtw.distance(features, it.features, bandRatio) } }
            .sortedBy { it.second }
}
