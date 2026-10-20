package com.admrichest.wallflow.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** When does the wallpaper move to the next item. */
enum class RotationMode { OFF, INTERVAL, UNLOCK, TAP }

/** How much of the source resolution the live engine keeps in memory. */
enum class Quality { BALANCED, FULL }

/**
 * All user preferences, in one small SharedPreferences file.
 *
 * Read from three processes (UI, alarm receiver, wallpaper engine), so every
 * value is read straight from disk rather than cached in a field.
 */
class Settings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wallflow_settings", Context.MODE_PRIVATE)

    init {
        // The mirror used to default to on. On a device that puts the live
        // wallpaper on both screens that setting writes a still over our own
        // video, so anyone carrying the old value gets it turned off once.
        if (prefs.getInt(KEY_SETTINGS_VERSION, 0) < 1) {
            prefs.edit()
                .putBoolean(KEY_MIRROR_LOCK, false)
                .putInt(KEY_SETTINGS_VERSION, 1)
                .apply()
        }
    }

    /** true = one picture on both screens, false = home and lock run separate queues. */
    var syncedScreens: Boolean
        get() = prefs.getBoolean(KEY_SYNCED, true)
        set(v) = prefs.edit().putBoolean(KEY_SYNCED, v).apply()

    var rotationMode: RotationMode
        get() = runCatching { RotationMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(RotationMode.UNLOCK)
        set(v) = prefs.edit().putString(KEY_MODE, v.name).apply()

    /** Minutes between changes when rotationMode is INTERVAL. */
    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 30)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceIn(1, 24 * 60)).apply()

    var shuffle: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(v) = prefs.edit().putBoolean(KEY_SHUFFLE, v).apply()

    /**
     * Mirror whatever the live engine shows onto the lock screen as a still.
     * This is what keeps the lock screen in sync on devices where a live
     * wallpaper can only be attached to the home screen.
     */
    var mirrorToLock: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_LOCK, false)
        set(v) = prefs.edit().putBoolean(KEY_MIRROR_LOCK, v).apply()

    /** Slow pan and zoom on still images in the live engine. Costs battery. */
    var kenBurns: Boolean
        get() = prefs.getBoolean(KEY_KEN_BURNS, false)
        set(v) = prefs.edit().putBoolean(KEY_KEN_BURNS, v).apply()

    /** Shift the image with home-screen page swipes. */
    var parallax: Boolean
        get() = prefs.getBoolean(KEY_PARALLAX, true)
        set(v) = prefs.edit().putBoolean(KEY_PARALLAX, v).apply()

    /** Stop video playback below this battery percentage. 0 disables the guard. */
    var pauseVideoBelowBattery: Int
        get() = prefs.getInt(KEY_BATTERY_GUARD, 20)
        set(v) = prefs.edit().putInt(KEY_BATTERY_GUARD, v.coerceIn(0, 50)).apply()

    var quality: Quality
        get() = runCatching { Quality.valueOf(prefs.getString(KEY_QUALITY, null) ?: "") }
            .getOrDefault(Quality.BALANCED)
        set(v) = prefs.edit().putString(KEY_QUALITY, v.name).apply()

    /** Feed WallpaperColors to the system so the lock clock recolours itself. */
    var adaptiveClockColors: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_COLORS, true)
        set(v) = prefs.edit().putBoolean(KEY_ADAPTIVE_COLORS, v).apply()

    // ---- clock overlay -----------------------------------------------------

    /** Draw the circular clock layer on top of the wallpaper. */
    var showOverlay: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, true)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY, v).apply()

    /** Needs ACCESS_COARSE_LOCATION and INTERNET; silently off without them. */
    var showWeather: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_WEATHER, true)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY_WEATHER, v).apply()

    /** Needs READ_CALENDAR; silently off without it. */
    var showAgenda: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_AGENDA, true)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY_AGENDA, v).apply()

    /** Vertical placement of the overlay, 0 at the top and 1 at the bottom. */
    var overlayAnchor: Float
        get() = prefs.getFloat(KEY_OVERLAY_ANCHOR, 0.42f)
        set(v) = prefs.edit().putFloat(KEY_OVERLAY_ANCHOR, v.coerceIn(0.15f, 0.85f)).apply()

    // ---- queue position ----------------------------------------------------

    var homeIndex: Int
        get() = prefs.getInt(KEY_HOME_INDEX, 0)
        set(v) = prefs.edit().putInt(KEY_HOME_INDEX, v).apply()

    var lockIndex: Int
        get() = prefs.getInt(KEY_LOCK_INDEX, 0)
        set(v) = prefs.edit().putInt(KEY_LOCK_INDEX, v).apply()

    /** Wall clock time of the last change, used to debounce unlock triggers. */
    var lastChangeAt: Long
        get() = prefs.getLong(KEY_LAST_CHANGE, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_CHANGE, v).apply()

    // ---- diagnostics -------------------------------------------------------

    /**
     * A short rolling log the wallpaper engine writes and the settings screen
     * reads back. The engine has no UI of its own and can be restarted by the
     * system at any time, so without this the only way to see what it did is
     * a USB cable and logcat.
     */
    var engineLog: String
        get() = prefs.getString(KEY_ENGINE_LOG, "") ?: ""
        private set(v) = prefs.edit().putString(KEY_ENGINE_LOG, v).apply()

    fun appendEngineLog(line: String, keepLines: Int = 30) {
        val stamped = "${TIME_FORMAT.format(Date())}  $line"
        val kept = (engineLog.lineSequence().filter { it.isNotBlank() } + stamped)
            .toList()
            .takeLast(keepLines)
        engineLog = kept.joinToString("\n")
    }

    fun clearEngineLog() {
        engineLog = ""
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

        private const val KEY_SYNCED = "synced_screens"
        private const val KEY_MODE = "rotation_mode"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_MIRROR_LOCK = "mirror_to_lock"
        private const val KEY_KEN_BURNS = "ken_burns"
        private const val KEY_PARALLAX = "parallax"
        private const val KEY_BATTERY_GUARD = "battery_guard"
        private const val KEY_QUALITY = "quality"
        private const val KEY_ADAPTIVE_COLORS = "adaptive_colors"
        private const val KEY_HOME_INDEX = "home_index"
        private const val KEY_LOCK_INDEX = "lock_index"
        private const val KEY_LAST_CHANGE = "last_change_at"
        private const val KEY_ENGINE_LOG = "engine_log"
        private const val KEY_SETTINGS_VERSION = "settings_version"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_OVERLAY_WEATHER = "overlay_weather"
        private const val KEY_OVERLAY_AGENDA = "overlay_agenda"
        private const val KEY_OVERLAY_ANCHOR = "overlay_anchor"

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also { instance = it }
            }
    }
}
