package com.admrichest.wallflow.wallpaper

import android.content.Context
import android.content.Intent
import android.util.Log
import com.admrichest.wallflow.data.LibraryRepository
import com.admrichest.wallflow.data.MediaItem
import com.admrichest.wallflow.data.Settings
import java.util.concurrent.Executors
import kotlin.random.Random

private const val TAG = "WallFlowPlayback"

/**
 * Decides what is on screen and puts it there.
 *
 * One queue position lives in [Settings], so the alarm receiver, the UI and the
 * live engine always agree on which item is current.
 */
object PlaybackController {

    /** Sent after the current item changes, so a running engine can redraw. */
    const val ACTION_REFRESH = "com.admrichest.wallflow.action.REFRESH"

    /** Ignore repeat triggers that arrive within this window. */
    private const val DEBOUNCE_MS = 5_000L

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wallflow-playback").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // ---- reading the queue -------------------------------------------------

    fun currentHome(context: Context): MediaItem? {
        val queue = LibraryRepository.get(context).homeQueue()
        if (queue.isEmpty()) return null
        return queue[Settings.get(context).homeIndex.mod(queue.size)]
    }

    fun currentLock(context: Context): MediaItem? {
        val settings = Settings.get(context)
        if (settings.syncedScreens) return currentHome(context)
        val queue = LibraryRepository.get(context).lockQueue()
        if (queue.isEmpty()) return null
        return queue[settings.lockIndex.mod(queue.size)]
    }

    // ---- changing the queue ------------------------------------------------

    /** Steps to the next item and applies it. Runs off the calling thread. */
    fun advance(context: Context, respectDebounce: Boolean = true, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            try {
                val settings = Settings.get(app)
                val now = System.currentTimeMillis()
                if (respectDebounce && now - settings.lastChangeAt < DEBOUNCE_MS) return@execute

                val repo = LibraryRepository.get(app)
                val homeQueue = repo.homeQueue()
                if (homeQueue.isNotEmpty()) {
                    settings.homeIndex = step(settings.homeIndex, homeQueue.size, settings.shuffle)
                }
                if (!settings.syncedScreens) {
                    val lockQueue = repo.lockQueue()
                    if (lockQueue.isNotEmpty()) {
                        settings.lockIndex =
                            step(settings.lockIndex, lockQueue.size, settings.shuffle)
                    }
                }
                settings.lastChangeAt = now
                separateLockFromHome(app)
                applyBlocking(app)
            } catch (t: Throwable) {
                Log.w(TAG, "advance failed", t)
            } finally {
                onDone?.invoke()
            }
        }
    }

    /** Jumps straight to one item without touching the rest of the queue. */
    fun jumpTo(context: Context, item: MediaItem, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            try {
                val repo = LibraryRepository.get(app)
                val settings = Settings.get(app)

                // Honour the item's own H / L tags. A lock-only picture should
                // land on the lock screen, not drag the home screen with it.
                if (item.onHome) {
                    repo.homeQueue().indexOfFirst { it.id == item.id }
                        .takeIf { it >= 0 }?.let { settings.homeIndex = it }
                }
                if (item.onLock) {
                    repo.lockQueue().indexOfFirst { it.id == item.id }
                        .takeIf { it >= 0 }?.let { settings.lockIndex = it }
                }

                settings.lastChangeAt = System.currentTimeMillis()
                if (!item.onLock) separateLockFromHome(app)
                applyBlocking(app)
            } catch (t: Throwable) {
                Log.w(TAG, "jumpTo failed", t)
            } finally {
                onDone?.invoke()
            }
        }
    }

    /** Re-applies whatever is current, for example after a settings change. */
    fun reapply(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            try {
                separateLockFromHome(app)
                applyBlocking(app)
            } catch (t: Throwable) {
                Log.w(TAG, "reapply failed", t)
            } finally {
                onDone?.invoke()
            }
        }
    }

    // ---- the actual work ---------------------------------------------------

    private fun applyBlocking(context: Context) {
        val settings = Settings.get(context)
        val liveActive = WallpaperApplier.isLiveEngineActive(context)
        val home = currentHome(context)
        val lock = currentLock(context)

        if (liveActive) {
            // The engine owns the home screen. Nudge it, then mirror the lock
            // screen as a still so it follows the same queue on devices where a
            // live wallpaper cannot be attached to the lock screen.
            context.sendBroadcast(
                Intent(ACTION_REFRESH).setPackage(context.packageName)
            )
            // Only mirror when the engine is not already on the lock screen.
            // Writing a still there otherwise replaces our own live wallpaper
            // with a photo, which is the opposite of what the setting is for.
            if (settings.mirrorToLock && lock != null &&
                !WallpaperApplier.lockFollowsLiveEngine(context)
            ) {
                WallpaperApplier.applyStill(context, lock, WallpaperApplier.LOCK)
            }
            return
        }

        if (settings.syncedScreens) {
            // One system call sets both screens at the same instant.
            home?.let { WallpaperApplier.applyStill(context, it, WallpaperApplier.BOTH) }
        } else {
            home?.let { WallpaperApplier.applyStill(context, it, WallpaperApplier.HOME) }
            lock?.let { WallpaperApplier.applyStill(context, it, WallpaperApplier.LOCK) }
        }
    }

    /**
     * Keeps the two screens showing different pictures when they are running
     * separate queues.
     *
     * Every item is tagged for both screens when it is added, so the home and
     * lock queues usually start out as the same list with the same position.
     * Turning the sync switch off would then change nothing visible, which
     * reads as the setting being broken. Nudge the lock queue off the home
     * item instead.
     */
    private fun separateLockFromHome(context: Context) {
        val settings = Settings.get(context)
        if (settings.syncedScreens) return

        val lockQueue = LibraryRepository.get(context).lockQueue()
        if (lockQueue.size <= 1) return

        val homeId = currentHome(context)?.id ?: return
        var attempts = 0
        while (lockQueue[settings.lockIndex.mod(lockQueue.size)].id == homeId &&
            attempts++ < lockQueue.size
        ) {
            settings.lockIndex = settings.lockIndex + 1
        }
    }

    private fun step(current: Int, size: Int, shuffle: Boolean): Int {
        if (size <= 1) return 0
        if (!shuffle) return (current + 1).mod(size)
        var next = Random.nextInt(size)
        if (next == current.mod(size)) next = (next + 1).mod(size)
        return next
    }
}
