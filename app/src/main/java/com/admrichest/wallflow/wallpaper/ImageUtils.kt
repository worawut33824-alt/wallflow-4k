package com.admrichest.wallflow.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.admrichest.wallflow.data.MediaItem
import com.admrichest.wallflow.data.MediaType
import kotlin.math.max

private const val TAG = "WallFlowImage"

/**
 * Decoding helpers.
 *
 * A 4K source is never held at full size unless the caller asks for it: every
 * decode is sampled down towards the pixels that will actually be shown, which
 * is what keeps a phone-sized wallpaper out of a 100 MB heap.
 */
object ImageUtils {

    /** Physical pixel size of the default display. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /**
     * Decodes [uri] no smaller than [targetW] x [targetH], honouring EXIF
     * rotation. Returns a software bitmap so it can be drawn and compressed.
     */
    fun decodeSampled(context: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap? =
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                decoder.setTargetSampleSize(
                    sampleSizeFor(info.size.width, info.size.height, targetW, targetH)
                )
            }
        }.onFailure { Log.w(TAG, "decode failed for $uri", it) }.getOrNull()

    /** Largest power-of-two sample size that still covers the target box. */
    fun sampleSizeFor(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) {
            sample *= 2
        }
        return sample
    }

    /** Pulls a representative still out of a video, scaled towards the target box. */
    fun videoFrame(context: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // A frame a little way in avoids the black fade many clips start on.
            val atUs = (durationMs * 1000L / 4).coerceAtLeast(0L)
            retriever.getScaledFrameAtTime(
                atUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetW,
                targetH
            ) ?: retriever.frameAtTime
        }.onFailure { Log.w(TAG, "no frame from $uri", it) }
            .also { runCatching { retriever.release() } }
            .getOrNull()
    }

    /** Decodes whichever kind of media [item] is into a still. */
    fun stillFor(context: Context, item: MediaItem, targetW: Int, targetH: Int): Bitmap? =
        when (item.type) {
            MediaType.IMAGE -> decodeSampled(context, item.parsedUri, targetW, targetH)
            MediaType.VIDEO -> videoFrame(context, item.parsedUri, targetW, targetH)
        }

    /** Scales [src] to cover a [w] x [h] box and crops the overflow evenly. */
    fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** A tiny copy, used only to derive WallpaperColors. */
    fun shrinkForColors(src: Bitmap, maxDim: Int = 128): Bitmap {
        val scale = maxDim.toFloat() / max(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
