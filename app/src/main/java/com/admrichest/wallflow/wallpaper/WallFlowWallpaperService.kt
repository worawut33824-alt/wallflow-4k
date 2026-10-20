package com.admrichest.wallflow.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.admrichest.wallflow.data.LibraryRepository
import com.admrichest.wallflow.data.MediaItem
import com.admrichest.wallflow.data.Quality
import com.admrichest.wallflow.data.RotationMode
import com.admrichest.wallflow.data.CalendarRepository
import com.admrichest.wallflow.data.WeatherRepository
import com.admrichest.wallflow.data.Settings
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

private const val TAG = "WallFlowEngine"

/**
 * The live wallpaper.
 *
 * The device only ever has one live wallpaper installed, but this one reads
 * its content from the app library at draw time. That is the whole trick: set
 * it once, then add as many photos and videos as you like without ever going
 * back to the system wallpaper picker.
 *
 * Stills and video both go through [GlPainter]. Drawing a still with
 * lockCanvas would claim the Surface for the CPU buffer API, after which
 * MediaCodec can no longer connect to it and video silently stops appearing -
 * and WallpaperService offers no way to get a fresh Surface on demand.
 */
class WallFlowWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WallEngine()

    @androidx.annotation.OptIn(UnstableApi::class)
    private inner class WallEngine : Engine() {

        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "wallflow-engine-io")
        }

        private val settings by lazy { Settings.get(this@WallFlowWallpaperService) }

        private var painter: GlPainter? = null
        private var player: ExoPlayer? = null
        private var still: Bitmap? = null
        private var shownId: String? = null
        private var stillFallbackId: String? = null
        private var computedColors: WallpaperColors? = null

        private var visible = false
        private var surfaceW = 0
        private var surfaceH = 0
        private var videoW = 0
        private var videoH = 0
        private var xOffset = 0.5f
        private var kenBurnsStartedAt = 0L
        private var lastTapAt = 0L

        private val refreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = render(force = true)
        }

        private var overlayRenderer: OverlayRenderer? = null
        private var overlayBitmap: Bitmap? = null

        /** Redraws the clock layer on each minute boundary while visible. */
        private val overlayTick = object : Runnable {
            override fun run() {
                refreshOverlay()
                main.postDelayed(this, millisToNextMinute())
            }
        }

        private val kenBurnsTick = object : Runnable {
            override fun run() {
                if (!visible || still == null) return
                drawStill()
                main.postDelayed(this, KEN_BURNS_FRAME_MS)
            }
        }

        // ---- lifecycle -----------------------------------------------------

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(settings.parallax)
            ContextCompat.registerReceiver(
                this@WallFlowWallpaperService,
                refreshReceiver,
                IntentFilter(PlaybackController.ACTION_REFRESH),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        override fun onDestroy() {
            main.removeCallbacks(kenBurnsTick)
            main.removeCallbacks(overlayTick)
            runCatching {
                this@WallFlowWallpaperService.unregisterReceiver(refreshReceiver)
            }
            releasePlayer()
            releasePainter()
            still?.recycle()
            still = null
            overlayBitmap?.recycle()
            overlayBitmap = null
            io.shutdownNow()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
            rebuildPainter(holder)
            shownId = null
            stillFallbackId = null
            render(force = true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            main.removeCallbacks(kenBurnsTick)
            releasePlayer()
            releasePainter()
            shownId = null
            stillFallbackId = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            note("visible=$visible role=$role")
            if (!visible) {
                main.removeCallbacks(kenBurnsTick)
                main.removeCallbacks(overlayTick)
                player?.pause()
                return
            }

            // Reattach content first. advance() below is debounced and runs on
            // a worker thread, so leaning on it alone leaves a freshly
            // recreated surface blank for the whole debounce window.
            render(force = false)

            if (!isPreview && !isLockEngine && settings.rotationMode == RotationMode.UNLOCK) {
                PlaybackController.advance(this@WallFlowWallpaperService)
            }

            if (playbackAllowed()) player?.play()
            main.removeCallbacks(overlayTick)
            main.post(overlayTick)
            if (settings.kenBurns && still != null) {
                main.removeCallbacks(kenBurnsTick)
                main.post(kenBurnsTick)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xStep: Float,
            yStep: Float,
            xPixels: Int,
            yPixels: Int,
        ) {
            if (!settings.parallax) return
            this.xOffset = xOffset
            if (still != null) drawStill()
        }

        override fun onComputeColors(): WallpaperColors? =
            if (settings.adaptiveClockColors) computedColors else null

        override fun onTouchEvent(event: MotionEvent) {
            if (isPreview || settings.rotationMode != RotationMode.TAP) return
            if (event.action != MotionEvent.ACTION_DOWN) return
            val now = SystemClock.uptimeMillis()
            if (now - lastTapAt in 60..DOUBLE_TAP_MS) {
                lastTapAt = 0L
                PlaybackController.advance(this@WallFlowWallpaperService)
            } else {
                lastTapAt = now
            }
        }

        // ---- the GL surface -------------------------------------------------

        private fun rebuildPainter(holder: SurfaceHolder) {
            releasePainter()
            val surface = holder.surface
            if (surface == null || !surface.isValid || surfaceW <= 0 || surfaceH <= 0) {
                note("surface not usable yet")
                return
            }
            val created = GlPainter(surface, surfaceW, surfaceH)
            if (!created.isReady) {
                note("GL context could not be created")
                created.release()
                return
            }
            created.setOnFrameAvailable { main.post { drawVideoFrame() } }
            painter = created
            overlayRenderer = OverlayRenderer(surfaceW)
            refreshOverlay()
            note("GL surface ready ${surfaceW}x$surfaceH role=$role")
        }

        private fun releasePainter() {
            painter?.release()
            painter = null
        }

        // ---- rendering -----------------------------------------------------

        private fun render(force: Boolean) {
            val gl = painter ?: return
            if (surfaceW == 0 || surfaceH == 0) return

            val item = pickItem()
            if (item == null) {
                shownId = null
                releasePlayer()
                gl.drawBlank()
                return
            }

            val attached = when {
                !item.isVideo -> still != null
                player != null -> true
                stillFallbackId == item.id -> still != null
                else -> false
            }
            if (!force && item.id == shownId && attached) return

            shownId = item.id
            note("show ${item.type} ${item.displayName}")
            if (item.isVideo) showVideo(item) else showStill(item)
            updateColors(item)
        }

        private fun pickItem(): MediaItem? {
            val repo = LibraryRepository.get(this@WallFlowWallpaperService)
            return when {
                isPreview -> repo.homeQueue().firstOrNull() ?: repo.items.value.firstOrNull()
                isLockEngine -> PlaybackController.currentLock(this@WallFlowWallpaperService)
                else -> PlaybackController.currentHome(this@WallFlowWallpaperService)
            }
        }

        private fun showVideo(item: MediaItem) {
            main.removeCallbacks(kenBurnsTick)
            still?.recycle()
            still = null
            stillFallbackId = null
            releasePlayer()

            val target = painter?.videoSurface
            if (target == null) {
                note("no GL video surface, falling back to a frame")
                showStill(item)
                stillFallbackId = item.id
                return
            }

            videoW = 0
            videoH = 0
            val built = runCatching {
                ExoPlayer.Builder(this@WallFlowWallpaperService).build().apply {
                    setVideoSurface(target)
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(size: VideoSize) {
                            videoW = size.width
                            videoH = size.height
                            note("video size ${size.width}x${size.height}")
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            note("isPlaying=$isPlaying")
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            val name = when (state) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> state.toString()
                            }
                            note("playbackState=$name")
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            note("PLAYBACK FAILED ${error.errorCodeName}: ${error.message}")
                            main.post {
                                releasePlayer()
                                showStill(item)
                                stillFallbackId = item.id
                            }
                        }
                    })
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(item.parsedUri))
                    prepare()
                    playWhenReady = playbackAllowed()
                }
            }.onFailure {
                note("could not create a player: ${it.message}")
            }.getOrNull()

            player = built
            if (built != null) {
                note("video attached to GL surface")
            } else {
                showStill(item)
                stillFallbackId = item.id
            }
        }

        private fun drawVideoFrame() {
            painter?.drawVideoFrame(videoW, videoH)
        }

        private fun showStill(item: MediaItem) {
            releasePlayer()
            stillFallbackId = null
            val scale = if (settings.quality == Quality.FULL) 2 else 1
            val targetW = surfaceW * scale
            val targetH = surfaceH * scale

            io.execute {
                val decoded = ImageUtils.stillFor(
                    this@WallFlowWallpaperService, item, targetW, targetH
                )
                if (decoded == null) {
                    note("could not decode ${item.displayName}")
                    return@execute
                }
                main.post {
                    still?.recycle()
                    still = decoded
                    kenBurnsStartedAt = SystemClock.uptimeMillis()
                    drawStill()
                    if (settings.kenBurns && visible) {
                        main.removeCallbacks(kenBurnsTick)
                        main.post(kenBurnsTick)
                    }
                }
            }
        }

        private fun drawStill() {
            val bitmap = still ?: return
            if (bitmap.isRecycled) return
            val zoom = if (settings.kenBurns) kenBurnsZoom() else 1f
            val offset = if (settings.parallax) xOffset else 0.5f
            painter?.drawBitmap(bitmap, offset, zoom)
        }

        /** Repaints whatever is on screen, for when only the overlay changed. */
        private fun redrawCurrent() {
            if (still != null) drawStill() else painter?.drawVideoFrame(videoW, videoH)
        }

        // ---- clock overlay --------------------------------------------------

        /**
         * Keeps the overlay out of the fingerprint reader's area.
         *
         * The overlay cannot break the scanner: it is wallpaper, drawn below
         * the keyguard, and lock-screen touches never reach this engine. What
         * it can do is sit behind the fingerprint icon and make it hard to
         * read, so the bottom of the screen is reserved and the user's chosen
         * position is clamped to what is left.
         */
        private fun safeAnchor(layerHeight: Int): Float {
            val height = surfaceH.toFloat()
            if (height <= 0f) return settings.overlayAnchor

            val reserved = if (
                packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            ) {
                FINGERPRINT_SAFE_FRACTION
            } else {
                GESTURE_BAR_SAFE_FRACTION
            }

            val half = layerHeight / 2f / height
            val lowest = 1f - reserved - half
            val highest = STATUS_SAFE_FRACTION + half

            // A layer taller than the usable band simply centres.
            if (highest > lowest) return 0.5f
            return settings.overlayAnchor.coerceIn(highest, lowest)
        }

        private fun millisToNextMinute(): Long {
            val now = System.currentTimeMillis()
            return 60_000L - (now % 60_000L) + 250L
        }

        /**
         * Rebuilds the clock layer. Weather and agenda are read here rather
         * than cached in the engine so a revoked permission takes effect on
         * the very next minute.
         */
        private fun refreshOverlay() {
            val renderer = overlayRenderer ?: return
            val context = this@WallFlowWallpaperService

            if (!settings.showOverlay) {
                painter?.overlay = null
                overlayBitmap?.recycle()
                overlayBitmap = null
                redrawCurrent()
                return
            }

            io.execute {
                val now = Date()
                val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
                val minuteOfHour = Calendar.getInstance().get(Calendar.MINUTE)

                val weather = if (settings.showWeather) {
                    WeatherRepository.get(context).refreshIfStale()
                        ?.let { "${it.celsius}°   ${it.description}".trim() }
                } else {
                    null
                }

                val events = if (settings.showAgenda) {
                    val calendar = CalendarRepository.get(context)
                    calendar.upcoming(3).map { calendar.format(it) }
                } else {
                    emptyList()
                }

                val rendered = runCatching {
                    renderer.render(
                        OverlayContent(
                            time = DateFormat.format(timePattern, now).toString(),
                            date = DateFormat.format("EEEE d MMMM", now).toString(),
                            minuteFraction = minuteOfHour / 60f,
                            weather = weather,
                            events = events,
                        )
                    )
                }.onFailure { note("overlay render failed: ${it.message}") }.getOrNull()
                    ?: return@execute

                main.post {
                    val previous = overlayBitmap
                    overlayBitmap = rendered
                    painter?.overlayAnchor = safeAnchor(rendered.height)
                    painter?.overlay = rendered
                    redrawCurrent()
                    previous?.recycle()
                }
            }
        }

        /** A 0 to 6% drift over half a minute, then back. */
        private fun kenBurnsZoom(): Float {
            val elapsed = (SystemClock.uptimeMillis() - kenBurnsStartedAt).coerceAtLeast(0L)
            val phase = (elapsed % KEN_BURNS_CYCLE_MS).toFloat() / KEN_BURNS_CYCLE_MS
            val triangle = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            return 1f + 0.06f * triangle
        }

        // ---- colours for the lock-screen clock ------------------------------

        /**
         * The adaptive clock reads WallpaperColors. A live wallpaper has to
         * publish them itself, which is why a plain video wallpaper usually
         * leaves the clock stuck on one colour and this one does not.
         */
        private fun updateColors(item: MediaItem) {
            if (!settings.adaptiveClockColors) return
            io.execute {
                val source = ImageUtils.stillFor(this@WallFlowWallpaperService, item, 256, 256)
                    ?: return@execute
                val small = ImageUtils.shrinkForColors(source)
                val colors = runCatching { WallpaperColors.fromBitmap(small) }.getOrNull()
                if (small !== source) small.recycle()
                source.recycle()
                main.post {
                    computedColors = colors
                    runCatching { notifyColorsChanged() }
                }
            }
        }

        // ---- helpers -------------------------------------------------------

        /**
         * A live wallpaper that covers both screens gets one Engine per
         * screen, and both write here, so every line says which one it came
         * from. Without that the log reads like one engine contradicting
         * itself.
         */
        private val engineId: String =
            Integer.toHexString(System.identityHashCode(this)).takeLast(4)

        /**
         * A wallpaper covering both screens gets one Engine per screen, and
         * they must not behave identically: the lock engine follows the lock
         * queue, and it must never advance the queue, because the lock screen
         * appearing is the user putting the phone down, not unlocking it.
         */
        private val isLockEngine: Boolean
            get() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
                val flags = runCatching { wallpaperFlags }.getOrDefault(0)
                // One UI serves both screens from a single engine, and that
                // engine carries SYSTEM and LOCK together. Treating anything
                // with the LOCK bit as the lock engine left nobody advancing
                // the queue, so only a lock-only engine counts.
                return (flags and WallpaperManager.FLAG_LOCK) != 0 &&
                    (flags and WallpaperManager.FLAG_SYSTEM) == 0
            }

        private val role: String
            get() {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { wallpaperFlags }.getOrDefault(0)
                } else {
                    0
                }
                return "${if (isLockEngine) "LOCK" else "HOME"}(flags=$flags)"
            }

        private fun note(line: String) {
            val tagged = "[$engineId] $line"
            Log.i(TAG, tagged)
            runCatching { settings.appendEngineLog(tagged) }
        }

        private fun releasePlayer() {
            player?.let { p ->
                runCatching {
                    p.clearVideoSurface()
                    p.release()
                }
            }
            player = null
        }

        private fun playbackAllowed(): Boolean {
            val floor = settings.pauseVideoBelowBattery
            if (floor <= 0) return true
            val manager = getSystemService(BatteryManager::class.java) ?: return true
            if (manager.isCharging) return true
            val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return level <= 0 || level > floor
        }
    }

    private companion object {
        const val DOUBLE_TAP_MS = 320L
        const val KEN_BURNS_FRAME_MS = 80L
        const val KEN_BURNS_CYCLE_MS = 60_000L

        // Bottom band kept clear for the under-display fingerprint target on
        // a tall phone, measured as a fraction of screen height.
        const val FINGERPRINT_SAFE_FRACTION = 0.26f
        const val GESTURE_BAR_SAFE_FRACTION = 0.10f
        const val STATUS_SAFE_FRACTION = 0.07f
    }
}
