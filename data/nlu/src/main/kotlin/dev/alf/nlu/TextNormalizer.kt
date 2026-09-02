package dev.alf.nlu

import java.util.Locale


/**
 * Puts transcripts and catalog phrases into the same shape before they are compared.
 *
 * The Turkish locale matters: the default lowercase maps 'I' to 'i', which turns "Işığı" into
 * "işığı" and breaks matching against "ışığı". Apostrophes are dropped rather than replaced by
 * a space so that "7'ye" collapses to "7ye" instead of splitting into two tokens.
 */
object TextNormalizer {

    // Locale.of() would be the modern call, but it does not exist on Android 10's API level.
    @Suppress("DEPRECATION")
    private val TURKISH = Locale("tr", "TR")
    private val APOSTROPHES = Regex("['‘’ʼ`]")
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(raw: String): String = raw
        .replace(APOSTROPHES, "")
        .lowercase(TURKISH)
        .replace(NON_WORD, " ")
        .replace(WHITESPACE, " ")
        .trim()
}
