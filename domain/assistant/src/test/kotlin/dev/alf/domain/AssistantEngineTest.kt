package dev.alf.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeSkill(
    id: String,
    private val result: (Map<String, String>) -> SkillResult,
) : Skill {
    override val definition = SkillDefinition(id, "test skill")
    var seenParams: Map<String, String>? = null
    override suspend fun execute(params: Map<String, String>): SkillResult {
        seenParams = params
        return result(params)
    }
}

class AssistantEngineTest {

    private fun engineOf(
        skill: Skill,
        intent: Intent?,
        onError: (Throwable) -> Unit = {},
    ) = AssistantEngine(SkillRegistry(listOf(skill)), { intent }, onError = onError)

    @Test
    fun `spoken result is what alf says`() = runBlocking {
        val skill = FakeSkill("time_now") { SkillResult.Spoken("Saat üç.") }
        val reply = engineOf(skill, Intent("time_now")).handle("saat kaç")

        assertEquals("Saat üç.", reply.text)
        assertTrue(reply.speak)
    }

    @Test
    fun `resolved parameters reach the skill`() = runBlocking {
        val skill = FakeSkill("set_alarm") { SkillResult.Silent }
        engineOf(skill, Intent("set_alarm", mapOf("hour" to "7"))).handle("alarmı yediye kur")

        assertEquals(mapOf("hour" to "7"), skill.seenParams)
    }

    @Test
    fun `silent result says nothing`() = runBlocking {
        val skill = FakeSkill("cancel") { SkillResult.Silent }
        val reply = engineOf(skill, Intent("cancel")).handle("iptal")

        assertFalse(reply.speak)
        assertEquals("", reply.text)
    }

    @Test
    fun `unresolved input falls back without touching any skill`() = runBlocking {
        val skill = FakeSkill("time_now") { SkillResult.Spoken("nope") }
        val reply = engineOf(skill, intent = null).handle("bugün hava nasıl olacak")

        assertEquals("Bunu anlayamadım.", reply.text)
        assertEquals(null, skill.seenParams)
    }

    @Test
    fun `a throwing skill is reported but does not escape`() = runBlocking {
        val errors = mutableListOf<Throwable>()
        val skill = FakeSkill("set_alarm") { error("alarm clock app missing") }
        val reply = engineOf(skill, Intent("set_alarm"), onError = { errors += it }).handle("alarmı yediye kur")

        assertEquals("Bunu yaparken bir sorun çıktı.", reply.text)
        assertEquals(1, errors.size)
    }

    @Test
    fun `resolver naming an unknown skill falls back and reports`() = runBlocking {
        val errors = mutableListOf<Throwable>()
        val skill = FakeSkill("time_now") { SkillResult.Silent }
        val reply = engineOf(skill, Intent("ghost_skill"), onError = { errors += it }).handle("bir şey")

        assertEquals("Bunu anlayamadım.", reply.text)
        assertEquals(1, errors.size)
    }
}
