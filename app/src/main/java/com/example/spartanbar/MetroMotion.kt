package com.example.spartanbar

import android.view.animation.PathInterpolator

/** W10M motion curves shared by every SpartanBar animation. */
internal object MetroMotion {
    /** Entrances and settles: fast start, long soft landing. */
    val Decel = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)
    /** Exits: gentle start, quick finish. */
    val Accel = PathInterpolator(0.7f, 0f, 1f, 0.5f)

    /** Duration for finishing a drag: follows the finger's speed, clamped to 140-300ms. */
    fun settleDuration(remainingPx: Float, velocityPxPerSec: Float, fallback: Long): Long {
        val v = kotlin.math.abs(velocityPxPerSec)
        if (v < 300f) return fallback
        return (kotlin.math.abs(remainingPx) / v * 1000f * 1.8f).toLong().coerceIn(140L, 300L)
    }
}