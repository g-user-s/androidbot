package dev.alf.sources

/**
 * Reads the numbers Turkish financial feeds actually emit.
 *
 * They arrive as strings, and the separators are the opposite of the JVM default: a comma marks
 * the decimal and a dot groups thousands, so `9.876,54` is nine thousand and `34,1234` is
 * thirty four. Parsing either with [String.toDouble] silently produces a number that is wrong by
 * three orders of magnitude, which is worse than failing — alf would confidently read it out.
 */
object TurkishNumbers {

    fun parse(raw: String?): Double? {
        val text = raw?.trim()?.replace(WHITESPACE, "") ?: return null
        if (text.isEmpty()) return null

        val hasComma = ',' in text
        val hasDot = '.' in text

        val normalised = when {
            // "9.876,54" — dot groups, comma decides the decimal.
            hasComma && hasDot -> text.replace(".", "").replace(',', '.')
            // "34,1234" — a lone comma is always the decimal separator here.
            hasComma -> text.replace(',', '.')
            // "9.876" is ambiguous. Three digits after a single dot and no other separator is
            // thousands grouping far more often than it is a price with three decimals.
            hasDot -> if (THOUSANDS.matches(text)) text.replace(".", "") else text
            else -> text
        }

        return normalised.toDoubleOrNull()
    }

    private val WHITESPACE = Regex("[\\s\\u00A0]")
    private val THOUSANDS = Regex("^-?\\d{1,3}(\\.\\d{3})+$")
}
