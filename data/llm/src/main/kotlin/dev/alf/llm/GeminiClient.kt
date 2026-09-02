package dev.alf.llm

data class TransportResult(val httpStatus: Int, val body: String)

/** Sends one prepared request to one model. Injected so the fallback logic can be tested. */
fun interface GeminiTransport {
    suspend fun send(model: GeminiModel, body: String): TransportResult
}

/**
 * Asks the models in turn until one answers.
 *
 * On a free tier an exhausted quota is the expected end of a model's day, not an error, so the
 * chain simply moves down and the good allowances get spent on the questions that arrive first.
 * A model that fails for some other reason is also stepped over — a second attempt costs nothing
 * here — but the first failure is the one reported, since a malformed request fails everywhere
 * and its reason is the useful one.
 */
class GeminiClient(
    private val chain: ModelChain,
    private val transport: GeminiTransport,
) {
    suspend fun ask(body: String): GeminiReply {
        val candidates = chain.available()
        if (candidates.isEmpty()) return GeminiReply.QuotaExhausted

        var firstFailure: GeminiReply.Failed? = null

        for (model in candidates) {
            val reply = runCatching { transport.send(model, body) }
                .fold(
                    onSuccess = { GeminiResponseParser.parse(it.body, it.httpStatus) },
                    onFailure = { GeminiReply.Failed(it.message ?: it.toString()) },
                )

            when (reply) {
                is GeminiReply.CallSkill, is GeminiReply.Spoken -> return reply
                GeminiReply.QuotaExhausted -> chain.markExhausted(model)
                is GeminiReply.Failed -> if (firstFailure == null) firstFailure = reply
            }
        }

        // Everything was tried. Quota exhaustion is the more useful thing to report when nothing
        // else went wrong, because it is temporary and resolves on its own.
        return firstFailure ?: GeminiReply.QuotaExhausted
    }
}
