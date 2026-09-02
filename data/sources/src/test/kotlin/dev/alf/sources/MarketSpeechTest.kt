package dev.alf.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketSpeechTest {

    private val payload: String =
        checkNotNull(javaClass.getResourceAsStream("/bigpara-headerlist.json"))
            .bufferedReader().readText()

    private val quotes = BigParaParser.parse(payload)

    private fun spoken(instrument: Instrument) =
        MarketSpeech.quote(instrument, assertNotNull(BigParaParser.quoteOf(payload, instrument)))

    @Test
    fun `prices are spoken with a comma decimal and dotted thousands`() {
        // A Turkish voice reads "6705.58" as broken digits; "6.705,58" it reads as a number.
        assertEquals("Gram altın 6.705,58 lira, yüzde 0,23 düşüşte.", spoken(Instrument.GRAM_GOLD))
        assertEquals("Dolar 48,30 lira, yüzde 0,01 yükselişte.", spoken(Instrument.USD))
    }

    @Test
    fun `the index is spoken in points, without decimals`() {
        assertEquals("BIST 100 13.964 puan, yüzde 1,86 düşüşte.", spoken(Instrument.BIST100))
    }

    @Test
    fun `dollar quoted instruments say dolar`() {
        assertTrue(spoken(Instrument.BRENT).contains("dolar"), spoken(Instrument.BRENT))
    }

    @Test
    fun `a flat quote says so rather than claiming a direction`() {
        val flat = MarketQuote("USDTRY", "Dolar", price = 48.30, changePercent = 0.0, time = "12:00")

        assertEquals("Dolar 48,30 lira, değişim yok.", MarketSpeech.quote(Instrument.USD, flat))
    }

    @Test
    fun `a missing change is simply left out`() {
        val bare = MarketQuote("USDTRY", "Dolar", price = 48.30, changePercent = null, time = null)

        assertEquals("Dolar 48,30 lira.", MarketSpeech.quote(Instrument.USD, bare))
    }

    @Test
    fun `the summary covers the index, both currencies and gold`() {
        val summary = assertNotNull(MarketSpeech.summary(quotes))

        assertTrue(summary.startsWith("BIST 100"), summary)
        listOf("Dolar", "Euro", "Gram altın").forEach {
            assertTrue(it in summary, "$it missing from: $summary")
        }
    }

    @Test
    fun `an empty feed produces no summary rather than an empty sentence`() {
        assertNull(MarketSpeech.summary(emptyList()))
    }
}
