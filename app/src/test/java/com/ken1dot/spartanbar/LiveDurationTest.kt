package com.ken1dot.spartanbar

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDurationTest {
    @Test fun underAnHourStaysMinutesAndSeconds() {
        assertEquals("0:00", formatLiveDuration(0, roundUp = false))
        assertEquals("0:59", formatLiveDuration(59_000, roundUp = false))
        assertEquals("1:00", formatLiveDuration(60_000, roundUp = false))
    }

    @Test fun anHourOrMoreShowsHours() {
        assertEquals("1:00:00", formatLiveDuration(3_600_000, roundUp = false))
        assertEquals("1:30:00", formatLiveDuration(5_400_000, roundUp = false))
    }

    @Test fun timerRoundsUpTheLastPartialSecond() {
        assertEquals("0:01", formatLiveDuration(1, roundUp = true))
        assertEquals("1:00:00", formatLiveDuration(3_599_001, roundUp = true))
    }
}
