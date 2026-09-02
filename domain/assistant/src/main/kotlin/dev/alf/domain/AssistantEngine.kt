package dev.alf.domain

/** What the assistant says back. */
data class Reply(val text: String, val speak: Boolean = true) {
    companion object {
        val Nothing = Reply(text = "", speak = false)
    }
}

/**
 * Transcript in, reply out. Deliberately ignorant of where the transcript came from — typed,
 * recognised on device, or recognised in the cloud — and of which resolver is in play.
 */
class AssistantEngine(
    private val registry: SkillRegistry,
    private val resolver: IntentResolver,
    private val notUnderstood: String = "Bunu anlayamadım.",
    private val onError: (Throwable) -> Unit = {},
) {
    suspend fun handle(transcript: String): Reply {
        val intent = resolver.resolve(transcript) ?: return Reply(notUnderstood)
        val skill = registry.find(intent.skillId)
            ?: return Reply(notUnderstood).also {
                onError(IllegalStateException("resolver returned unknown skill '${intent.skillId}'"))
            }

        // Reported here rather than folded into SkillResult.Failed, so a crash is logged once.
        val result = try {
            skill.execute(intent.params)
        } catch (e: Exception) {
            onError(e)
            return Reply(SOMETHING_WENT_WRONG)
        }

        return when (result) {
            is SkillResult.Spoken -> Reply(result.text)
            is SkillResult.Silent -> Reply.Nothing
            is SkillResult.Failed ->
                Reply(result.spoken).also { onError(SkillFailed(skill.definition.id, result.reason)) }
        }
    }

    private companion object {
        const val SOMETHING_WENT_WRONG = "Bunu yaparken bir sorun çıktı."
    }
}

class SkillFailed(skillId: String, reason: String) : Exception("skill '$skillId' failed: $reason")
