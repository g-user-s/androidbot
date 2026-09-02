package dev.alf.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillCatalogTest {

    @Test
    fun `every definition is well formed`() {
        assertTrue(SkillCatalog.definitions.isNotEmpty())
        assertEquals(
            SkillCatalog.definitions.size,
            SkillCatalog.definitions.map { it.id }.toSet().size,
            "skill ids must be unique",
        )
    }

    @Test
    fun `only free text skills cannot be recognised offline`() {
        val recognisable = SkillCatalog.definitions.filter { it.recognisableOffline }.map { it.id }.toSet()

        assertTrue(SkillCatalog.Ids.SET_ALARM in recognisable)
        assertTrue(SkillCatalog.Ids.TIME_NOW in recognisable)
        assertFalse(SkillCatalog.Ids.TAKE_NOTE in recognisable, "a note body is not a finite phrase")
    }

    @Test
    fun `data skills are heard offline even though they cannot answer`() {
        // The distinction alf's replies depend on: it should say it has no connection rather
        // than that it did not understand.
        val weather = SkillCatalog.definitions.first { it.id == SkillCatalog.Ids.WEATHER_NOW }

        assertTrue(weather.recognisableOffline)
        assertTrue(weather.requiresNetwork)
    }

    @Test
    fun `only the data skills need the network`() {
        val networked = SkillCatalog.definitions.filter { it.requiresNetwork }.map { it.id }.toSet()

        assertEquals(
            setOf(
                SkillCatalog.Ids.WEATHER_NOW,
                SkillCatalog.Ids.WEATHER_TOMORROW,
                SkillCatalog.Ids.NEWS_HEADLINES,
                SkillCatalog.Ids.MARKET_QUOTE,
                SkillCatalog.Ids.MARKET_SUMMARY,
            ),
            networked,
        )
    }

    @Test
    fun `alarm hours cover a twelve hour clock`() {
        val alarm = SkillCatalog.definitions.first { it.id == SkillCatalog.Ids.SET_ALARM }
        val hours = alarm.utterances.flatMap { it.expand() }.map { it.params.getValue("hour") }.toSet()

        assertEquals((1..12).map { it.toString() }.toSet(), hours)
    }

    @Test
    fun `slots must bind to declared parameters`() {
        assertFailsWith<IllegalArgumentException> {
            SkillDefinition(
                id = "broken",
                description = "binds to a parameter that does not exist",
                parameters = emptyList(),
                utterances = listOf(
                    UtterancePattern(
                        "{x} yap",
                        listOf(SlotSpec("x", param = "missing", values = listOf(SlotValue("bir", "1")))),
                    ),
                ),
            )
        }
    }

    @Test
    fun `wake responses are provided`() {
        assertEquals("hey alf", SkillCatalog.WAKE_WORD)
        assertTrue(SkillCatalog.WAKE_RESPONSES.size >= 2)
    }
}
