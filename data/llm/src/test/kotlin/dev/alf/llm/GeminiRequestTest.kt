package dev.alf.llm

import dev.alf.domain.SkillCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiRequestTest {

    private fun parse(request: String) = Json.parseToJsonElement(request) as JsonObject

    private fun declarations(request: String): List<JsonObject> =
        ((parse(request)["tools"] as JsonArray).first() as JsonObject)
            .let { it["functionDeclarations"] as JsonArray }
            .map { it as JsonObject }

    private fun name(declaration: JsonObject) = (declaration["name"] as JsonPrimitive).content

    @Test
    fun `every catalog skill is offered to the model`() {
        val request = GeminiRequest.forText("bugün ne yapsam", SkillCatalog.definitions)

        assertEquals(
            SkillCatalog.definitions.map { it.id }.toSet(),
            declarations(request).map { name(it) }.toSet(),
        )
    }

    @Test
    fun `a skill's parameters become its schema`() {
        val request = GeminiRequest.forText("x", SkillCatalog.definitions)
        val alarm = declarations(request).single { name(it) == SkillCatalog.Ids.SET_ALARM }

        val parameters = assertNotNull(alarm["parameters"] as? JsonObject)
        val properties = assertNotNull(parameters["properties"] as? JsonObject)

        assertTrue("hour" in properties.keys, properties.keys.toString())
        assertEquals("OBJECT", (parameters["type"] as JsonPrimitive).content)
        assertTrue("hour" in (parameters["required"] as JsonArray).map { (it as JsonPrimitive).content })
    }

    @Test
    fun `a skill without parameters carries no schema`() {
        val request = GeminiRequest.forText("x", SkillCatalog.definitions)
        val time = declarations(request).single { name(it) == SkillCatalog.Ids.TIME_NOW }

        assertTrue(time["parameters"] == null, "a parameterless skill should not declare a schema")
    }

    @Test
    fun `descriptions come straight from the catalog`() {
        val request = GeminiRequest.forText("x", SkillCatalog.definitions)
        val declaration = declarations(request).single { name(it) == SkillCatalog.Ids.NEWS_HEADLINES }
        val catalog = SkillCatalog.definitions.single { it.id == SkillCatalog.Ids.NEWS_HEADLINES }

        assertEquals(catalog.description, (declaration["description"] as JsonPrimitive).content)
    }

    @Test
    fun `a transcript is sent as text`() {
        val contents = parse(GeminiRequest.forText("yarın şemsiye lazım mı", SkillCatalog.definitions))["contents"] as JsonArray
        val parts = (contents.first() as JsonObject)["parts"] as JsonArray

        assertEquals("yarın şemsiye lazım mı", ((parts.first() as JsonObject)["text"] as JsonPrimitive).content)
    }

    @Test
    fun `audio is sent inline with its type`() {
        val contents = parse(GeminiRequest.forAudio("QUJD", SkillCatalog.definitions))["contents"] as JsonArray
        val inline = ((contents.first() as JsonObject)["parts"] as JsonArray)
            .first().let { (it as JsonObject)["inlineData"] as JsonObject }

        assertEquals("audio/wav", (inline["mimeType"] as JsonPrimitive).content)
        assertEquals("QUJD", (inline["data"] as JsonPrimitive).content)
    }

    @Test
    fun `the system instruction tells the model it is being listened to`() {
        val instruction = (parse(GeminiRequest.forText("x", SkillCatalog.definitions))["systemInstruction"] as JsonObject)
            .let { (it["parts"] as JsonArray).first() as JsonObject }
            .let { (it["text"] as JsonPrimitive).content }

        assertTrue("yüksek sesle" in instruction, instruction)
        assertTrue("emoji" in instruction, instruction)
    }

    @Test
    fun `turkish characters and quotes survive serialisation`() {
        val request = GeminiRequest.forText("\"ışığı\" aç dedim, olmadı\n", SkillCatalog.definitions)

        val text = ((parse(request)["contents"] as JsonArray).first() as JsonObject)
            .let { (it["parts"] as JsonArray).first() as JsonObject }
            .let { (it["text"] as JsonPrimitive).content }

        assertEquals("\"ışığı\" aç dedim, olmadı\n", text)
    }
}
