package com.admrichest.wallflow

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.admrichest.wallflow.wallpaper.RotationScheduler

/**
 * Thumbnail caching is the only place the app spends disk space of its own, so
 * it is capped here rather than left to grow.
 */
class WallFlowApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Survives a force stop followed by a relaunch.
        RotationScheduler.reschedule(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbnails"))
                .maxSizeBytes(THUMBNAIL_CACHE_BYTES)
                .build()
        }
        .crossfade(true)
        .build()

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 48L * 1024 * 1024
    }
}
