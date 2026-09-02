package dev.alf.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the listener back after a restart; a home assistant nobody logs into has to self start. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AlfService.start(context)
    }
}
