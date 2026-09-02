package dev.alf.nlu

import dev.alf.domain.SkillCatalog
import dev.alf.domain.SkillDefinition

/**
 * Every phrase the device must recognise with no network.
 *
 * This list is what gets synthesised into reference templates for the on device matcher, so it
 * doubles as the answer to "how big is the offline vocabulary" — worth watching, since matching
 * cost and false accepts both grow with it.
 */
data class VocabularyEntry(
    val skillId: String,
    val phrase: String,
    val params: Map<String, String>,
)

object OfflineVocabulary {

    /** The wake word is recognised by the same matcher, so it belongs in the same vocabulary. */
    const val WAKE_SKILL_ID: String = "__wake__"

    fun build(
        definitions: List<SkillDefinition> = SkillCatalog.definitions,
        includeWakeWord: Boolean = true,
    ): List<VocabularyEntry> {
        val wake = if (includeWakeWord) {
            listOf(VocabularyEntry(WAKE_SKILL_ID, TextNormalizer.normalize(SkillCatalog.WAKE_WORD), emptyMap()))
        } else {
            emptyList()
        }

        val commands = definitions.flatMap { definition ->
            definition.utterances.flatMap { it.expand() }.map { expanded ->
                VocabularyEntry(definition.id, TextNormalizer.normalize(expanded.phrase), expanded.params)
            }
        }

        return (wake + commands).distinctBy { it.phrase }
    }

    /**
     * Phrases that more than one skill claims. Any hit here is a catalog bug: offline the
     * matcher only sees the phrase, so it has no way to pick the intended skill.
     */
    fun collisions(definitions: List<SkillDefinition> = SkillCatalog.definitions): Map<String, Set<String>> =
        definitions
            .flatMap { definition ->
                definition.utterances.flatMap { it.expand() }
                    .map { TextNormalizer.normalize(it.phrase) to definition.id }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.toSet() }
            .filterValues { it.size > 1 }
}
