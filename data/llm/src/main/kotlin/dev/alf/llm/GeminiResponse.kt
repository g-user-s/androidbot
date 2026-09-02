package dev.alf.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What came back from the model. */
sealed interface GeminiReply {
    /** The model chose a skill. [arguments] are already flattened to strings. */
    data class CallSkill(val skillId: String, val arguments: Map<String, String>) : GeminiReply

    /** The model answered in words. */
    data class Spoken(val text: String) : GeminiReply

    /** The daily allowance for this model is spent; the caller should drop to the next one. */
    data object QuotaExhausted : GeminiReply

    /** Anything else, including a response in a shape this code does not recognise. */
    data class Failed(val reason: String) : GeminiReply
}

/**
 * Reads the model's answer.
 *
 * A response that does not match the expected shape becomes [GeminiReply.Failed] rather than an
 * empty string or a made up call. The assistant says it could not manage that, which is honest;
 * inventing a skill call from a half understood response would have it act on a guess.
 */
object GeminiResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(payload: String, httpStatus: Int = 200): GeminiReply {
        if (httpStatus == 429) return GeminiReply.QuotaExhausted

        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return GeminiReply.Failed("unparseable response")

        (root["error"] as? JsonObject)?.let { error ->
            val status = (error["status"] as? JsonPrimitive)?.content
            val code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (status == "RESOURCE_EXHAUSTED" || code == 429) return GeminiReply.QuotaExhausted
            return GeminiReply.Failed("api error: ${(error["message"] as? JsonPrimitive)?.content ?: status}")
        }

        if (httpStatus !in 200..299) return GeminiReply.Failed("http $httpStatus")

        val parts = (root["candidates"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.let { it["content"] as? JsonObject }
            ?.let { it["parts"] as? JsonArray }
            ?: return GeminiReply.Failed("no candidate content")

        // A function call is checked for first: when the model both says something and calls a
        // skill, the call is what the speaker asked for.
        parts.mapNotNull { (it as? JsonObject)?.get("functionCall") as? JsonObject }
            .firstOrNull()
            ?.let { call ->
                val name = (call["name"] as? JsonPrimitive)?.content
                    ?: return GeminiReply.Failed("function call without a name")
                return GeminiReply.CallSkill(name, argumentsOf(call["args"] as? JsonObject))
            }

        val text = parts.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
            .joinToString(" ") { it.content }
            .trim()

        return if (text.isEmpty()) GeminiReply.Failed("empty answer") else GeminiReply.Spoken(text)
    }

    /**
     * Skill parameters are strings throughout, so a number or a boolean the model returns is
     * flattened rather than rejected — "7" and 7 mean the same hour.
     */
    private fun argumentsOf(args: JsonObject?): Map<String, String> =
        args.orEmpty().mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()
}
