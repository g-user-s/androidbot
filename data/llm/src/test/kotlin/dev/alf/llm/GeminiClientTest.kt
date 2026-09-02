package dev.alf.llm

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiClientTest {

    private val models = listOf(
        GeminiModel("best"),
        GeminiModel("good"),
        GeminiModel("cheap"),
    )

    private var day = 1L
    private val tried = mutableListOf<String>()

    private fun clientOf(responses: Map<String, TransportResult>, chain: ModelChain = ModelChain(models) { day }) =
        chain to GeminiClient(chain) { model, _ ->
            tried += model.id
            responses[model.id] ?: TransportResult(500, """{"error":{"code":500,"message":"boom"}}""")
        }

    private fun answer(text: String) =
        TransportResult(200, """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}""")

    private val quota = TransportResult(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""")

    @Test
    fun `the first model that answers wins`() = runBlocking {
        val (_, client) = clientOf(mapOf("best" to answer("tamam")))

        assertEquals(GeminiReply.Spoken("tamam"), client.ask("{}"))
        assertEquals(listOf("best"), tried)
    }

    @Test
    fun `an exhausted model hands over to the next`() = runBlocking {
        val (_, client) = clientOf(mapOf("best" to quota, "good" to answer("tamam")))

        assertEquals(GeminiReply.Spoken("tamam"), client.ask("{}"))
        assertEquals(listOf("best", "good"), tried)
    }

    @Test
    fun `an exhausted model is not tried again the same day`() = runBlocking {
        val chain = ModelChain(models) { day }
        val (_, client) = clientOf(mapOf("best" to quota, "good" to answer("tamam")), chain)

        client.ask("{}")
        tried.clear()
        client.ask("{}")

        assertEquals(listOf("good"), tried, "the spent model should have been skipped")
    }

    @Test
    fun `quotas come back the next day`() = runBlocking {
        val chain = ModelChain(models) { day }
        val (_, client) = clientOf(mapOf("best" to quota, "good" to answer("tamam")), chain)
        client.ask("{}")

        day = 2L
        tried.clear()
        client.ask("{}")

        assertTrue(tried.first() == "best", "the new day should have restored the best model")
    }

    @Test
    fun `all quotas spent is reported as such`() = runBlocking {
        val (_, client) = clientOf(models.associate { it.id to quota })

        assertIs<GeminiReply.QuotaExhausted>(client.ask("{}"))
        assertEquals(models.map { it.id }, tried)
    }

    @Test
    fun `a failing model is stepped over`() = runBlocking {
        val (_, client) = clientOf(mapOf("good" to answer("tamam")))

        assertEquals(GeminiReply.Spoken("tamam"), client.ask("{}"))
        assertEquals(listOf("best", "good"), tried)
    }

    @Test
    fun `when every model fails the first reason is the one reported`() = runBlocking {
        val (_, client) = clientOf(
            mapOf(
                "best" to TransportResult(400, """{"error":{"code":400,"message":"ilk hata"}}"""),
                "good" to TransportResult(400, """{"error":{"code":400,"message":"ikinci hata"}}"""),
            ),
        )

        val reply = assertIs<GeminiReply.Failed>(client.ask("{}"))
        assertTrue("ilk hata" in reply.reason, reply.reason)
    }

    @Test
    fun `a transport that throws is treated as a failure, not a crash`() = runBlocking {
        val chain = ModelChain(models) { day }
        val client = GeminiClient(chain) { _, _ -> throw java.io.IOException("ağ yok") }

        val reply = assertIs<GeminiReply.Failed>(client.ask("{}"))
        assertTrue("ağ yok" in reply.reason, reply.reason)
    }

    @Test
    fun `nothing is sent once the day is spent`() = runBlocking {
        val chain = ModelChain(models) { day }
        models.forEach { chain.markExhausted(it) }
        val (_, client) = clientOf(mapOf("best" to answer("tamam")), chain)

        assertIs<GeminiReply.QuotaExhausted>(client.ask("{}"))
        assertTrue(tried.isEmpty(), "no request should have been made")
    }
}
