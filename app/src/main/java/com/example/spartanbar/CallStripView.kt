package com.example.spartanbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** What the W10M in-call strip shows. [startedAtMillis] null = not connected yet. */
internal data class CallStripData(
    val name: String,
    val startedAtMillis: Long?,
    val held: Boolean,
    val carrier: String,
    /** Live-activity mode (music/timer/stopwatch/alarm): plain left and right text, no call timer. */
    val left: String? = null,
    val right: String? = null,
)

/**
 * Second row of the Windows 10 Mobile in-call status bar. While a call is active and the
 * Phone app is not in front, W10M turns the status bar into a full-width accent strip about
 * twice the normal height: the normal status icons on the top row (drawn by MetroBarView in
 * call mode) and "00:38  Tech Maniac ........ Vodafone UA-3MOB" on this row. Tap = back to call.
 */
internal class CallStripView(context: Context, private val onTap: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val bg = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    var data: CallStripData? = null
        set(value) { field = value; invalidate() }
    var accent: Int = Color.rgb(0, 120, 215)
        set(value) { field = Color.rgb(Color.red(value), Color.green(value), Color.blue(value)); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bg.color = accent
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val d = data ?: return
        text.typeface = MetroFont.regular(context)
        text.textSize = 13f * density
        // W10M: text sits slightly below the row centre (row is the lower 55% of the strip).
        val baseline = height * 0.52f - (text.ascent() + text.descent()) / 2f
        // Align with the row above: signal glyph's visible left edge / clock's right edge.
        val left = 14.2f * density
        val right = width - 14f * density
        d.left?.let { leftText ->
            val rightText = d.right.orEmpty()
            val rw = if (rightText.isBlank()) 0f else text.measureText(rightText)
            val max = right - left - (if (rw > 0f) rw + 16f * density else 0f)
            var l = leftText
            if (text.measureText(l) > max && max > 0f) {
                while (l.isNotEmpty() && text.measureText(l + "\u2026") > max) l = l.dropLast(1)
                l += "\u2026"
            }
            canvas.drawText(l, left, baseline, text)
            if (rw > 0f) canvas.drawText(rightText, right - rw, baseline, text)
            return
        }
        val duration = d.startedAtMillis?.let {
            formatCallDuration(System.currentTimeMillis() - it)
        } ?: "Calling"
        val status = if (d.held) "On hold" else duration
        canvas.drawText(status, left, baseline, text)
        val nameLeft = left + text.measureText(status) + 10f * density
        var carrier = d.carrier
        val carrierW = if (carrier.isBlank()) 0f else text.measureText(carrier)
        val nameMax = right - nameLeft - (if (carrierW > 0f) carrierW + 16f * density else 0f)
        var name = d.name
        if (text.measureText(name) > nameMax && nameMax > 0f) {
            while (name.isNotEmpty() && text.measureText(name + "\u2026") > nameMax) name = name.dropLast(1)
            name += "\u2026"
        }
        canvas.drawText(name, nameLeft, baseline, text)
        if (carrierW > 0f && nameMax > 0f) canvas.drawText(carrier, right - carrierW, baseline, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) { performClick(); onTap() }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        fun formatCallDuration(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000)
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
            else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
        }
    }
}
