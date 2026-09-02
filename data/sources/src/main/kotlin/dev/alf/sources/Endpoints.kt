package dev.alf.sources

import java.net.URLEncoder
import java.util.Locale

/** The addresses alf fetches from. Kept together so a moved feed is a one line change. */
object Endpoints {

    const val MARKET = "https://api.bigpara.hurriyet.com.tr/doviz/headerlist/anasayfa"

    const val NEWS_RSS = "https://feeds.bbci.co.uk/turkce/rss.xml"

    /** Resolves a place name to coordinates, so nobody has to type latitude and longitude. */
    fun geocoding(city: String, language: String = "tr"): String =
        "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${encode(city)}&count=1&language=$language&format=json"

    fun forecast(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${coordinate(latitude)}&longitude=${coordinate(longitude)}" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=2"

    /**
     * Formatted in the root locale on purpose. The device runs in Turkish, where the default
     * decimal separator is a comma, and `41,01` in a query string is not a number the service
     * will accept — it silently becomes a different request or an error.
     */
    private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
