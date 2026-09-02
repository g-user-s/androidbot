package dev.alf.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {

    private val forecast = """
        {
          "latitude": 41.0, "longitude": 29.0, "timezone": "Europe/Istanbul",
          "current": {"time": "2026-09-02T12:00", "interval": 900, "temperature_2m": 24.3, "weather_code": 2},
          "daily": {
            "time": ["2026-09-02", "2026-09-03"],
            "weather_code": [2, 63],
            "temperature_2m_max": [28.1, 25.4],
            "temperature_2m_min": [18.2, 17.0]
          }
        }
    """.trimIndent()

    private val geocoding = """
        {"results":[{"id":745044,"name":"İstanbul","latitude":41.01384,"longitude":28.94966,"country":"Türkiye"}]}
    """.trimIndent()

    @Test
    fun `current conditions are read`() {
        val current = assertNotNull(OpenMeteoParser.parseForecast(forecast).current)

        assertEquals(24.3, current.temperatureC)
        assertEquals(2, current.code)
    }

    @Test
    fun `both days are read in order`() {
        val days = OpenMeteoParser.parseForecast(forecast).days

        assertEquals(2, days.size)
        assertEquals("2026-09-03", days[1].date)
        assertEquals(25.4, days[1].maxC)
        assertEquals(17.0, days[1].minC)
        assertEquals(63, days[1].code)
    }

    @Test
    fun `a missing temperature yields no report rather than zero degrees`() {
        // Defaulting would have alf announce freezing weather in July with full confidence.
        val without = """{"current":{"time":"2026-09-02T12:00","weather_code":2}}"""

        assertNull(OpenMeteoParser.parseForecast(without).current)
    }

    @Test
    fun `an unusable payload is empty, not an exception`() {
        assertTrue(OpenMeteoParser.parseForecast("not json").isEmpty)
        assertTrue(OpenMeteoParser.parseForecast("").isEmpty)
        assertTrue(OpenMeteoParser.parseForecast("{}").isEmpty)
    }

    @Test
    fun `geocoding gives the first match`() {
        val place = assertNotNull(OpenMeteoParser.parseGeocoding(geocoding))

        assertEquals("İstanbul", place.name)
        assertEquals(41.01384, place.latitude)
        assertEquals(28.94966, place.longitude)
        assertEquals("Türkiye", place.country)
    }

    @Test
    fun `an unknown place is null`() {
        assertNull(OpenMeteoParser.parseGeocoding("""{"generationtime_ms":0.2}"""))
        assertNull(OpenMeteoParser.parseGeocoding("""{"results":[]}"""))
    }

    @Test
    fun `now is spoken with the condition`() {
        assertEquals(
            "Şu an hava 24 derece, parçalı bulutlu.",
            WeatherSpeech.now(OpenMeteoParser.parseForecast(forecast)),
        )
    }

    @Test
    fun `tomorrow is the second day, not today`() {
        assertEquals(
            "Yarın en yüksek 25 derece, en düşük 17 derece, yağmurlu.",
            WeatherSpeech.tomorrow(OpenMeteoParser.parseForecast(forecast)),
        )
    }

    @Test
    fun `below zero is spoken as a word, not a dash`() {
        val cold = WeatherReport(CurrentWeather(-3.4, code = 71), emptyList())

        assertEquals("Şu an hava eksi 3 derece, hafif kar yağışlı.", WeatherSpeech.now(cold))
    }

    @Test
    fun `an unrecognised code drops the condition instead of inventing one`() {
        val odd = WeatherReport(CurrentWeather(20.0, code = 1234), emptyList())

        assertEquals("Şu an hava 20 derece.", WeatherSpeech.now(odd))
    }

    @Test
    fun `no data means no sentence`() {
        assertNull(WeatherSpeech.now(WeatherReport(null, emptyList())))
        assertNull(WeatherSpeech.tomorrow(WeatherReport(null, emptyList())))
        assertNull(WeatherSpeech.tomorrow(OpenMeteoParser.parseForecast("""{"daily":{"time":["2026-09-02"],"temperature_2m_max":[28.0],"temperature_2m_min":[18.0],"weather_code":[2]}}""")))
    }
}
