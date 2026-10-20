package com.admrichest.wallflow.wallpaper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.admrichest.wallflow.data.RotationMode
import com.admrichest.wallflow.data.Settings

/**
 * Timer for interval rotation.
 *
 * Uses an inexact, doze-tolerant alarm rather than an exact one. A wallpaper
 * changing a minute late is invisible, and exact alarms would mean asking for a
 * restricted permission that Google Play reserves for alarm clocks.
 */
object RotationScheduler {

    const val ACTION_ALARM = "com.admrichest.wallflow.action.ALARM"
    private const val REQUEST_CODE = 7301

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)

        alarms.cancel(pending)

        val settings = Settings.get(app)
        if (settings.rotationMode != RotationMode.INTERVAL) return

        val triggerAt = System.currentTimeMillis() + settings.intervalMinutes * 60_000L
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, RotationReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
