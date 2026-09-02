package dev.alf.skills

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.util.Log
import dev.alf.domain.Skill
import dev.alf.domain.SkillCatalog
import dev.alf.domain.SkillDefinition
import dev.alf.domain.SkillResult
import dev.alf.sources.AlarmTime
import dev.alf.sources.DateTimeSpeech
import java.io.File
import java.time.LocalDateTime

internal fun definitionOf(id: String): SkillDefinition =
    SkillCatalog.definitions.first { it.id == id }

internal class TimeNowSkill(private val clock: () -> LocalDateTime = LocalDateTime::now) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.TIME_NOW)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val now = clock()
        return SkillResult.Spoken(DateTimeSpeech.time(now.hour, now.minute))
    }
}

internal class DateTodaySkill(private val clock: () -> LocalDateTime = LocalDateTime::now) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.DATE_TODAY)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val now = clock()
        return SkillResult.Spoken(
            DateTimeSpeech.date(now.dayOfMonth, now.monthValue, now.year, now.dayOfWeek.value),
        )
    }
}

internal class BatteryLevelSkill(private val context: Context) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.BATTERY_LEVEL)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val manager = context.getSystemService(BatteryManager::class.java)
            ?: return SkillResult.Failed("no BatteryManager", CANNOT_READ_BATTERY)
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent !in 0..100) return SkillResult.Failed("capacity $percent", CANNOT_READ_BATTERY)
        return SkillResult.Spoken(DateTimeSpeech.battery(percent, manager.isCharging))
    }

    private companion object {
        const val CANNOT_READ_BATTERY = "Pil durumunu okuyamadım."
    }
}

/**
 * Hands the alarm to whatever clock app the device has.
 *
 * Android 10 blocks an app in the background from starting an activity, and this runs from a
 * service with no window. Installed under `/system/priv-app` and holding `SYSTEM_ALERT_WINDOW`
 * the app is exempt, which is how this device is provisioned; on an ordinary install the start
 * is refused and the failure is spoken rather than swallowed.
 */
internal class SetAlarmSkill(
    private val context: Context,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.SET_ALARM)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val hour12 = params["hour"]?.toIntOrNull()
            ?: return SkillResult.Failed("no hour in $params", "Saati anlayamadım.")
        if (hour12 !in 1..12) return SkillResult.Failed("hour $hour12", "Saati anlayamadım.")

        val now = clock()
        val hour24 = AlarmTime.nextOccurrence(hour12, now.hour, now.minute)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, 0)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

        return startOrFail(context, intent, "Alarmı kuramadım.") {
            SkillResult.Spoken(AlarmTime.spoken(hour24))
        }
    }
}

internal class SetTimerSkill(private val context: Context) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.SET_TIMER)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val minutes = params["minutes"]?.toIntOrNull()
            ?: return SkillResult.Failed("no minutes in $params", "Süreyi anlayamadım.")
        if (minutes !in 1..24 * 60) return SkillResult.Failed("minutes $minutes", "Süreyi anlayamadım.")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

        return startOrFail(context, intent, "Zamanlayıcıyı kuramadım.") {
            val spoken = if (minutes % 60 == 0) "${minutes / 60} saatlik" else "$minutes dakikalık"
            SkillResult.Spoken("$spoken zamanlayıcı kuruldu.")
        }
    }
}

internal class SetVolumeSkill(private val context: Context) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.SET_VOLUME)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return SkillResult.Failed("no AudioManager", "Ses seviyesini değiştiremedim.")

        val adjustment = when (params["direction"]) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            else -> return SkillResult.Failed("direction ${params["direction"]}", "Anlayamadım.")
        }

        return runCatching {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, 0)
            // The change is audible; saying so as well would be noise.
            SkillResult.Silent
        }.getOrElse { SkillResult.Failed(it.toString(), "Ses seviyesini değiştiremedim.") }
    }
}

/** Stops whatever alf was saying. The listener has already closed its window by this point. */
internal class CancelSkill : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.CANCEL)

    override suspend fun execute(params: Map<String, String>): SkillResult = SkillResult.Silent
}

internal class TakeNoteSkill(private val context: Context, private val clock: () -> LocalDateTime = LocalDateTime::now) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.TAKE_NOTE)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val text = params["text"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SkillResult.Failed("no text in $params", "Not içeriğini anlayamadım.")

        return runCatching {
            val now = clock()
            File(context.filesDir, "notes.txt").appendText(
                "%04d-%02d-%02d %02d:%02d\t%s\n".format(
                    now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, text,
                ),
            )
            SkillResult.Spoken("Not aldım.")
        }.getOrElse { SkillResult.Failed(it.toString(), "Notu kaydedemedim.") }
    }
}

private inline fun startOrFail(
    context: Context,
    intent: Intent,
    spokenOnFailure: String,
    onSuccess: () -> SkillResult,
): SkillResult = try {
    context.startActivity(intent)
    onSuccess()
} catch (e: Exception) {
    Log.w("AlfSkills", "could not start ${intent.action}", e)
    SkillResult.Failed(e.toString(), spokenOnFailure)
}
