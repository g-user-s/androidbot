package dev.alf.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

data class GeoLocation(val name: String, val latitude: Double, val longitude: Double, val country: String?)

data class CurrentWeather(val temperatureC: Double, val code: Int)

data class DailyForecast(val date: String, val code: Int, val maxC: Double, val minC: Double)

data class WeatherReport(val current: CurrentWeather?, val days: List<DailyForecast>) {
    val isEmpty: Boolean get() = current == null && days.isEmpty()
}

/**
 * Reads the forecast service's response.
 *
 * Field names follow the service's documented shape. Every read is optional: if the shape ever
 * changes, the report comes back empty and alf says it could not get the weather — which is the
 * right failure. Defaulting a missing temperature to zero would have it confidently announce
 * freezing weather in July.
 */
object OpenMeteoParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseForecast(payload: String): WeatherReport {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return WeatherReport(null, emptyList())

        val current = (root["current"] as? JsonObject)?.let { block ->
            val temperature = number(block, "temperature_2m") ?: return@let null
            CurrentWeather(temperature, number(block, "weather_code")?.toInt() ?: UNKNOWN_CODE)
        }

        val daily = (root["daily"] as? JsonObject)
        val dates = daily?.get("time") as? JsonArray
        val codes = daily?.get("weather_code") as? JsonArray
        val highs = daily?.get("temperature_2m_max") as? JsonArray
        val lows = daily?.get("temperature_2m_min") as? JsonArray

        val days = if (dates == null || highs == null || lows == null) {
            emptyList()
        } else {
            dates.indices.mapNotNull { index ->
                val high = numberAt(highs, index) ?: return@mapNotNull null
                val low = numberAt(lows, index) ?: return@mapNotNull null
                DailyForecast(
                    date = (dates.getOrNull(index) as? JsonPrimitive)?.content.orEmpty(),
                    code = numberAt(codes, index)?.toInt() ?: UNKNOWN_CODE,
                    maxC = high,
                    minC = low,
                )
            }
        }

        return WeatherReport(current, days)
    }

    fun parseGeocoding(payload: String): GeoLocation? {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val latitude = number(first, "latitude") ?: return null
        val longitude = number(first, "longitude") ?: return null
        return GeoLocation(
            name = (first["name"] as? JsonPrimitive)?.content.orEmpty(),
            latitude = latitude,
            longitude = longitude,
            country = (first["country"] as? JsonPrimitive)?.content,
        )
    }

    private fun number(container: JsonObject, key: String): Double? =
        (container[key] as? JsonPrimitive)?.let { if (it.isString) TurkishNumbers.parse(it.content) else it.content.toDoubleOrNull() }

    private fun numberAt(array: JsonArray?, index: Int): Double? =
        (array?.getOrNull(index) as? JsonPrimitive)?.content?.toDoubleOrNull()

    const val UNKNOWN_CODE = -1
}

/** WMO weather codes, as they should be spoken in Turkish. */
object WeatherCodes {

    fun describe(code: Int): String? = when (code) {
        0 -> "açık"
        1 -> "az bulutlu"
        2 -> "parçalı bulutlu"
        3 -> "çok bulutlu"
        45, 48 -> "sisli"
        51, 53, 55 -> "çiseleyen yağmurlu"
        56, 57 -> "dondurucu çiseleyen yağmurlu"
        61 -> "hafif yağmurlu"
        63 -> "yağmurlu"
        65 -> "şiddetli yağmurlu"
        66, 67 -> "dondurucu yağmurlu"
        71 -> "hafif kar yağışlı"
        73 -> "kar yağışlı"
        75 -> "yoğun kar yağışlı"
        77 -> "kar taneli"
        80, 81 -> "sağanak yağışlı"
        82 -> "kuvvetli sağanak yağışlı"
        85, 86 -> "kar sağanaklı"
        95 -> "gök gürültülü fırtınalı"
        96, 99 -> "dolu ve gök gürültülü fırtınalı"
        else -> null
    }
}

/**
 * Weather as spoken sentences.
 *
 * The city is deliberately left out. Turkish would need it in the locative — "Ankara'da",
 * "İstanbul'da", "Uşak'ta" — and the suffix depends on vowel harmony and on whether the final
 * consonant is voiced, so getting it right for arbitrary place names is a real piece of
 * morphology. On a device that sits in one house, the listener already knows where they are.
 */
object WeatherSpeech {

    fun now(report: WeatherReport): String? {
        val current = report.current ?: return null
        val condition = WeatherCodes.describe(current.code)
        val temperature = degrees(current.temperatureC)
        return if (condition == null) "Şu an hava $temperature." else "Şu an hava $temperature, $condition."
    }

    fun tomorrow(report: WeatherReport): String? {
        // The service is asked for two days; the second one is tomorrow.
        val tomorrow = report.days.getOrNull(1) ?: return null
        val condition = WeatherCodes.describe(tomorrow.code)
        val range = "en yüksek ${degrees(tomorrow.maxC)}, en düşük ${degrees(tomorrow.minC)}"
        return if (condition == null) "Yarın $range." else "Yarın $range, $condition."
    }

    private fun degrees(celsius: Double): String {
        val rounded = celsius.roundToInt()
        // "-3 derece" is read as a dash by some engines; the word is unambiguous.
        return if (rounded < 0) "eksi ${-rounded} derece" else "$rounded derece"
    }
}
