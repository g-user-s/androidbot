package dev.alf.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * The one way this app reaches the network.
 *
 * Plain `HttpURLConnection` rather than a client library: three small GETs do not justify the
 * dependency, and on a device with 2 GB of memory every avoided library is memory the assistant
 * keeps. The response is capped because a feed that unexpectedly returns something enormous
 * should fail cleanly instead of taking the process down.
 */
class HttpFetcher(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxBytes: Int = 512 * 1024,
) {
    data class Response(val status: Int, val body: String)

    /** Throws on anything but a 2xx, because a feed that did not answer has nothing to parse. */
    suspend fun get(url: String): String {
        val response = request(url, method = "GET")
        if (response.status !in 200..299) throw IOException("$url returned ${response.status}")
        return response.body
    }

    /**
     * Returns the status alongside the body rather than throwing.
     *
     * The model service says things worth reading in its error responses — an exhausted quota
     * comes back as a 429 with a body that names it — and turning that into an exception would
     * throw away exactly the information the caller needs to pick another model.
     */
    suspend fun post(
        url: String,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        headers: Map<String, String> = emptyMap(),
    ): Response = request(url, method = "POST", body = body, contentType = contentType, headers = headers)

    private suspend fun request(
        url: String,
        method: String,
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            // Some feeds refuse the default Java agent outright.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                contentType?.let { setRequestProperty("Content-Type", it) }
            }
        }

        try {
            body?.let { payload ->
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val charset = connection.contentEncoding?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: Charsets.UTF_8

            // An error status still carries a body, and that body is the useful part.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { source ->
                val buffer = ByteArray(16 * 1024)
                val collected = StringBuilder()
                var total = 0
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) throw IOException("$url exceeded $maxBytes bytes")
                    collected.append(String(buffer, 0, read, charset))
                }
                collected.toString()
            }.orEmpty()

            Response(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "alf/0.1 (Android)"
    }
}
