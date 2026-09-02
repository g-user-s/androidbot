package dev.alf.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.alf.skills.AlfSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A control panel, not the assistant.
 *
 * alf is meant to be used with the screen off, so this screen only exists to grant the microphone
 * permission, start and stop the listener, and rebuild the reference templates after the
 * vocabulary changes. It is written in views rather than Compose deliberately: this device has
 * 2 GB of RAM and a weak CPU, and there is no interface here worth paying a toolkit for.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var apiKey: EditText
    private lateinit var models: EditText
    private lateinit var city: EditText
    private var scope: CoroutineScope? = null
    private val settings by lazy { AlfSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        requestMicrophoneIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { this.scope = it }
        scope.launch {
            combine(AlfState.phase, AlfState.detail) { phase, detail -> phase to detail }
                .collect { (phase, detail) ->
                    status.text = if (detail.isEmpty()) phase.name else "${phase.name} · $detail"
                }
        }
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    private fun buildLayout(): ViewGroup {
        val padding = (24 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            text = getString(R.string.status_stopped)
        }

        apiKey = field(R.string.hint_api_key, settings.geminiApiKey)
        // Several lines: the chain is tried in order, so it is a list, not a single choice.
        models = field(R.string.hint_models, settings.geminiModels, lines = 4)
        city = field(R.string.hint_city, settings.city)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(status)
            addView(button(R.string.action_start) { AlfService.start(this@MainActivity) })
            addView(button(R.string.action_stop) { AlfService.stop(this@MainActivity) })
            addView(
                button(R.string.action_rebuild) {
                    AlfService.start(this@MainActivity, AlfService.ACTION_REBUILD_TEMPLATES)
                },
            )
            addView(apiKey)
            addView(models)
            addView(city)
            addView(button(R.string.action_save) { save() })
        }

        return ScrollView(this).apply { addView(column) }
    }

    private fun save() {
        settings.geminiApiKey = apiKey.text.toString()
        settings.geminiModels = models.text.toString()
        // Setting the city clears the stored coordinates, so the next weather question resolves
        // the new place rather than answering for the old one.
        city.text.toString().trim().takeIf { it.isNotEmpty() && it != settings.city }
            ?.let { settings.city = it }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun field(hintRes: Int, value: String, lines: Int = 1) = EditText(this).apply {
        setHint(hintRes)
        setText(value)
        setLines(lines)
        isSingleLine = lines == 1
    }

    private fun button(labelRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(labelRes)
        setOnClickListener { onClick() }
    }

    private fun requestMicrophoneIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}
