package dev.alf.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurkishNumbersTest {

    @Test
    fun `comma is the decimal separator`() {
        assertEquals(34.1234, TurkishNumbers.parse("34,1234"))
        assertEquals(-0.23, TurkishNumbers.parse("-0,23"))
    }

    @Test
    fun `dots group thousands when a comma is present`() {
        assertEquals(9876.54, TurkishNumbers.parse("9.876,54"))
        assertEquals(58382970493.0, TurkishNumbers.parse("58.382.970.493,00"))
    }

    @Test
    fun `a lone dot in a grouped shape is thousands, not a decimal`() {
        // "13.964" is the index, not thirteen point nine. Getting this backwards is a thousandfold
        // error that alf would read out with full confidence.
        assertEquals(13964.0, TurkishNumbers.parse("13.964"))
        assertEquals(1234567.0, TurkishNumbers.parse("1.234.567"))
    }

    @Test
    fun `a lone dot outside that shape stays a decimal`() {
        assertEquals(48.2975, TurkishNumbers.parse("48.2975"))
        assertEquals(1.15, TurkishNumbers.parse("1.15"))
    }

    @Test
    fun `plain integers pass through`() {
        assertEquals(42.0, TurkishNumbers.parse("42"))
    }

    @Test
    fun `spaces and non breaking spaces are ignored`() {
        assertEquals(9876.54, TurkishNumbers.parse(" 9.876,54 "))
        assertEquals(9876.54, TurkishNumbers.parse("9.876,54 "))
    }

    @Test
    fun `nonsense is null rather than zero`() {
        assertNull(TurkishNumbers.parse("abc"))
        assertNull(TurkishNumbers.parse(""))
        assertNull(TurkishNumbers.parse(null))
    }
}
