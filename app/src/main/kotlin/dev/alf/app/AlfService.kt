package dev.alf.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.alf.audio.MicrophoneSource
import dev.alf.audio.TemplateSynthesizer
import dev.alf.audio.TurkishTts
import dev.alf.audio.WakeResponsePlayer
import dev.alf.domain.SkillCatalog
import dev.alf.domain.SkillRegistry
import dev.alf.domain.SkillResult
import dev.alf.dsp.ConversationListener
import dev.alf.dsp.FeatureSequence
import dev.alf.dsp.ListenerEvent
import dev.alf.dsp.MfccExtractor
import dev.alf.dsp.PhraseMatcher
import dev.alf.dsp.PhraseTemplate
import dev.alf.dsp.TemplateStore
import dev.alf.dsp.VadConfig
import dev.alf.dsp.VoiceSegmenter
import dev.alf.nlu.OfflineVocabulary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * The always on half of alf: microphone in, spoken answer out.
 *
 * A foreground service because that is what lets an app record while the screen is off on
 * Android 10, and it holds a partial wake lock because Doze would otherwise stop the CPU that
 * does the listening. There is no DSP path on this hardware, so this process staying awake is
 * the cost of hearing the wake word at all — see docs/PLAN.md.
 */
class AlfService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val extractor = MfccExtractor()
    private val vadConfig = VadConfig()
    private var tts: TurkishTts? = null
    private var wakeResponses: WakeResponsePlayer? = null

    /** Empty until the skill executors land; the matcher already resolves ids and parameters. */
    private val skills = SkillRegistry(emptyList())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(getString(R.string.status_starting)))
        acquireWakeLock()

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REBUILD_TEMPLATES -> templatesFile().delete()
        }

        if (listeningJob?.isActive != true) {
            listeningJob = scope.launch { listen() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        wakeResponses?.release()
        tts?.shutdown()
        releaseWakeLock()
        AlfState.set(AlfState.Phase.Stopped)
        super.onDestroy()
    }

    private suspend fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail(getString(R.string.status_no_permission))
            return
        }

        val speech = TurkishTts(this).also { tts = it }
        val status = speech.start()
        if (status == TurkishTts.Status.Unavailable) {
            fail(getString(R.string.status_no_voice))
            return
        }

        val templates = loadOrBuildTemplates(speech)
        if (templates.isEmpty()) {
            fail(getString(R.string.status_no_voice))
            return
        }

        wakeResponses = WakeResponsePlayer(File(cacheDir, "wake")).also {
            it.prepare(speech, SkillCatalog.WAKE_RESPONSES)
        }

        val (wakeTemplates, commandTemplates) =
            templates.partition { it.skillId == OfflineVocabulary.WAKE_SKILL_ID }

        val listener = ConversationListener(
            wakeMatcher = PhraseMatcher(wakeTemplates, MatcherTuning.WAKE_ACCEPT_DISTANCE),
            commandMatcher = PhraseMatcher(
                templates = commandTemplates,
                acceptDistance = MatcherTuning.COMMAND_ACCEPT_DISTANCE,
                minMargin = MatcherTuning.COMMAND_MIN_MARGIN,
            ),
            commandWindowMs = MatcherTuning.COMMAND_WINDOW_MS,
        )
        val diagnostics = PhraseMatcher(templates, acceptDistance = Double.MAX_VALUE)

        val segmenter = VoiceSegmenter(vadConfig)
        setPhase(AlfState.Phase.Listening)

        MicrophoneSource(
            sampleRate = vadConfig.sampleRate,
            frameLength = vadConfig.frameLength,
        ).frames().collect { frame ->
            val utterance = segmenter.accept(frame)
            if (utterance == null) {
                // Still lets the command window expire while nobody is speaking.
                handle(listener.onTick(System.currentTimeMillis()), speech)
                return@collect
            }

            val features = extractor.extract(utterance)
            if (MatcherTuning.LOG_RANKINGS) logRankings(diagnostics, features)
            handle(listener.onUtterance(features, System.currentTimeMillis()), speech)
        }
    }

    private suspend fun handle(event: ListenerEvent?, speech: TurkishTts) {
        when (event) {
            null -> Unit

            ListenerEvent.Woke -> {
                setPhase(AlfState.Phase.Awake)
                // Falls back to speaking when no clip could be cached.
                if (wakeResponses?.play() != true) {
                    speech.speak(SkillCatalog.WAKE_RESPONSES.random())
                }
            }

            is ListenerEvent.Command -> {
                setPhase(AlfState.Phase.Listening)
                runCommand(event, speech)
            }

            ListenerEvent.NotUnderstood -> {
                setPhase(AlfState.Phase.Listening)
                speech.speak(NOT_UNDERSTOOD)
            }

            ListenerEvent.TimedOut -> setPhase(AlfState.Phase.Listening)
        }
    }

    private suspend fun runCommand(event: ListenerEvent.Command, speech: TurkishTts) {
        val skill = skills.find(event.match.skillId)
        if (skill == null) {
            // Until the executors exist, say what was understood: it makes the matcher testable
            // on the device without waiting for the rest of the assistant.
            Log.i(TAG, "matched '${event.match.phrase}' -> ${event.match.skillId} ${event.match.params}")
            speech.speak("${event.match.phrase}, anlaşıldı")
            return
        }

        val reply = runCatching { skill.execute(event.match.params) }
            .getOrElse { SkillResult.Failed(it.toString(), SOMETHING_WENT_WRONG) }

        when (reply) {
            is SkillResult.Spoken -> speech.speak(reply.text)
            is SkillResult.Failed -> speech.speak(reply.spoken)
            SkillResult.Silent -> Unit
        }
    }

    private suspend fun loadOrBuildTemplates(speech: TurkishTts): List<PhraseTemplate> {
        val file = templatesFile()
        if (file.exists()) {
            runCatching { file.inputStream().use { TemplateStore.read(it) } }
                .onSuccess { if (it.isNotEmpty()) return it }
                .onFailure { Log.w(TAG, "template file unreadable, rebuilding", it) }
        }

        setPhase(AlfState.Phase.BuildingTemplates)
        val built = TemplateSynthesizer(speech, File(cacheDir, "synth")).build(
            entries = OfflineVocabulary.build(),
            voices = speech.offlineTurkishVoices(),
        ) { progress ->
            setPhase(AlfState.Phase.BuildingTemplates, "${progress.done}/${progress.total}")
        }

        if (built.isNotEmpty()) {
            runCatching { file.outputStream().use { TemplateStore.write(built, it) } }
                .onFailure { Log.w(TAG, "could not cache templates", it) }
        }
        return built
    }

    private fun logRankings(diagnostics: PhraseMatcher, features: FeatureSequence) {
        val ranked = diagnostics.rank(features).take(MatcherTuning.RANKINGS_LOGGED)
        Log.d(TAG, "capture ranked: " + ranked.joinToString { "${it.first}=%.3f".format(it.second) })
    }

    private fun templatesFile() = File(filesDir, "templates.alf")

    private fun setPhase(phase: AlfState.Phase, detail: String = "") {
        AlfState.set(phase, detail)
        notificationManager().notify(NOTIFICATION_ID, notification(describe(phase, detail)))
    }

    private fun fail(reason: String) {
        Log.w(TAG, "listener stopped: $reason")
        AlfState.set(AlfState.Phase.Failed, reason)
        notificationManager().notify(NOTIFICATION_ID, notification(reason))
    }

    private fun describe(phase: AlfState.Phase, detail: String): String {
        val base = when (phase) {
            AlfState.Phase.Stopped -> getString(R.string.status_stopped)
            AlfState.Phase.Starting -> getString(R.string.status_starting)
            AlfState.Phase.BuildingTemplates -> getString(R.string.status_building)
            AlfState.Phase.Listening -> getString(R.string.status_listening)
            AlfState.Phase.Awake -> getString(R.string.status_awake)
            AlfState.Phase.Failed -> getString(R.string.status_stopped)
        }
        return if (detail.isEmpty()) base else "$base $detail"
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.channel_description) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "AlfService"
        private const val CHANNEL_ID = "alf.listening"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "alf:listening"
        private const val NOT_UNDERSTOOD = "Bunu anlayamadım."
        private const val SOMETHING_WENT_WRONG = "Bunu yaparken bir sorun çıktı."

        const val ACTION_STOP = "dev.alf.app.STOP"
        const val ACTION_REBUILD_TEMPLATES = "dev.alf.app.REBUILD_TEMPLATES"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, AlfService::class.java).apply { this.action = action }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            start(context, ACTION_STOP)
        }
    }
}
