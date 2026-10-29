package com.ken1dot.spartanbar

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Index of the action that should fill the toast reply box, or -1. */
internal fun selectReplyAction(
    hasRemoteInput: BooleanArray,
    semanticReply: BooleanArray,
    titles: Array<out CharSequence?>
): Int {
    var titled = -1
    var any = -1
    for (index in hasRemoteInput.indices) {
        if (!hasRemoteInput[index]) continue
        if (semanticReply.getOrElse(index) { false }) return index
        if (any < 0) any = index
        val title = titles.getOrNull(index)?.toString()
        if (titled < 0 && title?.contains("reply", ignoreCase = true) == true) titled = index
    }
    return if (titled >= 0) titled else any
}

internal object NotificationReply {
    fun action(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        val index = selectReplyAction(
            BooleanArray(actions.size) { !actions[it].remoteInputs.isNullOrEmpty() },
            BooleanArray(actions.size) {
                Build.VERSION.SDK_INT >= 28 &&
                    actions[it].semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
            },
            Array(actions.size) { actions[it].title }
        )
        return actions.getOrNull(index)
    }

    fun send(context: Context, action: Notification.Action, text: CharSequence): Boolean {
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
        val pending = action.actionIntent ?: return false
        val fillIn = Intent()
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode =
                    if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    } else {
                        @Suppress("DEPRECATION")
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }
            }.toBundle()
        } else null
        return try {
            pending.send(context, 0, fillIn, null, null, null, options)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }
}
