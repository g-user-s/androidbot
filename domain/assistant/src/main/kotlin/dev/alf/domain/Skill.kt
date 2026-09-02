package dev.alf.domain

/**
 * Everything about a capability except how it runs.
 *
 * One definition feeds three consumers: the rule based resolver matches against [utterances],
 * the offline recogniser's vocabulary is built from the same phrases, and the LLM path will
 * serialise [description] and [parameters] into a tool schema. Adding a skill means adding a
 * definition here plus an executor on the Android side, nothing else.
 */
data class SkillDefinition(
    val id: String,
    val description: String,
    val parameters: List<ParamSpec> = emptyList(),
    val utterances: List<UtterancePattern> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "skill id must not be blank" }
        val declared = utterances.flatMap { pattern -> pattern.slots.map { it.param } }.toSet()
        val known = parameters.map { it.name }.toSet()
        require(known.containsAll(declared)) {
            "skill '$id' binds slots to unknown parameters: ${declared - known}"
        }
    }

    /**
     * Whether at least one phrasing survives without a network. A skill taking free text — a
     * note body, a search query — has nothing the offline matcher can compare against, so it
     * simply goes quiet until the assistant is back online.
     */
    val availableOffline: Boolean = utterances.any { it.enumerable }
}

data class ParamSpec(
    val name: String,
    val description: String,
    val required: Boolean = true,
)

/** A capability the assistant can run. */
interface Skill {
    val definition: SkillDefinition

    suspend fun execute(params: Map<String, String>): SkillResult
}

sealed interface SkillResult {
    /** Success, with something to say back. */
    data class Spoken(val text: String) : SkillResult

    /** Success, nothing to say — the effect speaks for itself. */
    data object Silent : SkillResult

    /** Failure. [spoken] is for the user, [reason] for the log. */
    data class Failed(val reason: String, val spoken: String) : SkillResult
}
