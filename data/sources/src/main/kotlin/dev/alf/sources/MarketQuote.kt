package dev.alf.sources

/** One instrument as the market feed reports it. */
data class MarketQuote(
    /** Feed symbol, e.g. `USDTRY`, `XU100`, `GLDGR`. */
    val symbol: String,
    /** The feed's own description, e.g. "Amerikan Doları Türk Lirası". */
    val label: String,
    val price: Double,
    /** Change since the previous close, in percent. */
    val changePercent: Double?,
    /** Time of the quote as the feed formatted it, e.g. "12:08". */
    val time: String?,
) {
    val rising: Boolean get() = (changePercent ?: 0.0) > 0.0
    val falling: Boolean get() = (changePercent ?: 0.0) < 0.0
}

/** The instruments alf can be asked about, and the feed symbols that carry them. */
enum class Instrument(val symbol: String, val spokenName: String, val unit: PriceUnit) {
    USD("USDTRY", "Dolar", PriceUnit.LIRA),
    EUR("EURTRY", "Euro", PriceUnit.LIRA),
    GBP("GBPTRY", "Sterlin", PriceUnit.LIRA),
    GRAM_GOLD("GLDGR", "Gram altın", PriceUnit.LIRA),
    BRENT("BRENT", "Brent petrol", PriceUnit.DOLLAR),
    BIST100("XU100", "BIST 100", PriceUnit.POINTS);

    companion object {
        fun ofSymbol(symbol: String): Instrument? =
            entries.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
    }
}

enum class PriceUnit { LIRA, DOLLAR, POINTS }
