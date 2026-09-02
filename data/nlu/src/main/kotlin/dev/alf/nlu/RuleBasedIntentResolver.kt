package dev.alf.nlu

import dev.alf.domain.Intent
import dev.alf.domain.IntentResolver
import dev.alf.domain.SkillDefinition
import dev.alf.domain.SlotKind
import dev.alf.domain.UtterancePattern
import kotlin.math.max

/**
 * Matches a transcript against the catalog with no model and no network.
 *
 * This is both the offline brain and the fallback for when the LLM is unreachable, so it is
 * deliberately conservative: it would rather return null and have alf say it did not understand
 * than fire the wrong skill. Matching runs in three passes, most trustworthy first —
 * exact phrase, then templates holding free text, then fuzzy.
 */
class RuleBasedIntentResolver(
    definitions: List<SkillDefinition>,
    /**
     * How close a fuzzy match has to be, as 1 - (edit distance / length). 0.82 tolerates a
     * dropped suffix or a swapped letter on a short command without reaching across to a
     * different one; worth re-tuning once real recogniser output is available.
     */
    private val minSimilarity: Double = 0.82,
) : IntentResolver {

    private class Phrase(val skillId: String, val text: String, val params: Map<String, String>)

    private class FreeTextPattern(
        val skillId: String,
        val regex: Regex,
        val paramsInOrder: List<String>,
        val fixedParams: Map<String, String>,
    )

    private val phrases: List<Phrase> = definitions.flatMap { definition ->
        definition.utterances.flatMap { it.expand() }.map {
            Phrase(definition.id, TextNormalizer.normalize(it.phrase), it.params)
        }
    }

    private val freeTextPatterns: List<FreeTextPattern> = definitions.flatMap { definition ->
        definition.utterances.filterNot { it.enumerable }.mapNotNull { compile(definition.id, it) }
    }

    override suspend fun resolve(text: String): Intent? {
        val query = TextNormalizer.normalize(text)
        if (query.isEmpty()) return null

        phrases.firstOrNull { it.text == query }?.let {
            return Intent(it.skillId, it.params, confidence = 1f, transcript = text)
        }

        matchFreeText(query, text)?.let { return it }

        var best: Phrase? = null
        var bestScore = 0.0
        for (phrase in phrases) {
            val score = similarity(query, phrase.text)
            if (score > bestScore) {
                bestScore = score
                best = phrase
            }
        }
        return best
            ?.takeIf { bestScore >= minSimilarity }
            ?.let { Intent(it.skillId, it.params, confidence = bestScore.toFloat(), transcript = text) }
    }

    private fun matchFreeText(query: String, original: String): Intent? {
        for (pattern in freeTextPatterns) {
            val match = pattern.regex.matchEntire(query) ?: continue
            val captured = pattern.paramsInOrder.withIndex().associate { (index, param) ->
                param to match.groupValues[index + 1].trim()
            }
            if (captured.values.any { it.isEmpty() }) continue
            return Intent(
                skillId = pattern.skillId,
                params = pattern.fixedParams + captured,
                confidence = 0.9f,
                transcript = original,
            )
        }
        return null
    }

    /**
     * Turns `"internette {sorgu} ara"` into `^internette\s*(.+?)\s*ara$`. Enumerated slots that
     * share a template with free text become an alternation of their spoken forms; a slot whose
     * values only arrive at runtime cannot be compiled, so the pattern is skipped.
     */
    private fun compile(skillId: String, pattern: UtterancePattern): FreeTextPattern? {
        val slotsByName = pattern.slots.associateBy { it.name }
        val regex = StringBuilder("^")
        val paramsInOrder = mutableListOf<String>()
        var cursor = 0

        for (match in SLOT_RE.findAll(pattern.template)) {
            val slot = slotsByName[match.groupValues[1]] ?: return null
            appendLiteral(regex, pattern.template.substring(cursor, match.range.first))
            when (slot.kind) {
                SlotKind.FREE_TEXT -> {
                    regex.append("(.+?)")
                    paramsInOrder += slot.param
                }
                SlotKind.ENUMERATED -> {
                    regex.append("(?:")
                    regex.append(slot.values.joinToString("|") { Regex.escape(TextNormalizer.normalize(it.spoken)) })
                    regex.append(")")
                }
                SlotKind.RUNTIME -> return null
            }
            cursor = match.range.last + 1
        }
        appendLiteral(regex, pattern.template.substring(cursor))
        regex.append("$")

        if (paramsInOrder.isEmpty()) return null
        return FreeTextPattern(skillId, Regex(regex.toString()), paramsInOrder, emptyMap())
    }

    private fun appendLiteral(regex: StringBuilder, raw: String) {
        val literal = TextNormalizer.normalize(raw)
        if (literal.isEmpty()) return
        if (regex.length > 1) regex.append("\\s*")
        regex.append(Regex.escape(literal))
        regex.append("\\s*")
    }

    private fun similarity(a: String, b: String): Double {
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / longest
    }

    /** Levenshtein over two rolling rows; the strings here are single short commands. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private companion object {
        val SLOT_RE = Regex("""\{(\w+)}""")
    }
}
