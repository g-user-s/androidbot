package dev.alf.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative radix-2 Cooley-Tukey FFT.
 *
 * Hand written rather than pulled in as a dependency: this runs on every 25 ms of audio for as
 * long as the device is awake, so it wants to stay allocation free and obvious. Sizes are always
 * powers of two chosen by [MfccExtractor].
 */
internal object Fft {

    /** In place complex FFT. [re] and [im] must be the same power-of-two length. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "real and imaginary parts must match in length" }
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two, was $n" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            var block = 0
            while (block < n) {
                var twiddleRe = 1.0
                var twiddleIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = block + k
                    val b = a + half
                    val vRe = re[b] * twiddleRe - im[b] * twiddleIm
                    val vIm = re[b] * twiddleIm + im[b] * twiddleRe
                    re[b] = re[a] - vRe
                    im[b] = im[a] - vIm
                    re[a] += vRe
                    im[a] += vIm
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                }
                block += len
            }
            len = len shl 1
        }
    }

    /**
     * Power spectrum of a real signal, bins `0..size/2` inclusive.
     * [frame] is copied into a zero padded buffer of [size] samples.
     */
    fun powerSpectrum(frame: DoubleArray, size: Int, out: DoubleArray) {
        require(out.size == size / 2 + 1) { "output must hold ${size / 2 + 1} bins" }
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        frame.copyInto(re, endIndex = minOf(frame.size, size))
        transform(re, im)
        for (bin in out.indices) {
            out[bin] = re[bin] * re[bin] + im[bin] * im[bin]
        }
    }
}
