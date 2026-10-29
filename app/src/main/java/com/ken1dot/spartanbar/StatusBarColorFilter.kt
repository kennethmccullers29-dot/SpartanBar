package com.ken1dot.spartanbar

/** Requires consistent screen samples before replacing the current bar color. */
internal class StatusBarColorFilter {
    private var generation = Int.MIN_VALUE
    private var candidate = 0
    private var count = 0
    private var since = 0L

    fun sample(color: Int, now: Long, screenGeneration: Int): Int? {
        if (generation != screenGeneration || !similar(candidate, color)) {
            generation = screenGeneration
            candidate = color
            count = 1
            since = now
            return null
        }
        count++
        val bright = (16 downTo 0 step 8).all { (color ushr it and 255) >= 225 }
        val requiredCount = if (bright) 3 else 2
        val requiredTime = if (bright) 700L else 250L
        return color.takeIf { count >= requiredCount && now - since >= requiredTime }
    }

    private fun similar(first: Int, second: Int): Boolean =
        (16 downTo 0 step 8).all {
            kotlin.math.abs((first ushr it and 255) - (second ushr it and 255)) <= 12
        }

    companion object {
        fun median(colors: IntArray): Int {
            require(colors.isNotEmpty())
            fun channel(shift: Int) = colors.map { it ushr shift and 255 }
                .sorted()[colors.size / 2]
            return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
