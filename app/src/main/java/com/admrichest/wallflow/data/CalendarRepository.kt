package com.admrichest.wallflow.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Date

private const val TAG = "WallFlowCalendar"

/**
 * The next few events from the device calendars.
 *
 * Read-only and never stored: the overlay asks for the list when it redraws
 * and keeps nothing afterwards, so there is no copy of the user's schedule
 * inside the app.
 */
class CalendarRepository private constructor(private val appContext: Context) {

    data class Event(val startMillis: Long, val title: String, val allDay: Boolean)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Upcoming events between now and the end of tomorrow. Blocking. */
    fun upcoming(limit: Int = 3): List<Event> {
        if (!hasPermission()) return emptyList()

        val now = System.currentTimeMillis()
        val until = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }.timeInMillis

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, now) }
            .also { ContentUris.appendId(it, until) }
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
        )

        return runCatching {
            appContext.contentResolver.query(
                uri,
                projection,
                "${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val events = ArrayList<Event>(limit)
                while (cursor.moveToNext() && events.size < limit) {
                    val title = cursor.getString(1)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    events += Event(
                        startMillis = cursor.getLong(0),
                        title = title,
                        allDay = cursor.getInt(2) == 1,
                    )
                }
                events
            }.orEmpty()
        }.onFailure { Log.w(TAG, "calendar query failed", it) }.getOrDefault(emptyList())
    }

    /** "09:30  ประชุมทีม", trimmed so one event never wraps the overlay. */
    fun format(event: Event, maxTitle: Int = 28): String {
        val title = if (event.title.length > maxTitle) {
            event.title.take(maxTitle - 1).trimEnd() + "…"
        } else {
            event.title
        }
        if (event.allDay) return "ทั้งวัน   $title"
        val pattern = if (DateFormat.is24HourFormat(appContext)) "HH:mm" else "h:mm a"
        val time = DateFormat.format(pattern, Date(event.startMillis))
        return "$time   $title"
    }

    companion object {
        @Volatile
        private var instance: CalendarRepository? = null

        fun get(context: Context): CalendarRepository =
            instance ?: synchronized(this) {
                instance ?: CalendarRepository(context.applicationContext).also { instance = it }
            }
    }
}
