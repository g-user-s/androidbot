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
    fun `free text skills are the only ones unavailable offline`() {
        val offline = SkillCatalog.definitions.filter { it.availableOffline }.map { it.id }.toSet()

        assertTrue(SkillCatalog.Ids.SET_ALARM in offline)
        assertTrue(SkillCatalog.Ids.TIME_NOW in offline)
        assertTrue(SkillCatalog.Ids.OPEN_APP in offline, "runtime slots are still enumerable on device")
        assertFalse(SkillCatalog.Ids.WEB_SEARCH in offline)
        assertFalse(SkillCatalog.Ids.TAKE_NOTE in offline)
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
