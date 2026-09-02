package dev.alf.audio

import android.speech.tts.Voice
import dev.alf.dsp.MfccExtractor
import dev.alf.dsp.PhraseTemplate
import dev.alf.dsp.Resampler
import dev.alf.dsp.WavReader
import dev.alf.nlu.VocabularyEntry
import java.io.File

/**
 * Builds the matcher's reference set by having the device speak its own vocabulary.
 *
 * This is the step that replaces collecting a training corpus: every phrase alf can recognise
 * offline is synthesised once by every Turkish voice on the device, converted to features, and
 * stored. It runs at first start and whenever the vocabulary changes, never on the hot path.
 *
 * The rate conversion in the middle is not incidental. Engines write 22.05 or 24 kHz while the
 * microphone is opened at 16 kHz, and features extracted at two different rates are not
 * comparable at all.
 */
class TemplateSynthesizer(
    private val tts: TurkishTts,
    private val workDir: File,
    private val extractor: MfccExtractor = MfccExtractor(),
    private val targetSampleRate: Int = 16_000,
) {

    data class Progress(val done: Int, val total: Int, val phrase: String)

    /**
     * Returns one template per phrase per voice. Phrases the engine refuses to speak are skipped
     * rather than failing the whole build — a vocabulary that is mostly there still works, and
     * the caller can see which phrases are missing by comparing counts.
     */
    suspend fun build(
        entries: List<VocabularyEntry>,
        voices: List<Voice>,
        onProgress: (Progress) -> Unit = {},
    ): List<PhraseTemplate> {
        if (entries.isEmpty()) return emptyList()
        val usableVoices = voices.ifEmpty { listOf(null) }

        workDir.mkdirs()
        val scratch = File(workDir, "synth.wav")
        val templates = mutableListOf<PhraseTemplate>()

        entries.forEachIndexed { index, entry ->
            onProgress(Progress(index, entries.size, entry.phrase))
            for (voice in usableVoices) {
                scratch.delete()
                if (!tts.synthesizeToFile(entry.phrase, scratch, voice)) continue
                val features = runCatching {
                    val audio = WavReader.read(scratch)
                    extractor.extract(Resampler.resample(audio.samples, audio.sampleRate, targetSampleRate))
                }.getOrNull() ?: continue

                if (features.isEmpty) continue
                templates += PhraseTemplate(
                    phrase = entry.phrase,
                    skillId = entry.skillId,
                    params = entry.params,
                    features = features,
                    source = voice?.name ?: "default",
                )
            }
        }

        scratch.delete()
        onProgress(Progress(entries.size, entries.size, ""))
        return templates
    }
}
