package com.admrichest.wallflow.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the app for the interval timer, and re-arms it after a reboot or an
 * app update so rotation never silently stops.
 */
class RotationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> RotationScheduler.reschedule(app)

            RotationScheduler.ACTION_ALARM -> {
                val pending = goAsync()
                // The alarm is one-shot, so queue the next one straight away.
                RotationScheduler.reschedule(app)
                PlaybackController.advance(app, respectDebounce = false) { pending.finish() }
            }
        }
    }
}
