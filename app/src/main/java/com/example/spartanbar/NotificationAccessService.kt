package com.example.spartanbar

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationAccessService : NotificationListenerService() {
    companion object {
        var instance: NotificationAccessService? = null
            private set
        var onChanged: (() -> Unit)? = null
        var onPosted: ((StatusBarNotification) -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())
        private fun notifyChanged() {
            mainHandler.post { onChanged?.invoke() }
        }

        fun openNotification(context: Context, sbn: StatusBarNotification): Boolean {
            val intent = sbn.notification.contentIntent ?: return false
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
                intent.send(context, 0, null, null, null, null, options)
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        notifyChanged()
    }

    override fun onListenerDisconnected() {
        instance = null
        notifyChanged()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        notifyChanged()
        if (sbn != null) mainHandler.post { onPosted?.invoke(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = notifyChanged()

    fun dismissNotifications(keys: Array<String>) {
        if (keys.isEmpty()) return
        val requested = keys.toSet()
        try {
            val dismissible = activeNotifications.orEmpty().filter {
                it.key in requested && it.packageName != packageName &&
                    NotificationDismissal.canDismiss(it.isClearable, it.notification.flags)
            }.map { it.key }.toTypedArray()
            if (dismissible.isNotEmpty()) cancelNotifications(dismissible)
        } catch (_: SecurityException) {
            // Notification access may have been revoked during a dismissal animation.
        }
    }

    fun liveActivityNotifications(): List<StatusBarNotification> =
        try {
            activeNotifications.orEmpty().filter { it.packageName != packageName }
        } catch (_: SecurityException) {
            emptyList()
        }

    fun visibleNotifications(): List<StatusBarNotification> =
        try {
            activeNotifications.orEmpty()
                .filter {
                    it.packageName != packageName &&
                        it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
                }
                .sortedByDescending { it.postTime }
        } catch (_: SecurityException) {
            emptyList()
        }
}
