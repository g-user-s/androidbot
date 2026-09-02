package dev.alf.skills

import dev.alf.llm.GeminiModel
import dev.alf.llm.GeminiTransport
import dev.alf.llm.TransportResult

/**
 * Carries a prepared request to the model service.
 *
 * The key travels in a header rather than the query string. A key in a URL ends up in access
 * logs, crash reports and anything that records the address — none of which are places a
 * credential should come to rest.
 */
class GeminiTransportHttp(
    private val apiKey: String,
    private val http: HttpFetcher = HttpFetcher(readTimeoutMs = 30_000),
) : GeminiTransport {

    override suspend fun send(model: GeminiModel, body: String): TransportResult {
        val response = http.post(
            url = "$BASE_URL/models/${model.id}:generateContent",
            body = body,
            headers = mapOf("x-goog-api-key" to apiKey),
        )
        return TransportResult(response.status, response.body)
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}
