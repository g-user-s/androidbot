package dev.alf.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var scope: CoroutineScope? = null

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

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)

            addView(status)
            addView(button(R.string.action_start) { AlfService.start(this@MainActivity) })
            addView(button(R.string.action_stop) { AlfService.stop(this@MainActivity) })
            addView(
                button(R.string.action_rebuild) {
                    AlfService.start(this@MainActivity, AlfService.ACTION_REBUILD_TEMPLATES)
                },
            )
        }
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
