package dev.alf.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtterancePatternTest {

    @Test
    fun `pattern without slots expands to itself`() {
        val expanded = UtterancePattern("saat kaç").expand()
        assertEquals(listOf(ExpandedUtterance("saat kaç", emptyMap())), expanded)
    }

    @Test
    fun `slot values become phrases and parameters`() {
        val pattern = UtterancePattern(
            template = "alarmı {saat} kur",
            slots = listOf(
                SlotSpec(
                    name = "saat",
                    param = "hour",
                    values = listOf(SlotValue("yediye", "7"), SlotValue("sekize", "8")),
                ),
            ),
        )

        assertContentEquals(
            listOf(
                ExpandedUtterance("alarmı yediye kur", mapOf("hour" to "7")),
                ExpandedUtterance("alarmı sekize kur", mapOf("hour" to "8")),
            ),
            pattern.expand(),
        )
    }

    @Test
    fun `two slots expand to their product`() {
        val pattern = UtterancePattern(
            template = "{a} ve {b}",
            slots = listOf(
                SlotSpec("a", "first", values = listOf(SlotValue("bir", "1"), SlotValue("iki", "2"))),
                SlotSpec("b", "second", values = listOf(SlotValue("üç", "3"))),
            ),
        )

        val expanded = pattern.expand()
        assertEquals(2, expanded.size)
        assertEquals("bir ve üç", expanded[0].phrase)
        assertEquals(mapOf("first" to "1", "second" to "3"), expanded[0].params)
    }

    @Test
    fun `free text cannot be enumerated`() {
        val pattern = UtterancePattern(
            template = "not al {metin}",
            slots = listOf(SlotSpec("metin", "text", kind = SlotKind.FREE_TEXT)),
        )

        assertFalse(pattern.enumerable)
        assertTrue(pattern.expand().isEmpty())
    }

    @Test
    fun `runtime slot stays enumerable and fills in later`() {
        val pattern = UtterancePattern(
            template = "{uygulama} aç",
            slots = listOf(SlotSpec("uygulama", "package", kind = SlotKind.RUNTIME)),
        )

        assertTrue(pattern.enumerable)
        assertTrue(pattern.expand().isEmpty(), "no values known yet")

        val filled = pattern.withSlotValues("uygulama", listOf(SlotValue("takvim", "com.android.calendar")))
        assertEquals(
            listOf(ExpandedUtterance("takvim aç", mapOf("package" to "com.android.calendar"))),
            filled.expand(),
        )
    }

    @Test
    fun `template and slot list must agree`() {
        assertFailsWith<IllegalArgumentException> {
            UtterancePattern("alarmı {saat} kur", slots = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            UtterancePattern("alarm kur", slots = listOf(SlotSpec("saat", "hour", values = listOf(SlotValue("bire", "1")))))
        }
    }

    @Test
    fun `enumerated slot without values is rejected`() {
        assertFailsWith<IllegalArgumentException> { SlotSpec("saat", "hour") }
    }
}
