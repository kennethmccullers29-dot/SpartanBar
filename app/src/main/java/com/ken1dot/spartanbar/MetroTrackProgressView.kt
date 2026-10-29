package com.ken1dot.spartanbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** A flat Metro progress strip with a full-width touch target for tapping and scrubbing. */
internal class MetroTrackProgressView(
    context: Context,
    private val onSeek: (Long) -> Unit,
    private val onScrubChanged: (String?) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var duration = 0L
    private var position = 0L
    private var preview = 0L
    private var scrubbing = false
    private var accent = Color.rgb(0, 120, 215)

    fun update(positionMillis: Long, durationMillis: Long, canSeek: Boolean) {
        duration = durationMillis.coerceAtLeast(0)
        if (!scrubbing) position = positionMillis.coerceIn(0, duration)
        isEnabled = canSeek
        contentDescription = "Playback " + format(position) + " of " + format(duration)
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accent = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = if (scrubbing) preview else position
        val fraction = if (duration > 0) current.toFloat() / duration else 0f
        paint.color = Color.rgb(52, 52, 52)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = accent
        val top = if (scrubbing) 0f else height - 4f * density
        canvas.drawRect(0f, top, width * fraction, height.toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0 || width <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                updatePreview(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> { updatePreview(event.x); return true }
            MotionEvent.ACTION_UP -> {
                updatePreview(event.x)
                position = preview
                scrubbing = false
                onScrubChanged(null)
                performClick()
                onSeek(position)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                onScrubChanged(null)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f,
            if (duration > 0) position.toFloat() / duration else 0f)
        if (isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (isEnabled && duration > 0 && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            val change = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 10000L else -10000L
            position = (position + change).coerceIn(0, duration)
            onSeek(position)
            invalidate()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun updatePreview(x: Float) {
        preview = (duration * (x / width).coerceIn(0f, 1f)).toLong()
        onScrubChanged(format(preview) + " / −" + format(duration - preview))
        invalidate()
    }

    private fun format(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }
}
