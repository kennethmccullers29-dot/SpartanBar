package com.ken1dot.spartanbar

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockDurationTest {
    private val now = 1_000_000L
    private val elapsed = 5_000_000L

    @Test fun countdownWhenIsTheTimeLeft() {
        assertEquals(5 * 60_000L, clockDurationMillis(
            isTimer = true, paused = false, nowWall = now, nowElapsed = elapsed,
            postTime = now - 120_000L, showChronometer = true, countdownExtra = true,
            notificationWhen = now + 5 * 60_000L, chronometerBase = null,
            chronometerCountDown = false, liveTextMillis = 3 * 60_000L,
            staticTextMillis = 5 * 60_000L
        ))
    }

    @Test fun futureChronometerBaseIsRemainingEvenWithoutTheFlag() {
        assertEquals(5 * 60_000L, clockDurationMillis(
            isTimer = true, paused = false, nowWall = now, nowElapsed = elapsed,
            postTime = now - 120_000L, showChronometer = false, countdownExtra = false,
            notificationWhen = 0L, chronometerBase = elapsed + 5 * 60_000L,
            chronometerCountDown = false, liveTextMillis = 3 * 60_000L,
            staticTextMillis = null
        ))
    }

    @Test fun liveCountdownTextIsNotReducedByNotificationAge() {
        assertEquals(3 * 60_000L, clockDurationMillis(
            isTimer = true, paused = false, nowWall = now, nowElapsed = elapsed,
            postTime = now - 120_000L, showChronometer = false, countdownExtra = false,
            notificationWhen = 0L, chronometerBase = null,
            chronometerCountDown = false, liveTextMillis = 3 * 60_000L,
            staticTextMillis = 5 * 60_000L
        ))
    }

    @Test fun staticTimerTextAgesFromWhenItWasPosted() {
        assertEquals(3 * 60_000L, clockDurationMillis(
            isTimer = true, paused = false, nowWall = now, nowElapsed = elapsed,
            postTime = now - 120_000L, showChronometer = false, countdownExtra = false,
            notificationWhen = 0L, chronometerBase = null,
            chronometerCountDown = false, liveTextMillis = null,
            staticTextMillis = 5 * 60_000L
        ))
    }

    @Test fun pausedTimerKeepsTheShownTime() {
        assertEquals(4 * 60_000L, clockDurationMillis(
            isTimer = true, paused = true, nowWall = now, nowElapsed = elapsed,
            postTime = now - 120_000L, showChronometer = true, countdownExtra = true,
            notificationWhen = now + 5 * 60_000L, chronometerBase = null,
            chronometerCountDown = false, liveTextMillis = 4 * 60_000L,
            staticTextMillis = 5 * 60_000L
        ))
    }

    @Test fun stopwatchBaseIsElapsed() {
        assertEquals(90_000L, clockDurationMillis(
            isTimer = false, paused = false, nowWall = now, nowElapsed = elapsed,
            postTime = now - 90_000L, showChronometer = false, countdownExtra = false,
            notificationWhen = 0L, chronometerBase = elapsed - 90_000L,
            chronometerCountDown = false, liveTextMillis = null, staticTextMillis = null
        ))
    }

    @Test fun currentClockTimeIsNotADuration() {
        val twelveTen = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 10)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        org.junit.Assert.assertTrue(matchesWallClock("12:10 PM", twelveTen))
        org.junit.Assert.assertFalse(matchesWallClock("11:00", twelveTen))
    }
}
