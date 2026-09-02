package dev.alf.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DateTimeSpeechTest {

    @Test
    fun `time is zero padded`() {
        assertEquals("Saat 09:05.", DateTimeSpeech.time(9, 5))
        assertEquals("Saat 14:35.", DateTimeSpeech.time(14, 35))
        assertEquals("Saat 00:00.", DateTimeSpeech.time(0, 0))
    }

    @Test
    fun `dates use written out Turkish names`() {
        assertEquals("Bugün 2 Eylül 2026, Çarşamba.", DateTimeSpeech.date(2, 9, 2026, 3))
        assertEquals("Bugün 1 Ocak 2027, Cuma.", DateTimeSpeech.date(1, 1, 2027, 5))
        assertEquals("Bugün 31 Aralık 2026, Pazar.", DateTimeSpeech.date(31, 12, 2026, 7))
    }

    @Test
    fun `battery mentions charging only when it is`() {
        assertEquals("Pil yüzde 85.", DateTimeSpeech.battery(85, charging = false))
        assertEquals("Pil yüzde 85, şarj oluyor.", DateTimeSpeech.battery(85, charging = true))
    }

    @Test
    fun `nonsense input is a programming error`() {
        assertFailsWith<IllegalArgumentException> { DateTimeSpeech.time(24, 0) }
        assertFailsWith<IllegalArgumentException> { DateTimeSpeech.date(1, 13, 2026, 1) }
        assertFailsWith<IllegalArgumentException> { DateTimeSpeech.date(1, 1, 2026, 8) }
    }
}

class AlarmTimeTest {

    @Test
    fun `the morning is chosen when it is still ahead`() {
        assertEquals(7, AlarmTime.nextOccurrence(hour12 = 7, currentHour = 6, currentMinute = 30))
    }

    @Test
    fun `the evening is chosen once the morning has passed`() {
        assertEquals(19, AlarmTime.nextOccurrence(hour12 = 7, currentHour = 10, currentMinute = 0))
    }

    @Test
    fun `after both have passed it wraps to tomorrow morning`() {
        assertEquals(7, AlarmTime.nextOccurrence(hour12 = 7, currentHour = 22, currentMinute = 0))
    }

    @Test
    fun `twelve means noon before it passes and midnight after`() {
        assertEquals(0, AlarmTime.nextOccurrence(hour12 = 12, currentHour = 23, currentMinute = 0))
        assertEquals(12, AlarmTime.nextOccurrence(hour12 = 12, currentHour = 9, currentMinute = 0))
    }

    @Test
    fun `the current hour itself counts as passed`() {
        // Asked at 07:15 for "seven", the speaker cannot mean seven fifteen minutes ago.
        assertEquals(19, AlarmTime.nextOccurrence(hour12 = 7, currentHour = 7, currentMinute = 15))
    }

    @Test
    fun `the spoken confirmation names the real hour`() {
        assertEquals("Alarm 19:00 için kuruldu.", AlarmTime.spoken(19))
        assertEquals("Alarm 07:00 için kuruldu.", AlarmTime.spoken(7))
    }

    @Test
    fun `an hour off the twelve hour clock is a programming error`() {
        assertFailsWith<IllegalArgumentException> { AlarmTime.nextOccurrence(13, 8, 0) }
        assertFailsWith<IllegalArgumentException> { AlarmTime.nextOccurrence(0, 8, 0) }
    }
}
