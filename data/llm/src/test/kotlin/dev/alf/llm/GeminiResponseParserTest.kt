package dev.alf.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiResponseParserTest {

    private fun candidate(parts: String) =
        """{"candidates":[{"content":{"role":"model","parts":[$parts]},"finishReason":"STOP"}]}"""

    @Test
    fun `a function call becomes a skill call`() {
        val payload = candidate("""{"functionCall":{"name":"set_alarm","args":{"hour":"7"}}}""")

        val reply = assertIs<GeminiReply.CallSkill>(GeminiResponseParser.parse(payload))
        assertEquals("set_alarm", reply.skillId)
        assertEquals(mapOf("hour" to "7"), reply.arguments)
    }

    @Test
    fun `numeric arguments are flattened to strings`() {
        // Skill parameters are strings throughout; 7 and "7" mean the same hour.
        val payload = candidate("""{"functionCall":{"name":"set_timer","args":{"minutes":15}}}""")

        val reply = assertIs<GeminiReply.CallSkill>(GeminiResponseParser.parse(payload))
        assertEquals(mapOf("minutes" to "15"), reply.arguments)
    }

    @Test
    fun `plain text becomes something to say`() {
        val reply = assertIs<GeminiReply.Spoken>(GeminiResponseParser.parse(candidate("""{"text":"Yarın yağmurlu."}""")))

        assertEquals("Yarın yağmurlu.", reply.text)
    }

    @Test
    fun `a call wins over text in the same answer`() {
        // When the model both narrates and calls, the call is what the speaker asked for.
        val payload = candidate("""{"text":"Tabii."},{"functionCall":{"name":"time_now","args":{}}}""")

        val reply = assertIs<GeminiReply.CallSkill>(GeminiResponseParser.parse(payload))
        assertEquals("time_now", reply.skillId)
    }

    @Test
    fun `split text is joined`() {
        val reply = assertIs<GeminiReply.Spoken>(
            GeminiResponseParser.parse(candidate("""{"text":"Bugün"},{"text":"hava güzel."}""")),
        )

        assertEquals("Bugün hava güzel.", reply.text)
    }

    @Test
    fun `an exhausted quota is recognised from the status and from the body`() {
        assertIs<GeminiReply.QuotaExhausted>(GeminiResponseParser.parse("{}", httpStatus = 429))
        assertIs<GeminiReply.QuotaExhausted>(
            GeminiResponseParser.parse("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}"""),
        )
    }

    @Test
    fun `other api errors are failures, not quota`() {
        val reply = assertIs<GeminiReply.Failed>(
            GeminiResponseParser.parse("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"bad audio"}}"""),
        )

        assertTrue("bad audio" in reply.reason, reply.reason)
    }

    @Test
    fun `an unrecognised shape fails instead of inventing an answer`() {
        assertIs<GeminiReply.Failed>(GeminiResponseParser.parse("not json"))
        assertIs<GeminiReply.Failed>(GeminiResponseParser.parse("{}"))
        assertIs<GeminiReply.Failed>(GeminiResponseParser.parse(candidate("")))
        assertIs<GeminiReply.Failed>(GeminiResponseParser.parse(candidate("""{"text":"   "}""")))
        assertIs<GeminiReply.Failed>(GeminiResponseParser.parse(candidate("""{"functionCall":{"args":{}}}""")))
    }

    @Test
    fun `a blocked or empty candidate list is a failure`() {
        assertIs<GeminiReply.Failed>(GeminiResponseParser.parse("""{"candidates":[]}"""))
        assertIs<GeminiReply.Failed>(GeminiResponseParser.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
    }
}
