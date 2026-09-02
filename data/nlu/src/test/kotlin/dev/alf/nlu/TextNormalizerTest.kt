package dev.alf.nlu

import kotlin.test.Test
import kotlin.test.assertEquals

class TextNormalizerTest {

    @Test
    fun `lowercasing follows Turkish rules`() {
        // The default locale would turn these into "isigi" style mush and stop them matching.
        assertEquals("ışığı aç", TextNormalizer.normalize("IŞIĞI AÇ"))
        assertEquals("istanbul", TextNormalizer.normalize("İSTANBUL"))
    }

    @Test
    fun `apostrophes are dropped rather than split`() {
        assertEquals("alarmı 7ye kur", TextNormalizer.normalize("Alarmı 7'ye kur"))
        assertEquals("alarmı 7ye kur", TextNormalizer.normalize("Alarmı 7’ye kur"))
    }

    @Test
    fun `punctuation and spacing collapse`() {
        assertEquals("saat kaç", TextNormalizer.normalize("  Saat kaç?  "))
        assertEquals("hey alf saat kaç", TextNormalizer.normalize("Hey alf, saat kaç!"))
    }

    @Test
    fun `digits survive`() {
        assertEquals("15 dakika zamanlayıcı kur", TextNormalizer.normalize("15 dakika zamanlayıcı kur"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", TextNormalizer.normalize("   ...   "))
    }
}
