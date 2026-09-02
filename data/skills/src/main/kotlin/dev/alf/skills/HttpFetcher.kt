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
    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            // Some feeds refuse the default Java agent outright.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("$url returned $status")

            val charset = connection.contentEncoding?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: Charsets.UTF_8

            connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                val body = StringBuilder()
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) throw IOException("$url exceeded $maxBytes bytes")
                    body.append(String(buffer, 0, read, charset))
                }
                body.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "alf/0.1 (Android)"
    }
}
