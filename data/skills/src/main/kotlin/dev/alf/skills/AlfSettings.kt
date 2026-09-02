package dev.alf.skills

import android.content.Context

/**
 * The handful of things that differ between one installation and the next.
 *
 * The location is stored as coordinates once resolved, so the geocoding lookup happens on the
 * first weather question and never again — this device does not move.
 */
class AlfSettings(context: Context) {

    private val preferences = context.getSharedPreferences("alf", Context.MODE_PRIVATE)

    var city: String
        get() = preferences.getString(KEY_CITY, DEFAULT_CITY) ?: DEFAULT_CITY
        set(value) = preferences.edit().putString(KEY_CITY, value).remove(KEY_LATITUDE).remove(KEY_LONGITUDE).apply()

    /** Null until the city has been resolved. */
    val coordinates: Pair<Double, Double>?
        get() {
            if (!preferences.contains(KEY_LATITUDE) || !preferences.contains(KEY_LONGITUDE)) return null
            return preferences.getFloat(KEY_LATITUDE, 0f).toDouble() to
                preferences.getFloat(KEY_LONGITUDE, 0f).toDouble()
        }

    fun rememberCoordinates(latitude: Double, longitude: Double) {
        preferences.edit()
            .putFloat(KEY_LATITUDE, latitude.toFloat())
            .putFloat(KEY_LONGITUDE, longitude.toFloat())
            .apply()
    }

    private companion object {
        const val KEY_CITY = "city"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val DEFAULT_CITY = "İstanbul"
    }
}
