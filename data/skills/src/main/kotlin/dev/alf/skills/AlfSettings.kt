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

    /**
     * Stored in plain preferences on purpose.
     *
     * This device is rooted by design, so anything the app can read, root can read; wrapping the
     * key in an encrypted store would add a library and imply a protection it cannot provide
     * here. The real mitigation is the key itself: issue one that is only used for this
     * assistant, and revoke it if the device is lost.
     */
    var geminiApiKey: String
        get() = preferences.getString(KEY_GEMINI, "") ?: ""
        set(value) = preferences.edit().putString(KEY_GEMINI, value.trim()).apply()

    /**
     * The model endpoints to try, best first, one per line.
     *
     * Editable because Google ships new Flash revisions often; chasing them should be a line of
     * text on the settings screen, not a new build. Empty means the built in defaults.
     */
    var geminiModels: String
        get() = preferences.getString(KEY_MODELS, "") ?: ""
        set(value) = preferences.edit().putString(KEY_MODELS, value).apply()

    fun rememberCoordinates(latitude: Double, longitude: Double) {
        preferences.edit()
            .putFloat(KEY_LATITUDE, latitude.toFloat())
            .putFloat(KEY_LONGITUDE, longitude.toFloat())
            .apply()
    }

    private companion object {
        const val KEY_CITY = "city"
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_MODELS = "gemini_models"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val DEFAULT_CITY = "İstanbul"
    }
}
