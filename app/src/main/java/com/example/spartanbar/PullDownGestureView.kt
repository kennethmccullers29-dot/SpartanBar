package com.example.spartanbar

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View

internal class PullDownGestureView(
    context: Context,
    private val onDrag: (distancePx: Float, released: Boolean) -> Unit
) : View(context) {
    private var startY = 0f
    private var tracker: android.view.VelocityTracker? = null
    /** Finger speed at release (px/s, down is positive), read by the panel settle. */
    var releaseVelocity = 0f
        private set
    private val density = resources.displayMetrics.density

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        contentDescription = "Pull down for Action Center"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY
                tracker?.recycle()
                tracker = android.view.VelocityTracker.obtain().also { it.addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val distance = (event.rawY - startY).coerceAtLeast(0f)
                onDrag(distance, false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val distance = (event.rawY - startY).coerceAtLeast(0f)
                tracker?.addMovement(event); tracker?.computeCurrentVelocity(1000)
                releaseVelocity = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                val quickFlick = (distance >= 10f * density && event.eventTime - event.downTime <= 350L) ||
                    (distance >= 12f * density && releaseVelocity > 600f * density)
                val flungBack = releaseVelocity < -600f * density
                onDrag(if (flungBack) 0f else if (quickFlick) maxOf(distance, 32f * density) else distance, true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                onDrag(0f, true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
