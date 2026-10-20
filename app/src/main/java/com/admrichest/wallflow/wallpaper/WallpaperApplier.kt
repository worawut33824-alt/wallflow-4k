package com.admrichest.wallflow.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.admrichest.wallflow.data.MediaItem

private const val TAG = "WallFlowApplier"

/**
 * Writes a still into the system wallpaper slots.
 *
 * This is the path that sidesteps the stock 15-image lock-screen limit: One UI
 * only caps its own wallpaper picker, while WallpaperManager itself will take
 * as many successive images as it is given.
 */
object WallpaperApplier {

    /** Home screen only. */
    const val HOME = WallpaperManager.FLAG_SYSTEM

    /** Lock screen only. */
    const val LOCK = WallpaperManager.FLAG_LOCK

    /** Both at once, in a single system call. */
    const val BOTH = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK

    /**
     * Crops [item] to the panel and hands it to the system.
     *
     * The bitmap is pre-sized to the display, so the copy the system keeps is
     * as small as it can be while still being pixel-exact on screen.
     */
    fun applyStill(context: Context, item: MediaItem, flags: Int): Boolean {
        val manager = WallpaperManager.getInstance(context) ?: return false
        val (screenW, screenH) = ImageUtils.screenSize(context)

        // Ask for exactly one screen. Without this, launchers request a
        // double-width canvas for scrolling and the stored wallpaper doubles
        // in size for no visible gain.
        runCatching { manager.suggestDesiredDimensions(screenW, screenH) }

        val decoded = ImageUtils.stillFor(context, item, screenW, screenH)
        if (decoded == null) {
            Log.w(TAG, "could not decode ${item.displayName}")
            return false
        }

        val framed = ImageUtils.centerCrop(decoded, screenW, screenH)
        if (framed !== decoded) decoded.recycle()

        return try {
            manager.setBitmap(framed, null, true, flags)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setBitmap failed", t)
            false
        } finally {
            if (!framed.isRecycled) framed.recycle()
        }
    }

    /** True when our own live wallpaper is the one currently installed. */
    fun isLiveEngineActive(context: Context): Boolean {
        val info = runCatching { WallpaperManager.getInstance(context)?.wallpaperInfo }.getOrNull()
        return info?.packageName == context.packageName
    }

    /**
     * True when our live engine is already driving the lock screen.
     *
     * One UI does offer "home and lock screen" for a third-party live
     * wallpaper. When the user picks it, writing a still to FLAG_LOCK would
     * detach the engine from the lock screen and downgrade it to a photo, so
     * the mirror step has to stand down.
     */
    fun isLiveEngineOnLock(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val info = runCatching {
            WallpaperManager.getInstance(context)?.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
        }.getOrNull()
        return info?.packageName == context.packageName
    }

    /**
     * True when the lock screen is already showing our live engine.
     *
     * One UI serves both screens from a single engine when the user picks
     * "home and lock screen", and then getWallpaperInfo(FLAG_LOCK) answers
     * null because there is no separate lock wallpaper to describe. Reading
     * that as "no live wallpaper on lock" is what let the mirror step write a
     * still over our own video. A lock screen with no wallpaper of its own is
     * showing the system one, so that case counts too.
     */
    fun lockFollowsLiveEngine(context: Context): Boolean {
        if (!isLiveEngineActive(context)) return false
        if (isLiveEngineOnLock(context)) return true
        return !hasDedicatedLockWallpaper(context)
    }

    /** True when the system reports a separate lock-screen wallpaper is set. */
    fun hasDedicatedLockWallpaper(context: Context): Boolean = runCatching {
        WallpaperManager.getInstance(context)
            ?.getWallpaperId(WallpaperManager.FLAG_LOCK)
            ?.let { it > 0 } ?: false
    }.getOrDefault(false)

    /** Hands the lock screen back to the home-screen wallpaper. */
    fun clearLock(context: Context) {
        runCatching { WallpaperManager.getInstance(context)?.clear(WallpaperManager.FLAG_LOCK) }
    }
}
