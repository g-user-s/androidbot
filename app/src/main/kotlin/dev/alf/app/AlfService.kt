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
import dev.alf.llm.GeminiClient
import dev.alf.llm.GeminiReply
import dev.alf.llm.GeminiRequest
import dev.alf.llm.ModelChain
import dev.alf.llm.Models
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
import dev.alf.skills.AlfSettings
import dev.alf.skills.AlfSkills
import dev.alf.skills.GeminiTransportHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

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

    private val skills: SkillRegistry by lazy { AlfSkills.registry(this) }
    private val settings: AlfSettings by lazy { AlfSettings(this) }

    /**
     * Built once so the chain remembers which models are spent for the day. Absent until a key is
     * configured — without one the assistant is simply the offline one, which still works.
     */
    private val gemini: GeminiClient? by lazy {
        settings.geminiApiKey.takeIf { it.isNotBlank() }?.let { key ->
            val configured = Models.parse(settings.geminiModels)
            val chain = ModelChain(
                models = configured.ifEmpty { Models.DEFAULT_CHAIN },
                today = { LocalDate.now().toEpochDay() },
            )
            Log.i(TAG, "model chain: " + chain.available().joinToString { it.id })
            GeminiClient(chain, GeminiTransportHttp(key))
        }
    }

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
            setPhase(AlfState.Phase.Starting)
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
        // MissingVoice fails too: without a Turkish voice the templates would be synthesised in
        // whatever language the default voice speaks, which is worse than not starting.
        if (speech.start() != TurkishTts.Status.Ready) {
            fail(getString(R.string.status_no_voice))
            return
        }

        val templates = loadOrBuildTemplates(speech)
        if (templates.isEmpty()) {
            fail(getString(R.string.status_no_voice))
            return
        }

        wakeResponses = WakeResponsePlayer(this).also {
            it.prepare(speech, SkillCatalog.WAKE_RESPONSES)
            Log.i(TAG, "wake clips from ${it.source}")
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
        var suppressMicrophoneUntilMs = 0L
        setPhase(AlfState.Phase.Listening)

        MicrophoneSource(
            sampleRate = vadConfig.sampleRate,
            frameLength = vadConfig.frameLength,
        ).frames().collect { frame ->
            val nowMs = System.currentTimeMillis()
            // SoundPool playback is asynchronous. Without this guard Alf records its own wake
            // response and immediately mistakes "efendim" for the user's command.
            if (nowMs < suppressMicrophoneUntilMs) {
                segmenter.reset()
                return@collect
            }

            val utterance = segmenter.accept(frame)
            if (utterance == null) {
                // Still lets the command window expire while nobody is speaking.
                handle(listener.onTick(nowMs), speech, utterance = null)
                return@collect
            }

            val features = extractor.extract(utterance)
            if (MatcherTuning.LOG_RANKINGS) logRankings(diagnostics, features)
            val event = listener.onUtterance(features, nowMs)
            handle(event, speech, utterance)
            if (event == ListenerEvent.Woke) {
                segmenter.reset()
                suppressMicrophoneUntilMs = System.currentTimeMillis() + WAKE_RESPONSE_COOLDOWN_MS
            }
        }
    }

    private suspend fun handle(event: ListenerEvent?, speech: TurkishTts, utterance: FloatArray?) {
        when (event) {
            null -> Unit

            ListenerEvent.Woke -> {
                Log.i(TAG, "wake accepted")
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
                Log.i(TAG, "command not understood locally; trying model fallback")
                setPhase(AlfState.Phase.Listening)
                // The local vocabulary did not have it. Anything beyond that needs the model.
                if (utterance == null || !askGemini(utterance, speech)) speech.speak(NOT_UNDERSTOOD)
            }

            ListenerEvent.TimedOut -> {
                Log.i(TAG, "command window timed out")
                setPhase(AlfState.Phase.Listening)
            }
        }
    }

    private suspend fun runCommand(event: ListenerEvent.Command, speech: TurkishTts) {
        Log.i(TAG, "matched '${event.match.phrase}' -> ${event.match.skillId} ${event.match.params}")
        runSkill(event.match.skillId, event.match.params, speech)
    }

    private suspend fun runSkill(skillId: String, params: Map<String, String>, speech: TurkishTts) {
        val skill = skills.find(skillId)
        if (skill == null) {
            Log.w(TAG, "no executor for $skillId")
            speech.speak(NOT_UNDERSTOOD)
            return
        }

        // A phrase alf can hear but not answer offline: saying so is very different from
        // claiming not to have understood, and only one of the two is true.
        if (skill.definition.requiresNetwork && !AlfSkills.isOnline(this)) {
            speech.speak(NO_CONNECTION)
            return
        }

        val reply = runCatching { skill.execute(params) }
            .getOrElse { SkillResult.Failed(it.toString(), SOMETHING_WENT_WRONG) }

        when (reply) {
            is SkillResult.Spoken -> speech.speak(reply.text)
            is SkillResult.Failed -> speech.speak(reply.spoken)
            SkillResult.Silent -> Unit
        }
    }

    /**
     * The fallback for whatever the fixed vocabulary cannot cover.
     *
     * The captured audio goes up as it is, with the skills offered as callable functions, so one
     * round trip covers both understanding the speech and deciding what to do about it. Returns
     * false when nothing was said back, leaving the caller to give the ordinary "I did not
     * understand" — the honest answer when there is no model to ask.
     */
    private suspend fun askGemini(utterance: FloatArray, speech: TurkishTts): Boolean {
        val client = gemini ?: return false
        if (!AlfSkills.isOnline(this)) {
            speech.speak(NO_CONNECTION)
            return true
        }

        val request = runCatching {
            GeminiRequest.forAudio(utterance.toWavBase64(vadConfig.sampleRate), skills.definitions)
        }.getOrElse {
            Log.w(TAG, "could not package the utterance", it)
            return false
        }

        return when (val reply = client.ask(request)) {
            is GeminiReply.CallSkill -> {
                Log.i(TAG, "model chose ${reply.skillId} ${reply.arguments}")
                runSkill(reply.skillId, reply.arguments, speech)
                true
            }
            is GeminiReply.Spoken -> {
                speech.speak(reply.text)
                true
            }
            GeminiReply.QuotaExhausted -> {
                speech.speak(QUOTA_SPENT)
                true
            }
            is GeminiReply.Failed -> {
                Log.w(TAG, "model failed: ${reply.reason}")
                false
            }
        }
    }

    /**
     * Templates come from the apk when the build ships them, and are only synthesised on the
     * device as a fallback. Shipped templates are produced by a much better voice than this
     * hardware has, and they also spare a slow first boot: see tools/voicegen.
     */
    private suspend fun loadOrBuildTemplates(speech: TurkishTts): List<PhraseTemplate> {
        val file = templatesFile()
        if (file.exists()) {
            runCatching { file.inputStream().use { TemplateStore.read(it) } }
                .onSuccess { if (it.isNotEmpty()) return it }
                .onFailure { Log.w(TAG, "cached template file unreadable, rebuilding", it) }
        }

        runCatching { assets.open(TEMPLATES_ASSET).use { TemplateStore.read(it) } }
            .onSuccess {
                if (it.isNotEmpty()) {
                    Log.i(TAG, "loaded ${it.size} shipped templates")
                    return it
                }
            }
            .onFailure { Log.i(TAG, "no shipped templates, falling back to the device engine") }

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
        val all = diagnostics.rank(features)
        val ranked = all.take(MatcherTuning.RANKINGS_LOGGED)
        val wake = all.firstOrNull { it.first == SkillCatalog.WAKE_WORD }?.second
        val wakeScore = wake?.let { "%.3f".format(it) } ?: "missing"
        Log.d(
            TAG,
            "capture wake=$wakeScore ranked: " +
                ranked.joinToString { "${it.first}=%.3f".format(it.second) },
        )
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
        private const val WAKE_RESPONSE_COOLDOWN_MS = 1_800L
        private const val TEMPLATES_ASSET = "templates.alf"
        private const val NOT_UNDERSTOOD = "Bunu anlayamadım."
        private const val NO_CONNECTION = "Şu an internetim yok."
        private const val QUOTA_SPENT = "Bugünlük soru hakkım doldu, yarın tekrar deneyin."
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
