package dev.alf.sources

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Turns quotes into sentences a speech engine reads correctly.
 *
 * Formatting is not cosmetic here. A Turkish voice reads `6705.58` as a broken string of digits
 * and `6.705,58` as "altı bin yedi yüz beş virgül elli sekiz", so the numbers are rendered with a
 * comma decimal and dotted thousands before they ever reach the engine.
 */
object MarketSpeech {

    private val TURKISH = Locale("tr", "TR")
    private val symbols = DecimalFormatSymbols(TURKISH).apply {
        decimalSeparator = ','
        groupingSeparator = '.'
    }
    private val money = DecimalFormat("#,##0.00", symbols)
    private val points = DecimalFormat("#,##0", symbols)
    private val percent = DecimalFormat("0.00", symbols)

    fun quote(instrument: Instrument, quote: MarketQuote): String {
        val value = when (instrument.unit) {
            PriceUnit.LIRA -> "${money.format(quote.price)} lira"
            PriceUnit.DOLLAR -> "${money.format(quote.price)} dolar"
            PriceUnit.POINTS -> "${points.format(quote.price)} puan"
        }
        return "${instrument.spokenName} $value${change(quote)}."
    }

    /** One sentence covering the index, the two currencies people ask about, and gold. */
    fun summary(quotes: List<MarketQuote>): String? {
        val bySymbol = quotes.associateBy { it.symbol.uppercase(Locale.ROOT) }
        val parts = SUMMARY_ORDER.mapNotNull { instrument ->
            bySymbol[instrument.symbol]?.let { quote(instrument, it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun change(quote: MarketQuote): String {
        val percentChange = quote.changePercent ?: return ""
        if (abs(percentChange) < 0.005) return ", değişim yok"
        val direction = if (percentChange > 0) "yükselişte" else "düşüşte"
        return ", yüzde ${percent.format(abs(percentChange))} $direction"
    }

    private val SUMMARY_ORDER = listOf(
        Instrument.BIST100,
        Instrument.USD,
        Instrument.EUR,
        Instrument.GRAM_GOLD,
    )
}
