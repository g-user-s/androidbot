package dev.alf.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelChainTest {

    private var day = 1L
    private fun chain(models: List<GeminiModel> = Models.DEFAULT_CHAIN) = ModelChain(models) { day }

    @Test
    fun `the newest model comes first and the lite variants last`() {
        val ids = Models.DEFAULT_CHAIN.map { it.id }

        assertEquals("gemini-3.7-flash", ids.first())
        assertTrue(ids.last().contains("lite"), ids.toString())
    }

    @Test
    fun `a user supplied list is read one entry per line or comma`() {
        // New revisions ship often; the point of this is that a new one needs no new build.
        val parsed = Models.parse("gemini-4-flash\n gemini-3.7-flash , gemini-3.5-flash-lite")

        assertEquals(
            listOf("gemini-4-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite"),
            parsed.map { it.id },
        )
    }

    @Test
    fun `blank lines, repeats and junk are dropped`() {
        val parsed = Models.parse("\n\ngemini-3.7-flash\n\ngemini-3.7-flash\n  \nhttps://oops\n")

        assertEquals(listOf("gemini-3.7-flash"), parsed.map { it.id })
    }

    @Test
    fun `an empty setting parses to nothing so the caller can fall back to the defaults`() {
        assertTrue(Models.parse("").isEmpty())
        assertTrue(Models.parse("   \n  ").isEmpty())
    }

    @Test
    fun `an exhausted model steps aside`() {
        val chain = chain()
        chain.markExhausted(Models.DEFAULT_CHAIN[0])

        assertEquals(Models.DEFAULT_CHAIN[1].id, chain.available().first().id)
        assertEquals(Models.DEFAULT_CHAIN.size - 1, chain.available().size)
    }

    @Test
    fun `everything spent is reported rather than retried`() {
        val chain = chain()
        Models.DEFAULT_CHAIN.forEach { chain.markExhausted(it) }

        assertTrue(chain.allExhausted())
        assertTrue(chain.available().isEmpty())
    }

    @Test
    fun `quotas come back with the new day`() {
        // A model spent at 23:50 is worth trying again ten minutes later.
        val chain = chain()
        Models.DEFAULT_CHAIN.forEach { chain.markExhausted(it) }
        assertTrue(chain.allExhausted())

        day = 2L

        assertFalse(chain.allExhausted())
        assertEquals(Models.DEFAULT_CHAIN.size, chain.available().size)
    }

    @Test
    fun `an empty chain is a programming error`() {
        assertFailsWith<IllegalArgumentException> { ModelChain(emptyList()) { day } }
    }
}
