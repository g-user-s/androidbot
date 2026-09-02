package dev.alf.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class Stub(id: String) : Skill {
    override val definition = SkillDefinition(id, "stub")
    override suspend fun execute(params: Map<String, String>) = SkillResult.Silent
}

class SkillRegistryTest {

    @Test
    fun `looks skills up by id`() {
        val registry = SkillRegistry(listOf(Stub("a"), Stub("b")))

        assertEquals("a", registry.find("a")?.definition?.id)
        assertNull(registry.find("c"))
        assertEquals(2, registry.definitions.size)
    }

    @Test
    fun `duplicate ids are rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SkillRegistry(listOf(Stub("a"), Stub("a")))
        }
        assertEquals(true, failure.message?.contains("a"))
    }
}
