package dev.alf.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Feature vectors, one per analysis frame, in the order they were spoken. */
class FeatureSequence(val frames: List<DoubleArray>) {
    val size: Int get() = frames.size
    val dimension: Int get() = frames.firstOrNull()?.size ?: 0
    val isEmpty: Boolean get() = frames.isEmpty()
}

data class MfccConfig(
    val sampleRate: Int = 16_000,
    val frameLengthMs: Int = 25,
    val frameShiftMs: Int = 10,
    val fftSize: Int = 512,
    val melFilters: Int = 26,
    /** How many cepstral coefficients to keep, counting from c0. */
    val coefficients: Int = 13,
    val lowFrequencyHz: Double = 100.0,
    val highFrequencyHz: Double = 7_800.0,
    val preEmphasis: Double = 0.97,
) {
    val frameLength: Int = sampleRate * frameLengthMs / 1000
    val frameShift: Int = sampleRate * frameShiftMs / 1000

    init {
        require(frameLength <= fftSize) { "fftSize must cover a $frameLengthMs ms frame" }
        require(coefficients in 1..melFilters) { "coefficients must fit within the filter bank" }
        require(highFrequencyHz <= sampleRate / 2.0) { "highFrequencyHz exceeds Nyquist" }
    }
}

/**
 * Turns raw samples into MFCCs, then normalises them per utterance.
 *
 * The normalisation is what makes matching against synthesised references workable at all: a TTS
 * voice and a person in a room differ enormously in overall level and spectral tilt, and
 * subtracting the mean of each coefficient removes exactly that kind of fixed offset. Without it
 * the distance between a reference and a genuine utterance is dominated by the recording channel
 * rather than by what was said.
 */
class MfccExtractor(private val config: MfccConfig = MfccConfig()) {

    private val window: DoubleArray = DoubleArray(config.frameLength) { i ->
        // Hamming
        0.54 - 0.46 * cos(2.0 * PI * i / (config.frameLength - 1))
    }

    private val filterBank: MelFilterBank = MelFilterBank(
        sampleRate = config.sampleRate,
        fftSize = config.fftSize,
        filterCount = config.melFilters,
        lowFrequencyHz = config.lowFrequencyHz,
        highFrequencyHz = config.highFrequencyHz,
    )

    /** DCT-II basis, precomputed because the sizes never change. */
    private val dct: Array<DoubleArray> = Array(config.coefficients) { k ->
        DoubleArray(config.melFilters) { n ->
            cos(PI * k * (n + 0.5) / config.melFilters)
        }
    }

    fun extract(samples: FloatArray, normalise: Boolean = true): FeatureSequence {
        if (samples.size < config.frameLength) return FeatureSequence(emptyList())

        val emphasised = DoubleArray(samples.size)
        emphasised[0] = samples[0].toDouble()
        for (i in 1 until samples.size) {
            emphasised[i] = samples[i] - config.preEmphasis * samples[i - 1]
        }

        val frames = mutableListOf<DoubleArray>()
        val spectrum = DoubleArray(config.fftSize / 2 + 1)
        val windowed = DoubleArray(config.frameLength)
        val energies = DoubleArray(config.melFilters)

        var offset = 0
        while (offset + config.frameLength <= emphasised.size) {
            for (i in 0 until config.frameLength) {
                windowed[i] = emphasised[offset + i] * window[i]
            }
            Fft.powerSpectrum(windowed, config.fftSize, spectrum)
            filterBank.apply(spectrum, energies)

            val cepstrum = DoubleArray(config.coefficients)
            for (k in 0 until config.coefficients) {
                var sum = 0.0
                val basis = dct[k]
                for (n in 0 until config.melFilters) {
                    sum += ln(energies[n] + FLOOR) * basis[n]
                }
                cepstrum[k] = sum
            }
            frames += cepstrum
            offset += config.frameShift
        }

        val sequence = FeatureSequence(frames)
        return if (normalise) cepstralMeanVarianceNormalise(sequence) else sequence
    }

    companion object {
        private const val FLOOR = 1e-10

        /**
         * Zero mean, unit variance per coefficient across the whole utterance. Applied after the
         * fact rather than incrementally because a wake word or a command is short and complete
         * by the time it is matched.
         */
        fun cepstralMeanVarianceNormalise(sequence: FeatureSequence): FeatureSequence {
            if (sequence.isEmpty) return sequence
            val dimension = sequence.dimension
            val count = sequence.size

            val means = DoubleArray(dimension)
            for (frame in sequence.frames) {
                for (d in 0 until dimension) means[d] += frame[d]
            }
            for (d in 0 until dimension) means[d] /= count

            val deviations = DoubleArray(dimension)
            for (frame in sequence.frames) {
                for (d in 0 until dimension) {
                    val delta = frame[d] - means[d]
                    deviations[d] += delta * delta
                }
            }
            for (d in 0 until dimension) {
                deviations[d] = sqrt(deviations[d] / count).takeIf { it > 1e-8 } ?: 1.0
            }

            return FeatureSequence(
                sequence.frames.map { frame ->
                    DoubleArray(dimension) { d -> (frame[d] - means[d]) / deviations[d] }
                },
            )
        }
    }
}

/** Triangular filters spaced evenly on the mel scale. */
internal class MelFilterBank(
    sampleRate: Int,
    fftSize: Int,
    private val filterCount: Int,
    lowFrequencyHz: Double,
    highFrequencyHz: Double,
) {
    private val binCount = fftSize / 2 + 1

    /** For each filter: the first bin it touches, and its weights from there on. */
    private val startBins = IntArray(filterCount)
    private val weights: Array<DoubleArray>

    init {
        val lowMel = toMel(lowFrequencyHz)
        val highMel = toMel(highFrequencyHz)
        val points = DoubleArray(filterCount + 2) { i ->
            toHertz(lowMel + (highMel - lowMel) * i / (filterCount + 1))
        }
        val binOf = { hz: Double -> (hz * fftSize / sampleRate).toInt().coerceIn(0, binCount - 1) }

        weights = Array(filterCount) { filter ->
            val left = binOf(points[filter])
            val centre = binOf(points[filter + 1])
            val right = binOf(points[filter + 2])
            startBins[filter] = left
            val span = (right - left + 1).coerceAtLeast(1)
            DoubleArray(span) { offset ->
                val bin = left + offset
                when {
                    bin < centre && centre > left -> (bin - left).toDouble() / (centre - left)
                    bin > centre && right > centre -> (right - bin).toDouble() / (right - centre)
                    bin == centre -> 1.0
                    else -> 0.0
                }
            }
        }
    }

    fun apply(powerSpectrum: DoubleArray, out: DoubleArray) {
        require(out.size == filterCount)
        for (filter in 0 until filterCount) {
            var sum = 0.0
            val start = startBins[filter]
            val w = weights[filter]
            for (offset in w.indices) {
                val bin = start + offset
                if (bin < powerSpectrum.size) sum += powerSpectrum[bin] * w[offset]
            }
            out[filter] = sum
        }
    }

    companion object {
        fun toMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun toHertz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    }
}
