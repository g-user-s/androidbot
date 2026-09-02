package dev.alf.nlu

import dev.alf.domain.SkillCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineVocabularyTest {

    @Test
    fun `catalog has no colliding phrases`() {
        // A collision is unrecoverable offline: the matcher only sees the phrase, so it cannot
        // tell which skill was meant.
        assertEquals(emptyMap(), OfflineVocabulary.collisions())
    }

    @Test
    fun `wake word is part of the vocabulary`() {
        val entry = OfflineVocabulary.build().first { it.skillId == OfflineVocabulary.WAKE_SKILL_ID }
        assertEquals("hey alf", entry.phrase)
    }

    @Test
    fun `free text skills contribute nothing to recognise offline`() {
        val skillIds = OfflineVocabulary.build().map { it.skillId }.toSet()

        assertTrue(SkillCatalog.Ids.SET_ALARM in skillIds)
        assertTrue(SkillCatalog.Ids.WEB_SEARCH !in skillIds)
        assertTrue(SkillCatalog.Ids.TAKE_NOTE !in skillIds)
    }

    @Test
    fun `phrases are normalised and unique`() {
        val phrases = OfflineVocabulary.build().map { it.phrase }

        assertEquals(phrases.size, phrases.toSet().size)
        assertTrue(phrases.none { it != TextNormalizer.normalize(it) })
    }

    @Test
    fun `vocabulary stays small enough to match cheaply`() {
        // Matching cost and false accepts both scale with this number; if it grows past a few
        // hundred the template matcher needs revisiting before the catalog does.
        assertTrue(OfflineVocabulary.build().size < 200, "vocabulary grew to ${OfflineVocabulary.build().size}")
    }
}
