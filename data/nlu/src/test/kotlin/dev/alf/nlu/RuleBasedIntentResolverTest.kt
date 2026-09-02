package dev.alf.nlu

import dev.alf.domain.SkillCatalog
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
        val intent = assertNotNull(resolver.resolve("internette hava durumu ara"))

        assertEquals(SkillCatalog.Ids.WEB_SEARCH, intent.skillId)
        assertEquals("hava durumu", intent.params["query"])
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
        assertNull(resolver.resolve("takvim aç"))

        val withApps = SkillCatalog.definitions.map { definition ->
            if (definition.id != SkillCatalog.Ids.OPEN_APP) {
                definition
            } else {
                definition.copy(
                    utterances = definition.utterances.map {
                        it.withSlotValues(
                            "uygulama",
                            listOf(dev.alf.domain.SlotValue("takvim", "com.android.calendar")),
                        )
                    },
                )
            }
        }

        val intent = assertNotNull(RuleBasedIntentResolver(withApps).resolve("takvim aç"))
        assertEquals(SkillCatalog.Ids.OPEN_APP, intent.skillId)
        assertEquals("com.android.calendar", intent.params["package"])
    }
}
