package dev.alf.voicegen

import dev.alf.dsp.MfccExtractor
import dev.alf.dsp.PhraseTemplate
import dev.alf.nlu.VocabularyEntry

/** Anything that can speak a phrase as raw 16 kHz PCM. Lets the builder be tested offline. */
fun interface SpeechSource {
    fun synthesize(text: String, voiceId: String): ByteArray
}

/**
 * Turns the offline vocabulary into the reference set the on-device matcher compares against.
 *
 * Every phrase is spoken by every configured voice, because each voice is another example of the
 * same words for the matcher to be close to — the cheapest defence there is against a reference
 * that sounds nothing like the person in the room. A voice that fails on one phrase is reported
 * and skipped: a vocabulary missing one phrasing still works, a run that aborts halfway produces
 * nothing.
 */
class TemplateBuilder(
    private val speech: SpeechSource,
    private val extractor: MfccExtractor = MfccExtractor(),
) {
    data class Failure(val phrase: String, val voiceId: String, val reason: String)

    data class Result(val templates: List<PhraseTemplate>, val failures: List<Failure>)

    fun build(
        entries: List<VocabularyEntry>,
        voiceIds: List<String>,
        onProgress: (done: Int, total: Int, phrase: String) -> Unit = { _, _, _ -> },
    ): Result {
        require(voiceIds.isNotEmpty()) { "at least one voice is needed" }

        val templates = mutableListOf<PhraseTemplate>()
        val failures = mutableListOf<Failure>()

        entries.forEachIndexed { index, entry ->
            onProgress(index + 1, entries.size, entry.phrase)
            for (voiceId in voiceIds) {
                val features = runCatching {
                    val pcm = speech.synthesize(entry.phrase, voiceId)
                    extractor.extract(toSamples(pcm))
                }.getOrElse { failure ->
                    failures += Failure(entry.phrase, voiceId, failure.message ?: failure.toString())
                    null
                } ?: continue

                if (features.isEmpty) {
                    failures += Failure(entry.phrase, voiceId, "audio too short to yield features")
                    continue
                }
                templates += PhraseTemplate(
                    phrase = entry.phrase,
                    skillId = entry.skillId,
                    params = entry.params,
                    features = features,
                    source = voiceId,
                )
            }
        }

        return Result(templates, failures)
    }

    /** 16 bit little endian PCM, as the service returns it. */
    private fun toSamples(pcm: ByteArray): FloatArray {
        val count = pcm.size / 2
        return FloatArray(count) { i ->
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / Short.MAX_VALUE.toFloat()
        }
    }
}
