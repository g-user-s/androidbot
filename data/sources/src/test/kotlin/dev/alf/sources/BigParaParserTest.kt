package dev.alf.sources

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercised against a response actually returned by the feed, not a hand written stand-in. */
class BigParaParserTest {

    private val payload: String =
        checkNotNull(javaClass.getResourceAsStream("/bigpara-headerlist.json")) { "fixture missing" }
            .bufferedReader().readText()

    private fun quote(instrument: Instrument) = assertNotNull(
        BigParaParser.quoteOf(payload, instrument),
        "no quote for ${instrument.symbol}",
    )

    @Test
    fun `reads every instrument in the response`() {
        val symbols = BigParaParser.parse(payload).map { it.symbol }

        assertEquals(
            listOf("TAHVIL", "XU100", "BRENT", "EURTRY", "EURUSD", "GLDGR", "USDTRY", "GBPTRY"),
            symbols,
        )
    }

    @Test
    fun `the index price comes from the close, not from a zero ask`() {
        // XU100 reports ALIS and SATIS as 0.0 — an index has no bid or ask. Reading SATIS would
        // have alf announce that the market stands at zero.
        val bist = quote(Instrument.BIST100)

        assertTrue(abs(bist.price - 13_964.16) < 0.001, "price was ${bist.price}")
        assertTrue(bist.falling)
    }

    @Test
    fun `currency prices come from the ask`() {
        assertTrue(abs(quote(Instrument.USD).price - 48.2975) < 0.0001)
        assertTrue(abs(quote(Instrument.EUR).price - 55.9483) < 0.0001)
        assertTrue(abs(quote(Instrument.GBP).price - 65.2744) < 0.0001)
    }

    @Test
    fun `gram gold is read from its own symbol`() {
        val gold = quote(Instrument.GRAM_GOLD)

        assertTrue(abs(gold.price - 6_705.583) < 0.001)
        assertEquals("ALTIN GRAM - TL", gold.label)
    }

    @Test
    fun `percent change and quote time are kept`() {
        val dollar = quote(Instrument.USD)

        assertEquals(0.01, dollar.changePercent)
        assertEquals("12:08", dollar.time)
        assertTrue(dollar.rising)
    }

    @Test
    fun `numbers sent as Turkish formatted strings are accepted too`() {
        // A sibling feed sends these as strings with a comma decimal; reading them as plain
        // doubles would be off by a factor of a thousand.
        val stringly = """{"data":[{"SEMBOL":"USDTRY","ACIKLAMA":"Dolar","SATIS":"48,2975","YUZDEDEGISIM":"0,01"}]}"""

        val quote = assertNotNull(BigParaParser.quoteOf(stringly, Instrument.USD))
        assertTrue(abs(quote.price - 48.2975) < 0.0001, "price was ${quote.price}")
    }

    @Test
    fun `a renamed wrapper key does not take the feed down`() {
        val renamed = """{"payload":{"items":[{"SEMBOL":"USDTRY","ACIKLAMA":"Dolar","SATIS":48.3}]}}"""

        assertNotNull(BigParaParser.quoteOf(renamed, Instrument.USD))
    }

    @Test
    fun `entries without a usable price are skipped rather than reported as zero`() {
        val broken = """{"data":[{"SEMBOL":"XU100","ACIKLAMA":"BIST 100","SATIS":0.0,"KAPANIS":0.0,"ACILIS":0.0}]}"""

        assertTrue(BigParaParser.parse(broken).isEmpty())
    }

    @Test
    fun `rubbish parses to nothing instead of throwing`() {
        assertTrue(BigParaParser.parse("not json at all").isEmpty())
        assertTrue(BigParaParser.parse("").isEmpty())
        assertNull(BigParaParser.quoteOf("""{"data":[]}""", Instrument.USD))
    }
}
