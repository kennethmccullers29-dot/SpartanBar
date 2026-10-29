package com.ken1dot.spartanbar

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import java.io.IOException

internal object MetroFont {
    private val cache = mutableMapOf<Int, Typeface>()

    fun light(context: Context): Typeface = load(context, R.font.selawik_light)
    fun regular(context: Context): Typeface = load(context, R.font.selawik_regular)
    fun semibold(context: Context): Typeface = load(context, R.font.selawik_semibold)

    private fun load(context: Context, font: Int): Typeface {
        cache[font]?.let { return it }
        val selawik = ResourcesCompat.getFont(context, font) ?: return Typeface.DEFAULT
        val loaded = if (Build.VERSION.SDK_INT < 29) selawik else try {
            val family = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(context.resources, font).build()
            ).build()
            Typeface.CustomFallbackBuilder(family).setSystemFallback("sans-serif").build()
        } catch (_: IOException) {
            selawik
        }
        cache[font] = loaded
        return loaded
    }
}
