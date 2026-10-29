package com.ken1dot.spartanbar

import android.app.Notification

internal object NotificationDismissal {
    fun canDismiss(clearable: Boolean, flags: Int): Boolean =
        clearable && flags and (Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE) == 0
}
