package com.example.spartanbar

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Clock apps can publish their time only in a custom RemoteViews chronometer,
 * without the title or standard chronometer extras used by ordinary notifications.
 */
internal object ClockNotificationReader {
    private val clockPackages = setOf(
        "com.sec.android.app.clockpackage", "com.google.android.deskclock",
        "com.android.deskclock", "com.example.spartanclock"
    )
    private val timePattern = Regex("(?<![0-9])([0-9]{1,3}):([0-9]{2})(?::([0-9]{2}))?(?![0-9])")

    fun read(context: Context, sbn: StatusBarNotification): MetroLiveActivity? {
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val detail = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val extraText = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            notification.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
        val standardChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countdown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val channel = notification.channelId.orEmpty() + " " + sbn.tag.orEmpty() +
            " " + notification.category.orEmpty()
        val evidence = (channel + " " + title + " " + detail + " " +
            extraText.joinToString(" ")).lowercase()
        val knownClock = sbn.packageName in clockPackages
        val timerEvidence = evidence.contains("timer")
        val stopwatchEvidence = evidence.contains("stopwatch")
        if (!knownClock && !(standardChronometer && countdown) &&
            !(timerEvidence && notification.category == Notification.CATEGORY_ALARM)) return null

        var chronometer: Chronometer? = null
        val customText = mutableListOf<String>()
        if (knownClock) {
            val generated = try {
                Notification.Builder.recoverBuilder(context, notification).createContentView()
            } catch (_: RuntimeException) { null }
            for (remote in listOfNotNull(notification.contentView, notification.bigContentView,
                notification.headsUpContentView, generated)) {
                try {
                    val root = remote.apply(context, FrameLayout(context))
                    fun collect(view: View) {
                        if (view is Chronometer && chronometer == null &&
                            view.visibility == View.VISIBLE && view.base > 0) chronometer = view
                        if (view is TextView && view.text.isNotBlank()) {
                            customText.add(view.text.toString())
                        }
                        if (view is ViewGroup) {
                            for (index in 0 until view.childCount) collect(view.getChildAt(index))
                        }
                    }
                    collect(root)
                    if (chronometer != null) break
                } catch (_: RuntimeException) {
                    // Some custom layouts depend on private views unavailable to other apps.
                }
            }
        }
        val allText = (evidence + " " + customText.joinToString(" ")).lowercase()
        val futureBase = chronometer?.base?.let { it > SystemClock.elapsedRealtime() + 1000L } == true
        val isTimer = countdown || chronometer?.isCountDown == true || futureBase ||
            timerEvidence || allText.contains("timer")
        val isStopwatch = !isTimer && (stopwatchEvidence || allText.contains("stopwatch") ||
            chronometer != null || standardChronometer)
        if (!isTimer && !isStopwatch) {
            val alarm = allText.contains("alarm") ||
                notification.category == Notification.CATEGORY_ALARM
            if (!alarm) return null
            val label = listOf(title, detail).firstOrNull { it.contains(':') }
                ?: title.ifBlank { detail }.ifBlank { "Alarm" }
            return MetroLiveActivity(
                sbn, MetroLiveActivity.Kind.ALARM, "Alarm", label, null,
                notification.actions?.toList().orEmpty()
            )
        }

        val actions = notification.actions?.toList().orEmpty()
        val actionLabels = actions.map { it.title?.toString().orEmpty().lowercase() }
        val paused = allText.contains("paused") ||
            (actionLabels.any { it.contains("resume") || it == "start" } &&
                actionLabels.none { it.contains("pause") })
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val measured = clockDurationMillis(
            isTimer = isTimer,
            paused = paused,
            nowWall = wallNow,
            nowElapsed = elapsedNow,
            postTime = sbn.postTime,
            showChronometer = standardChronometer,
            countdownExtra = countdown,
            notificationWhen = notification.`when`,
            chronometerBase = chronometer?.base?.takeIf { it > 0 },
            chronometerCountDown = chronometer?.isCountDown == true,
            liveTextMillis = chronometer?.text?.toString()?.let { text ->
                parseDuration(text)?.takeUnless { matchesWallClock(text, wallNow) }
            },
            staticTextMillis = (listOf(title, detail) + extraText).firstNotNullOfOrNull { text ->
                parseDuration(text)?.takeUnless { matchesWallClock(text, wallNow) }
            }
        )
        val kind = if (isTimer) MetroLiveActivity.Kind.TIMER else MetroLiveActivity.Kind.STOPWATCH
        val displayTitle = title.takeIf { it.isNotBlank() && parseDuration(it) == null }
            ?: if (isTimer) "Timer" else "Stopwatch"
        return MetroLiveActivity(
            sbn, kind, displayTitle,
            detail.takeIf { it.isNotBlank() } ?: if (paused) "Paused" else "",
            if (isTimer && !paused && measured != null) wallNow + measured else null,
            actions,
            startedAtMillis = if (isStopwatch && !paused && measured != null)
                wallNow - measured else null,
            frozenTimeMillis = measured.takeIf { paused }
        )
    }

    private fun parseDuration(text: String): Long? {
        val match = timePattern.find(text) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val second = match.groupValues[2].toLongOrNull() ?: return null
        val third = match.groupValues[3].toLongOrNull()
        if (second > 59 || (third != null && third > 59)) return null
        return if (third != null) (first * 3600 + second * 60 + third) * 1000
            else (first * 60 + second) * 1000
    }
}

internal fun matchesWallClock(text: String, now: Long): Boolean {
    val match = Regex("(?<![0-9])([0-9]{1,2}):([0-9]{2})(?![0-9:])").find(text) ?: return false
    val clock = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val minute = clock.get(java.util.Calendar.MINUTE)
    val hour24 = clock.get(java.util.Calendar.HOUR_OF_DAY)
    val hour12 = clock.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
    val first = match.groupValues[1].toIntOrNull() ?: return false
    val second = match.groupValues[2].toIntOrNull() ?: return false
    return second == minute && (first == hour24 || first == hour12)
}

internal fun clockDurationMillis(
    isTimer: Boolean,
    paused: Boolean,
    nowWall: Long,
    nowElapsed: Long,
    postTime: Long,
    showChronometer: Boolean,
    countdownExtra: Boolean,
    notificationWhen: Long,
    chronometerBase: Long?,
    chronometerCountDown: Boolean,
    liveTextMillis: Long?,
    staticTextMillis: Long?
): Long? {
    if (paused) return liveTextMillis ?: staticTextMillis
    if (showChronometer && notificationWhen > 0L && (countdownExtra || !isTimer)) {
        if ((isTimer || countdownExtra) && notificationWhen > nowWall)
            return notificationWhen - nowWall
        if (!isTimer && !countdownExtra && notificationWhen <= nowWall)
            return nowWall - notificationWhen
    }
    if (chronometerBase != null && chronometerBase > 0L) {
        val countsDown = chronometerCountDown || chronometerBase > nowElapsed + 1000L
        if (countsDown && isTimer) return (chronometerBase - nowElapsed).coerceAtLeast(0)
        if (!countsDown && !isTimer) return (nowElapsed - chronometerBase).coerceAtLeast(0)
    }
    if (isTimer && notificationWhen > nowWall + 1000L &&
        notificationWhen < nowWall + 7L * 24 * 60 * 60 * 1000L)
        return notificationWhen - nowWall
    liveTextMillis?.let { return it }
    val displayed = staticTextMillis ?: return null
    val age = (nowWall - postTime).coerceAtLeast(0)
    return if (isTimer) (displayed - age).coerceAtLeast(0) else displayed + age
}
