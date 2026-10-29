package com.ken1dot.spartanbar

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDismissalTest {
    @Test fun ordinaryAlertsCanBeDismissed() {
        assertTrue(NotificationDismissal.canDismiss(true, 0))
        assertTrue(NotificationDismissal.canDismiss(true, Notification.FLAG_AUTO_CANCEL))
    }

    @Test fun systemNonClearableAlertsStay() {
        assertFalse(NotificationDismissal.canDismiss(false, 0))
    }

    @Test fun persistentAndForegroundAlertsStayEvenIfMarkedClearable() {
        listOf(Notification.FLAG_ONGOING_EVENT, Notification.FLAG_NO_CLEAR,
            Notification.FLAG_FOREGROUND_SERVICE).forEach {
            assertFalse(NotificationDismissal.canDismiss(true, it))
            assertFalse(NotificationDismissal.canDismiss(true, it or Notification.FLAG_AUTO_CANCEL))
        }
    }
}
