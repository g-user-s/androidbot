package dev.alf.voicegen

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Speaks a phrase with a chosen voice and returns raw 16 kHz PCM.
 *
 * The sample rate is not a detail: the microphone is opened at 16 kHz, and a reference template
 * extracted at any other rate lands in a different feature space, making every distance
 * meaningless. Asking the service for `pcm_16000` gets the right rate at the source and skips a
 * resampling step that would otherwise sit between the two.
 *
 * This runs on a workstation, never on the device — the assistant must keep working with no
 * network, so nothing it says at runtime may depend on a remote service.
 */
class ElevenLabsClient(
    private val apiKey: String,
    private val modelId: String = DEFAULT_MODEL,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : SpeechSource {

    override fun synthesize(text: String, voiceId: String): ByteArray {
        val body = """{"text":${quote(text)},"model_id":${quote(modelId)}}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$BASE_URL/text-to-speech/$voiceId?output_format=$OUTPUT_FORMAT"))
            .timeout(Duration.ofSeconds(60))
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "audio/*")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) {
            // Surfaced rather than swallowed: a wrong voice id or an exhausted quota should stop
            // the run with the service's own explanation, not produce silent templates.
            throw IOException(
                "speech service returned ${response.statusCode()} for voice $voiceId: " +
                    String(response.body()).take(400),
            )
        }
        if (response.body().isEmpty()) throw IOException("speech service returned no audio for '$text'")
        return response.body()
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        private const val OUTPUT_FORMAT = "pcm_16000"
        private const val DEFAULT_MODEL = "eleven_multilingual_v2"
    }
}
