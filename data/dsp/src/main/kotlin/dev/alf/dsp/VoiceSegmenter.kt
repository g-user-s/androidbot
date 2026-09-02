package dev.alf.dsp

import kotlin.math.log10

data class VadConfig(
    val sampleRate: Int = 16_000,
    val frameLengthMs: Int = 20,
    /** How far above the measured noise floor a frame has to be to open a segment. */
    val startMarginDb: Double = 9.0,
    /** Lower bar for staying inside a segment, so a quiet syllable does not cut a word in half. */
    val endMarginDb: Double = 4.0,
    /** Segments shorter than this are thrown away as clicks and knocks. */
    val minSpeechFrames: Int = 6,
    /** Silence this long ends a segment. */
    val hangoverFrames: Int = 25,
    /**
     * Frames kept from before the trigger. By the time energy crosses the threshold the onset of
     * the word is already past, and clipping "hey" down to "ey" is enough to break matching.
     */
    val preRollFrames: Int = 10,
    /** Hard cap, so a vacuum cleaner cannot grow a segment without end. */
    val maxSegmentFrames: Int = 250,
    /** How fast the noise floor follows the room while it is quiet. */
    val noiseAdaptation: Double = 0.05,
) {
    val frameLength: Int = sampleRate * frameLengthMs / 1000
}

/**
 * Splits a continuous microphone stream into utterances, and keeps the matcher asleep in between.
 *
 * On this hardware the assistant listens for as long as it is powered, so the cheap check has to
 * come first: frame energy against an adapting noise floor costs a few multiplications, while
 * feature extraction and matching cost orders of magnitude more. Everything downstream only runs
 * on what this class hands out.
 */
class VoiceSegmenter(private val config: VadConfig = VadConfig()) {

    private val preRoll = ArrayDeque<FloatArray>(config.preRollFrames + 1)
    private val segment = mutableListOf<FloatArray>()

    private var noiseFloorDb = Double.NaN
    private var inSpeech = false
    private var speechFrames = 0
    private var silenceFrames = 0

    /** True while a segment is open; useful for driving a listening indicator. */
    val isCapturing: Boolean get() = inSpeech

    /**
     * Feeds one frame of exactly [VadConfig.frameLength] samples.
     * Returns the finished utterance when this frame completed one, otherwise null.
     */
    fun accept(frame: FloatArray): FloatArray? {
        require(frame.size == config.frameLength) {
            "expected ${config.frameLength} samples per frame, got ${frame.size}"
        }

        val levelDb = levelDb(frame)
        if (noiseFloorDb.isNaN()) noiseFloorDb = levelDb

        if (!inSpeech) {
            if (levelDb > noiseFloorDb + config.startMarginDb) {
                inSpeech = true
                speechFrames = 0
                silenceFrames = 0
                segment.clear()
                // Only the frames before this one; the trigger frame is appended below like any other.
                segment.addAll(preRoll)
                preRoll.clear()
            } else {
                rememberForPreRoll(frame)
                // Only adapt while quiet, so speech never drags the floor up behind it.
                noiseFloorDb += config.noiseAdaptation * (levelDb - noiseFloorDb)
                return null
            }
        }

        segment += frame
        if (levelDb > noiseFloorDb + config.endMarginDb) {
            speechFrames++
            silenceFrames = 0
        } else {
            silenceFrames++
        }

        val ended = silenceFrames >= config.hangoverFrames
        val overran = segment.size >= config.maxSegmentFrames
        if (!ended && !overran) return null

        // Read the counters before reset() clears them.
        val captured = segment.toList()
        val voicedFrames = speechFrames
        val keep = captured.size - (if (ended) config.hangoverFrames else 0)
        reset()
        return if (voicedFrames >= config.minSpeechFrames) flatten(captured, keep) else null
    }

    /** Ends any open segment, for when capture stops. */
    fun flush(): FloatArray? {
        if (!inSpeech) return null
        val captured = segment.toList()
        val enough = speechFrames >= config.minSpeechFrames
        reset()
        return if (enough) flatten(captured, captured.size) else null
    }

    fun reset() {
        inSpeech = false
        speechFrames = 0
        silenceFrames = 0
        segment.clear()
        preRoll.clear()
    }

    private fun rememberForPreRoll(frame: FloatArray) {
        preRoll.addLast(frame)
        while (preRoll.size > config.preRollFrames) preRoll.removeFirst()
    }

    private fun flatten(frames: List<FloatArray>, keepFrames: Int): FloatArray {
        val kept = frames.take(keepFrames.coerceIn(1, frames.size))
        val out = FloatArray(kept.sumOf { it.size })
        var at = 0
        for (frame in kept) {
            frame.copyInto(out, at)
            at += frame.size
        }
        return out
    }

    private fun levelDb(frame: FloatArray): Double {
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        return 10.0 * log10(sum / frame.size + 1e-12)
    }
}
