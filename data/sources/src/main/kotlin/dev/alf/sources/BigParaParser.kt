package dev.alf.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the market feed that carries the exchange rates, gold and the index in one response.
 *
 * Two details in the payload decide how this is written. The index entry reports `ALIS` and
 * `SATIS` as `0.0` — an index has no bid or ask — so the price has to come from `KAPANIS` there,
 * and a parser that simply read `SATIS` would have alf announce that the market stands at zero.
 * And while this feed sends numbers as JSON numbers, sibling Turkish feeds send them as strings
 * with a comma decimal, so every numeric read goes through [TurkishNumbers] rather than
 * assuming one shape.
 */
object BigParaParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(payload: String): List<MarketQuote> {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return emptyList()
        val entries = quoteArray(root) ?: return emptyList()

        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val symbol = string(entry, "SEMBOL") ?: return@mapNotNull null

            // An index quotes no bid or ask, so its SATIS is zero; the close is the real number.
            val price = number(entry, "SATIS")?.takeIf { it > 0.0 }
                ?: number(entry, "KAPANIS")?.takeIf { it > 0.0 }
                ?: number(entry, "ACILIS")?.takeIf { it > 0.0 }
                ?: return@mapNotNull null

            MarketQuote(
                symbol = symbol,
                label = string(entry, "ACIKLAMA").orEmpty(),
                price = price,
                changePercent = number(entry, "YUZDEDEGISIM"),
                time = string(entry, "TARIHFORMAT"),
            )
        }
    }

    fun quoteOf(payload: String, instrument: Instrument): MarketQuote? =
        parse(payload).firstOrNull { it.symbol.equals(instrument.symbol, ignoreCase = true) }

    /**
     * The quotes live under `data`. Falling back to the first array of objects anywhere in the
     * tree costs a few lines and keeps a renamed wrapper key from taking the feature down.
     */
    private fun quoteArray(root: JsonElement): JsonArray? {
        (root as? JsonObject)?.get("data")?.let { if (it is JsonArray) return it }
        return firstObjectArray(root, depth = 0)
    }

    private fun firstObjectArray(element: JsonElement, depth: Int): JsonArray? {
        if (depth > MAX_DEPTH) return null
        when (element) {
            is JsonArray -> {
                if (element.any { it is JsonObject }) return element
                element.forEach { child -> firstObjectArray(child, depth + 1)?.let { return it } }
            }
            is JsonObject -> element.values.forEach { child ->
                firstObjectArray(child, depth + 1)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    private fun string(entry: JsonObject, key: String): String? =
        (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun number(entry: JsonObject, key: String): Double? {
        val primitive = entry[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) TurkishNumbers.parse(primitive.content) else primitive.content.toDoubleOrNull()
    }

    private const val MAX_DEPTH = 6
}
