package dev.alf.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Dynamic time warping between two feature sequences.
 *
 * This is the whole reason the matcher can compare a synthesised reference with a person
 * speaking: the two say the same thing at different speeds, and warping lines them up instead of
 * penalising the difference in timing. A plain frame by frame distance would call a slow "hey
 * alf" and a quick one two different phrases.
 */
object Dtw {

    /**
     * Cost of the cheapest alignment, normalised by the two lengths so sequences of different
     * durations stay comparable. Returns [Double.POSITIVE_INFINITY] if either side is empty.
     *
     * [bandRatio] is a Sakoe-Chiba band: alignments are not allowed to drift further than this
     * fraction of the longer sequence away from the diagonal. It caps the work and, more
     * usefully, stops a short burst of noise from stretching to cover a whole reference.
     */
    fun distance(a: FeatureSequence, b: FeatureSequence, bandRatio: Double = 0.25): Double {
        if (a.isEmpty || b.isEmpty) return Double.POSITIVE_INFINITY
        require(a.dimension == b.dimension) {
            "cannot compare ${a.dimension} dimensional features with ${b.dimension} dimensional ones"
        }

        val n = a.size
        val m = b.size
        // The band must at least span the length difference, or no path reaches the far corner.
        val band = max((bandRatio * max(n, m)).toInt(), abs(n - m)) + 1

        var previous = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var current = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        previous[0] = 0.0

        for (i in 1..n) {
            java.util.Arrays.fill(current, Double.POSITIVE_INFINITY)
            val centre = (i.toLong() * m / n).toInt()
            val from = max(1, centre - band)
            val to = minOf(m, centre + band)
            for (j in from..to) {
                val cost = euclidean(a.frames[i - 1], b.frames[j - 1])
                val best = minOf(previous[j], current[j - 1], previous[j - 1])
                current[j] = cost + best
            }
            val swap = previous
            previous = current
            current = swap
        }

        val total = previous[m]
        return if (total.isFinite()) total / (n + m) else Double.POSITIVE_INFINITY
    }

    private fun euclidean(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum)
    }
}
