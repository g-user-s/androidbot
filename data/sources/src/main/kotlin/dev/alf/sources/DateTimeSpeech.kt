package dev.alf.sources

/**
 * Dates, times and alarm arithmetic, as Turkish sentences.
 *
 * The month and day names are written out rather than taken from the platform's locale data,
 * which varies between Android versions and vendor builds; a wrong month name is the kind of
 * thing nobody notices until the device says it out loud.
 */
object DateTimeSpeech {

    private val MONTHS = listOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık",
    )

    /** Indexed the way `java.time.DayOfWeek` numbers them: Monday is 1. */
    private val DAYS = listOf(
        "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar",
    )

    fun time(hour: Int, minute: Int): String {
        require(hour in 0..23 && minute in 0..59) { "invalid time $hour:$minute" }
        return "Saat %02d:%02d.".format(hour, minute)
    }

    fun date(dayOfMonth: Int, month: Int, year: Int, dayOfWeek: Int): String {
        require(month in 1..12) { "invalid month $month" }
        require(dayOfWeek in 1..7) { "invalid day of week $dayOfWeek" }
        return "Bugün $dayOfMonth ${MONTHS[month - 1]} $year, ${DAYS[dayOfWeek - 1]}."
    }

    fun battery(percent: Int, charging: Boolean): String =
        if (charging) "Pil yüzde $percent, şarj oluyor." else "Pil yüzde $percent."
}

/**
 * Works out which o'clock the speaker meant.
 *
 * Commands name a twelve hour clock — "alarmı yediye kur" — while the alarm wants 0 to 23. Seven
 * in the morning and seven in the evening are both plausible, so the rule is the one a person
 * would assume: whichever comes first. Asking at six in the morning gets 07:00 today; asking at
 * ten gets 19:00 the same evening.
 */
object AlarmTime {

    fun nextOccurrence(hour12: Int, currentHour: Int, currentMinute: Int): Int {
        require(hour12 in 1..12) { "hour must be on a twelve hour clock, was $hour12" }
        require(currentHour in 0..23 && currentMinute in 0..59) { "invalid current time" }

        val morning = hour12 % 12
        val evening = morning + 12
        val nowMinutes = currentHour * 60 + currentMinute

        return when {
            morning * 60 > nowMinutes -> morning
            evening * 60 > nowMinutes -> evening
            // Both have passed today, so the next one is tomorrow morning.
            else -> morning
        }
    }

    fun spoken(hour24: Int): String = "Alarm %02d:00 için kuruldu.".format(hour24)
}
