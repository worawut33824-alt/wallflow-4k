package com.admrichest.wallflow.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Everything the overlay shows, already formatted for display. */
data class OverlayContent(
    val time: String,
    val date: String,
    /** 0..1 through the current hour, drawn as the ring's progress arc. */
    val minuteFraction: Float,
    val weather: String? = null,
    val events: List<String> = emptyList(),
)

/**
 * Draws the clock layer into a transparent bitmap for [GlPainter] to
 * composite over the wallpaper.
 *
 * Everything is white with a soft shadow rather than tinted to the photo:
 * a wallpaper queue runs over light and dark images alike, and a single
 * high-contrast treatment stays readable on both without recomputing a
 * palette on every change.
 */
class OverlayRenderer(private val width: Int) {

    private val scale = width / 1440f

    private val ringStroke = 5f * scale
    private val ringRadius = 300f * scale
    private val gapAfterRing = 86f * scale
    private val gapAfterDate = 54f * scale
    private val lineGap = 46f * scale

    private val timePaint = textPaint(166f, Typeface.create("sans-serif-light", Typeface.NORMAL))
    private val datePaint = textPaint(44f, Typeface.create("sans-serif", Typeface.NORMAL), 0.14f)
    private val weatherPaint = textPaint(46f, Typeface.create("sans-serif", Typeface.NORMAL))
    private val eventPaint = textPaint(38f, Typeface.create("sans-serif", Typeface.NORMAL))

    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        color = Color.argb(60, 255, 255, 255)
    }

    private val ringProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        setShadowLayer(10f * scale, 0f, 2f * scale, Color.argb(130, 0, 0, 0))
    }

    private fun textPaint(size: Float, face: Typeface, spacing: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * scale
            typeface = face
            textAlign = Paint.Align.CENTER
            letterSpacing = spacing
            setShadowLayer(14f * scale, 0f, 3f * scale, Color.argb(150, 0, 0, 0))
        }

    /** Height the layer needs for this content, so no space is wasted. */
    private fun heightFor(content: OverlayContent): Int {
        var h = ringRadius * 2f + ringStroke * 2f + gapAfterRing
        h += datePaint.textHeight() + gapAfterDate
        if (content.weather != null) h += weatherPaint.textHeight() + lineGap
        if (content.events.isNotEmpty()) {
            h += content.events.size * (eventPaint.textHeight() + lineGap * 0.45f)
        }
        return (h + 40f * scale).toInt()
    }

    fun render(content: OverlayContent): Bitmap {
        val height = heightFor(content)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f

        // --- ring ---------------------------------------------------------
        val ringCy = ringStroke + ringRadius
        val ringBox = RectF(
            cx - ringRadius, ringCy - ringRadius,
            cx + ringRadius, ringCy + ringRadius
        )
        canvas.drawCircle(cx, ringCy, ringRadius, ringTrackPaint)
        // Starts at twelve o'clock and sweeps with the hour.
        canvas.drawArc(ringBox, -90f, 360f * content.minuteFraction.coerceIn(0f, 1f), false, ringProgressPaint)

        // --- time inside the ring ------------------------------------------
        val timeBaseline = ringCy - (timePaint.ascent() + timePaint.descent()) / 2f
        canvas.drawText(content.time, cx, timeBaseline, timePaint)

        // --- everything below ------------------------------------------------
        var y = ringCy + ringRadius + gapAfterRing
        y += datePaint.textHeight()
        canvas.drawText(content.date, cx, y, datePaint)
        y += gapAfterDate

        content.weather?.let {
            y += weatherPaint.textHeight()
            canvas.drawText(it, cx, y, weatherPaint)
            y += lineGap
        }

        content.events.forEach { line ->
            y += eventPaint.textHeight()
            canvas.drawText(line, cx, y, eventPaint)
            y += lineGap * 0.45f
        }

        return bitmap
    }

    private fun Paint.textHeight(): Float = -ascent() + descent()
}
