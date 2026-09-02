package dev.alf.skills

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.alf.domain.SkillRegistry

/**
 * Builds the set of things alf can actually do.
 *
 * Every entry here has a matching definition in the catalog, and the catalog is what the matcher
 * and the resolver are built from — so a skill added in one place and forgotten in the other
 * shows up immediately as a command that is heard but does nothing.
 */
object AlfSkills {

    fun registry(context: Context, http: HttpFetcher = HttpFetcher()): SkillRegistry {
        val application = context.applicationContext
        val settings = AlfSettings(application)
        val weather = WeatherFetcher(http, settings)

        return SkillRegistry(
            listOf(
                TimeNowSkill(),
                DateTodaySkill(),
                BatteryLevelSkill(application),
                SetAlarmSkill(application),
                SetTimerSkill(application),
                SetVolumeSkill(application),
                CancelSkill(),
                TakeNoteSkill(application),
                MarketQuoteSkill(http),
                MarketSummarySkill(http),
                NewsHeadlinesSkill(http),
                WeatherNowSkill(weather),
                WeatherTomorrowSkill(weather),
            ),
        )
    }

    /**
     * Whether a request has any chance of getting through.
     *
     * Checked before running a skill that needs the network so alf can say it has no connection.
     * Without this the same situation would surface as a failed fetch and a vaguer message, and
     * "I have no connection" is the one the listener can act on.
     */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
