package com.ken1dot.spartanbar

internal class ActionCenterReveal {
    var maxHeight = 1f
        private set
    var visibleHeight = 0f
        private set

    fun show(height: Float) {
        visibleHeight = height.coerceIn(0f, maxHeight)
    }

    fun resize(height: Float) {
        val fraction = visibleHeight / maxHeight
        maxHeight = height.coerceAtLeast(1f)
        visibleHeight = fraction * maxHeight
    }

    fun shouldStayOpen(): Boolean = visibleHeight >= maxHeight * 0.65f
}
