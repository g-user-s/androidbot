package dev.alf.nlu

import dev.alf.domain.ParamSpec
import dev.alf.domain.SkillCatalog
import dev.alf.domain.SkillDefinition
import dev.alf.domain.SlotKind
import dev.alf.domain.SlotSpec
import dev.alf.domain.SlotValue
import dev.alf.domain.UtterancePattern
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleBasedIntentResolverTest {

    private val resolver = RuleBasedIntentResolver(SkillCatalog.definitions)

    @Test
    fun `exact phrase resolves with full confidence`() = runBlocking {
        val intent = assertNotNull(resolver.resolve("Saat kaç?"))

        assertEquals(SkillCatalog.Ids.TIME_NOW, intent.skillId)
        assertEquals(1f, intent.confidence)
    }

    @Test
    fun `slot value becomes a parameter`() = runBlocking {
        val intent = assertNotNull(resolver.resolve("alarmı yediye kur"))

        assertEquals(SkillCatalog.Ids.SET_ALARM, intent.skillId)
        assertEquals("7", intent.params["hour"])
    }

    @Test
    fun `duration slot maps to minutes`() = runBlocking {
        val intent = assertNotNull(resolver.resolve("yarım saat zamanlayıcı kur"))

        assertEquals(SkillCatalog.Ids.SET_TIMER, intent.skillId)
        assertEquals("30", intent.params["minutes"])
    }

    @Test
    fun `free text is captured whole`() = runBlocking {
        val intent = assertNotNull(resolver.resolve("not al ekmek almayı unutma"))

        assertEquals(SkillCatalog.Ids.TAKE_NOTE, intent.skillId)
        assertEquals("ekmek almayı unutma", intent.params["text"])
    }

    @Test
    fun `free text in the middle of a template stops at the trailing words`() = runBlocking {
        // Built here rather than taken from the catalog: this is a property of the resolver, and
        // it should keep being checked whether or not a shipped skill happens to be shaped so.
        val definition = SkillDefinition(
            id = "lookup",
            description = "test",
            parameters = listOf(ParamSpec("query", "aranan")),
            utterances = listOf(
                UtterancePattern(
                    template = "internette {sorgu} ara",
                    slots = listOf(SlotSpec("sorgu", param = "query", kind = SlotKind.FREE_TEXT)),
                ),
            ),
        )

        val intent = assertNotNull(RuleBasedIntentResolver(listOf(definition)).resolve("internette hava durumu ara"))

        assertEquals("lookup", intent.skillId)
        assertEquals("hava durumu", intent.params["query"])
    }

    @Test
    fun `a phrase needing the network still resolves`() = runBlocking {
        val intent = assertNotNull(resolver.resolve("hava nasıl"))

        assertEquals(SkillCatalog.Ids.WEATHER_NOW, intent.skillId)
    }

    @Test
    fun `instrument slot maps to a feed symbol`() = runBlocking {
        val dollar = assertNotNull(resolver.resolve("dolar kaç"))
        assertEquals(SkillCatalog.Ids.MARKET_QUOTE, dollar.skillId)
        assertEquals("USDTRY", dollar.params["symbol"])

        val gold = assertNotNull(resolver.resolve("gram altın ne kadar oldu"))
        assertEquals(SkillCatalog.Ids.MARKET_QUOTE, gold.skillId)
        assertEquals("GLDGR", gold.params["symbol"])

        val index = assertNotNull(resolver.resolve("borsa nasıl"))
        assertEquals(SkillCatalog.Ids.MARKET_QUOTE, index.skillId)
        assertEquals("XU100", index.params["symbol"])
    }

    @Test
    fun `the market summary is a phrase of its own`() = runBlocking {
        val intent = assertNotNull(resolver.resolve("piyasalar nasıl"))

        assertEquals(SkillCatalog.Ids.MARKET_SUMMARY, intent.skillId)
    }

    @Test
    fun `a near miss still resolves`() = runBlocking {
        // What a recogniser hands back is rarely letter perfect.
        val intent = assertNotNull(resolver.resolve("saat kac"))

        assertEquals(SkillCatalog.Ids.TIME_NOW, intent.skillId)
        assertTrue(intent.confidence < 1f)
    }

    @Test
    fun `unrelated speech is refused rather than guessed at`() = runBlocking {
        assertNull(resolver.resolve("yarın İzmir'e giden otobüs var mı acaba"))
        assertNull(resolver.resolve(""))
    }

    @Test
    fun `runtime slots match nothing until the device fills them in`() = runBlocking {
        val definition = SkillDefinition(
            id = "greet_room",
            description = "test",
            parameters = listOf(ParamSpec("room", "oda")),
            utterances = listOf(
                UtterancePattern(
                    template = "{oda} selam ver",
                    slots = listOf(SlotSpec("oda", param = "room", kind = SlotKind.RUNTIME)),
                ),
            ),
        )

        assertNull(RuleBasedIntentResolver(listOf(definition)).resolve("mutfak selam ver"))

        val filled = definition.copy(
            utterances = definition.utterances.map {
                it.withSlotValues("oda", listOf(SlotValue("mutfak", "kitchen")))
            },
        )

        val intent = assertNotNull(RuleBasedIntentResolver(listOf(filled)).resolve("mutfak selam ver"))
        assertEquals("greet_room", intent.skillId)
        assertEquals("kitchen", intent.params["room"])
    }
}
