package com.admrichest.wallflow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private const val TAG = "WallFlowWeather"

/**
 * Current conditions from Open-Meteo.
 *
 * Open-Meteo needs no API key and no account, which keeps the app free of
 * credentials. Coordinates are rounded before they are sent: the weather is
 * the same across a few kilometres, and a coarse figure is all the service
 * needs to answer.
 */
class WeatherRepository private constructor(private val appContext: Context) {

    data class Reading(val celsius: Int, val description: String, val fetchedAt: Long)

    private val prefs =
        appContext.getSharedPreferences("wallflow_weather", Context.MODE_PRIVATE)

    fun cached(): Reading? {
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        val temp = prefs.getInt(KEY_TEMP, Int.MIN_VALUE)
        if (temp == Int.MIN_VALUE) return null
        return Reading(temp, prefs.getString(KEY_DESC, "") ?: "", at)
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns a fresh reading, or the cached one when it is recent enough or
     * the network is unreachable. Blocking; call it off the main thread.
     */
    fun refreshIfStale(maxAgeMs: Long = 30 * 60_000L): Reading? {
        val cached = cached()
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < maxAgeMs) return cached
        if (!hasLocationPermission()) return cached

        val location = lastKnownLocation() ?: return cached
        val reading = fetch(location) ?: return cached

        prefs.edit()
            .putInt(KEY_TEMP, reading.celsius)
            .putString(KEY_DESC, reading.description)
            .putLong(KEY_AT, reading.fetchedAt)
            .apply()
        return reading
    }

    private fun lastKnownLocation(): Location? {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        // Last known only. Asking for a fresh fix would wake the radios for a
        // number that changes by one degree an hour.
        return providers.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
    }

    private fun fetch(location: Location): Reading? = runCatching {
        val lat = String.format(java.util.Locale.US, "%.2f", location.latitude)
        val lon = String.format(java.util.Locale.US, "%.2f", location.longitude)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&timezone=auto"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
        }
        val body = connection.use { it.inputStream.bufferedReader().readText() }
        val current = JSONObject(body).getJSONObject("current")
        Reading(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            description = describe(current.optInt("weather_code", -1)),
            fetchedAt = System.currentTimeMillis(),
        )
    }.onFailure { Log.w(TAG, "weather fetch failed", it) }.getOrNull()

    private fun HttpURLConnection.use(block: (HttpURLConnection) -> String): String =
        try {
            block(this)
        } finally {
            disconnect()
        }

    /** WMO weather codes, condensed to the handful of phrases worth showing. */
    private fun describe(code: Int): String = when (code) {
        0 -> "ท้องฟ้าแจ่มใส"
        1, 2 -> "มีเมฆบางส่วน"
        3 -> "เมฆมาก"
        45, 48 -> "หมอก"
        in 51..57 -> "ฝนปรอย"
        in 61..65 -> "ฝนตก"
        66, 67 -> "ฝนเยือกแข็ง"
        in 71..77 -> "หิมะ"
        in 80..82 -> "ฝนซู่"
        85, 86 -> "หิมะตกหนัก"
        95 -> "พายุฝนฟ้าคะนอง"
        96, 99 -> "พายุลูกเห็บ"
        else -> ""
    }

    companion object {
        private const val KEY_TEMP = "temp_c"
        private const val KEY_DESC = "description"
        private const val KEY_AT = "fetched_at"

        @Volatile
        private var instance: WeatherRepository? = null

        fun get(context: Context): WeatherRepository =
            instance ?: synchronized(this) {
                instance ?: WeatherRepository(context.applicationContext).also { instance = it }
            }
    }
}
