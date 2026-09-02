package dev.alf.voicegen

import dev.alf.domain.SkillCatalog
import dev.alf.dsp.TemplateStore
import dev.alf.dsp.WavWriter
import dev.alf.nlu.OfflineVocabulary
import java.io.File
import kotlin.system.exitProcess

/**
 * Builds alf's reference templates and wake clips on a workstation.
 *
 * Run once, and whenever the vocabulary changes. The device never talks to a speech service:
 * everything this produces is committed and shipped inside the apk, so the assistant keeps
 * working with no network and answers the wake word without waiting on anything.
 *
 *   export ELEVENLABS_API_KEY=...
 *   ./gradlew :tools:voicegen:run --args="--voices <id>,<id>"
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)

    val apiKey = System.getenv(API_KEY_ENV)?.takeIf { it.isNotBlank() } ?: fail(
        "$API_KEY_ENV is not set. Export it in the shell that runs this tool — do not put the key " +
            "in the repository.",
    )

    val voiceIds = options.voices.ifEmpty {
        System.getenv(VOICES_ENV)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
    if (voiceIds.isEmpty()) fail("No voices given. Pass --voices <id>[,<id>] or set $VOICES_ENV.")

    val client = ElevenLabsClient(apiKey)
    val assetsDir = File(options.assetsDir).apply { mkdirs() }
    val clipsDir = File(options.clipsDir).apply { mkdirs() }

    println("Wake clips -> ${clipsDir.path}")
    SkillCatalog.WAKE_RESPONSES.forEachIndexed { index, text ->
        val pcm = client.synthesize(text, voiceIds.first())
        val target = File(clipsDir, "wake_$index.wav")
        target.writeBytes(WavWriter.wrap(pcm, ElevenLabsClient.SAMPLE_RATE))
        println("  \"$text\" -> ${target.name} (${target.length()} bytes)")
    }

    val vocabulary = OfflineVocabulary.build()
    println("Templates -> ${assetsDir.path} (${vocabulary.size} phrases x ${voiceIds.size} voices)")

    val result = TemplateBuilder(client).build(vocabulary, voiceIds) { done, total, phrase ->
        println("  [$done/$total] $phrase")
    }

    if (result.templates.isEmpty()) fail("No templates were produced; nothing written.")

    val templatesFile = File(assetsDir, TEMPLATES_FILE)
    templatesFile.outputStream().use { TemplateStore.write(result.templates, it) }

    println()
    println("Wrote ${result.templates.size} templates to ${templatesFile.path} (${templatesFile.length()} bytes)")
    if (result.failures.isNotEmpty()) {
        println("${result.failures.size} phrase/voice pairs failed:")
        result.failures.take(20).forEach { println("  ${it.voiceId}: \"${it.phrase}\" — ${it.reason}") }
    }
}

private class Options(val voices: List<String>, val assetsDir: String, val clipsDir: String)

private fun parseArgs(args: Array<String>): Options {
    var voices = emptyList<String>()
    var assets = DEFAULT_ASSETS_DIR
    var clips = DEFAULT_CLIPS_DIR

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--voices" -> voices = args.getOrNull(++i)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: fail("--voices needs a comma separated list of voice ids")
            "--assets" -> assets = args.getOrNull(++i) ?: fail("--assets needs a directory")
            "--clips" -> clips = args.getOrNull(++i) ?: fail("--clips needs a directory")
            "--help", "-h" -> {
                println(USAGE)
                exitProcess(0)
            }
            else -> fail("Unknown argument '${args[i]}'.\n$USAGE")
        }
        i++
    }
    return Options(voices, assets, clips)
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

private const val API_KEY_ENV = "ELEVENLABS_API_KEY"
private const val VOICES_ENV = "ELEVENLABS_VOICE_IDS"
private const val TEMPLATES_FILE = "templates.alf"
private const val DEFAULT_ASSETS_DIR = "app/src/main/assets"
private const val DEFAULT_CLIPS_DIR = "app/src/main/res/raw"

private val USAGE = """
    Usage: voicegen [--voices <id>[,<id>...]] [--assets <dir>] [--clips <dir>]

      $API_KEY_ENV   required, read from the environment
      $VOICES_ENV    used when --voices is not given
""".trimIndent()
