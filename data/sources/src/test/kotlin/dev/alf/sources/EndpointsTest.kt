package dev.alf.sources

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

class EndpointsTest {

    @Test
    fun `coordinates use a dot even when the device runs in Turkish`() {
        // The device's default locale is Turkish, where the decimal separator is a comma. A URL
        // carrying "41,0138" is not the request anyone intended.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val url = Endpoints.forecast(41.0138, 28.9497)

            assertTrue("latitude=41.0138" in url, url)
            assertTrue("longitude=28.9497" in url, url)
            // Only the value itself — the query legitimately carries commas between field names.
            assertTrue(',' !in url.substringAfter("latitude=").substringBefore("&"), url)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `negative coordinates survive`() {
        val url = Endpoints.forecast(-33.8688, -151.2093)

        assertTrue("latitude=-33.8688" in url, url)
        assertTrue("longitude=-151.2093" in url, url)
    }

    @Test
    fun `city names are url encoded`() {
        val url = Endpoints.geocoding("Afyonkarahisar Merkez")

        assertTrue(' ' !in url, url)
        assertTrue("Afyonkarahisar" in url, url)
    }

    @Test
    fun `the forecast asks for both today and tomorrow`() {
        val url = Endpoints.forecast(41.0, 29.0)

        assertTrue("forecast_days=2" in url, url)
        assertTrue("current=temperature_2m,weather_code" in url, url)
    }
}
