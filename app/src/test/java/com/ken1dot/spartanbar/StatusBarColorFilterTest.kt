package com.ken1dot.spartanbar

import org.junit.Assert.*
import org.junit.Test

class StatusBarColorFilterTest {
    private val dark = 0xff202020.toInt()
    private val white = 0xffffffff.toInt()

    @Test fun transientWhiteDoesNotReplaceDarkColor() {
        val filter = StatusBarColorFilter()
        assertNull(filter.sample(dark, 0, 1))
        assertEquals(dark, filter.sample(dark, 400, 1))
        assertNull(filter.sample(white, 800, 1))
        assertNull(filter.sample(white, 1200, 1))
        assertNull(filter.sample(dark, 1600, 1))
        assertEquals(dark, filter.sample(dark, 2000, 1))
    }

    @Test fun genuineWhiteBackgroundIsEventuallyAccepted() {
        val filter = StatusBarColorFilter()
        assertNull(filter.sample(white, 0, 1))
        assertNull(filter.sample(white, 400, 1))
        assertEquals(white, filter.sample(white, 800, 1))
    }

    @Test fun newScreenRequiresItsOwnConfirmation() {
        val filter = StatusBarColorFilter()
        filter.sample(dark, 0, 1)
        assertNull(filter.sample(dark, 400, 2))
        assertEquals(dark, filter.sample(dark, 800, 2))
    }

    @Test fun oneWhitePixelCannotDominateTheSample() {
        assertEquals(dark, StatusBarColorFilter.median(intArrayOf(dark, dark, white)))
    }
}
