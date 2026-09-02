package dev.alf.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.alf.skills.AlfSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The always-visible face of Alf, with the developer controls tucked behind the menu. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var settingsPanel: View
    private lateinit var apiKey: EditText
    private lateinit var models: EditText
    private lateinit var city: EditText
    private var scope: CoroutineScope? = null
    private val settings by lazy { AlfSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        setContentView(buildLayout())
        requestMicrophoneIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { this.scope = it }
        scope.launch {
            combine(AlfState.phase, AlfState.detail) { phase, detail -> phase to detail }
                .collect { (phase, detail) -> status.text = describe(phase, detail) }
        }
        scope.launch {
            while (isActive) {
                updateClock()
                delay(30_000)
            }
        }
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    @Suppress("SetTextI18n")
    private fun buildLayout(): ViewGroup {
        val root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            isFocusableInTouchMode = true
            requestFocus()
        }

        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(20), dp(28), dp(42))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { settingsPanel.visibility = View.VISIBLE }
        }, LinearLayout.LayoutParams(dp(82), dp(82)))
        clock = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        header.addView(clock, LinearLayout.LayoutParams(0, dp(82), 1f))
        home.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))

        home.addView(ImageView(this).apply {
            setImageResource(R.drawable.alf_mascot)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = getString(R.string.app_name)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            text = getString(R.string.status_stopped)
            textSize = 31f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(20))
        }
        home.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(94)))

        home.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(Color.WHITE)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply {
            marginStart = dp(130)
            marginEnd = dp(130)
        })

        root.addView(home, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        settingsPanel = buildSettingsPanel()
        root.addView(settingsPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return root
    }

    private fun buildSettingsPanel(): View {
        val padding = dp(24)
        apiKey = field(R.string.hint_api_key, settings.geminiApiKey).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        models = field(R.string.hint_models, settings.geminiModels, lines = 4)
        city = field(R.string.hint_city, settings.city)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.settings_title)
                textSize = 28f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(button(R.string.action_close) { settingsPanel.visibility = View.GONE })
            addView(button(R.string.action_start) { AlfService.start(this@MainActivity) })
            addView(button(R.string.action_stop) { AlfService.stop(this@MainActivity) })
            addView(button(R.string.action_rebuild) {
                AlfService.start(this@MainActivity, AlfService.ACTION_REBUILD_TEMPLATES)
            })
            addView(apiKey)
            addView(models)
            addView(city)
            addView(button(R.string.action_save) { save() })
        }

        return ScrollView(this).apply {
            setBackgroundColor(SETTINGS_BACKGROUND)
            isFillViewport = true
            visibility = View.GONE
            addView(column)
        }
    }

    private fun save() {
        settings.geminiApiKey = apiKey.text.toString()
        settings.geminiModels = models.text.toString()
        city.text.toString().trim().takeIf { it.isNotEmpty() && it != settings.city }
            ?.let { settings.city = it }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun field(hintRes: Int, value: String, lines: Int = 1) = EditText(this).apply {
        setHint(hintRes)
        setHintTextColor(HINT)
        setTextColor(Color.WHITE)
        setText(value)
        setLines(lines)
        isSingleLine = lines == 1
    }

    private fun button(labelRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(labelRes)
        setOnClickListener { onClick() }
    }

    private fun describe(phase: AlfState.Phase, detail: String): String {
        val base = when (phase) {
            AlfState.Phase.Stopped -> getString(R.string.status_stopped)
            AlfState.Phase.Starting -> getString(R.string.status_starting)
            AlfState.Phase.BuildingTemplates -> getString(R.string.status_building)
            AlfState.Phase.Listening -> getString(R.string.status_listening)
            AlfState.Phase.Awake -> getString(R.string.status_awake)
            AlfState.Phase.Failed -> getString(R.string.status_failed)
        }
        return if (detail.isEmpty()) base else "$base $detail"
    }

    private fun updateClock() {
        val locale = Locale("tr", "TR")
        clock.text = LocalDateTime.now().format(CLOCK_FORMAT).uppercase(locale)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requestMicrophoneIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private companion object {
        val CLOCK_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM EEEE | HH:mm", Locale("tr", "TR"))
        val BACKGROUND: Int = Color.rgb(87, 139, 155)
        val SETTINGS_BACKGROUND: Int = Color.rgb(33, 54, 76)
        val HINT: Int = Color.rgb(180, 195, 205)
    }
}
