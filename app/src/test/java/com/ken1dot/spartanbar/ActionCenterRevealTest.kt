package com.ken1dot.spartanbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCenterRevealTest {
    @Test fun dragIsClampedToActualWindow() {
        val reveal = ActionCenterReveal()
        reveal.resize(600f)
        reveal.show(900f)
        assertEquals(600f, reveal.visibleHeight, 0f)
        reveal.show(-20f)
        assertEquals(0f, reveal.visibleHeight, 0f)
    }

    @Test fun resizingPreservesClosedOpenAndPartialPositions() {
        val reveal = ActionCenterReveal()
        reveal.resize(600f)
        reveal.resize(400f)
        assertEquals(0f, reveal.visibleHeight, 0f)
        reveal.show(200f)
        reveal.resize(800f)
        assertEquals(400f, reveal.visibleHeight, 0f)
        reveal.show(800f)
        reveal.resize(300f)
        assertEquals(300f, reveal.visibleHeight, 0f)
    }

    @Test fun releaseUsesCurrentPositionInsteadOfFarthestDrag() {
        val reveal = ActionCenterReveal()
        reveal.resize(1000f)
        reveal.show(900f)
        assertTrue(reveal.shouldStayOpen())
        reveal.show(400f)
        assertFalse(reveal.shouldStayOpen())
        reveal.show(650f)
        assertTrue(reveal.shouldStayOpen())
    }

    @Test fun zeroSizedWindowDoesNotProduceInvalidCoordinates() {
        val reveal = ActionCenterReveal()
        reveal.resize(0f)
        reveal.show(100f)
        assertEquals(1f, reveal.visibleHeight, 0f)
    }
}
