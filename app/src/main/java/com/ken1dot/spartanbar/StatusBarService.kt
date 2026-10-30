package com.ken1dot.spartanbar

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.SignalStrength
import android.telephony.PhoneStateListener
import androidx.core.content.ContextCompat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MetroAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_UPDATE = "com.ken1dot.spartanbar.UPDATE"
        const val ACTION_DEBUG_CALL_STRIP = "com.ken1dot.spartanbar.DEBUG_CALL_STRIP"
        private const val LOCK_SCREEN_PACKAGE = "com.ken1dot.spartanlock"
        private val TRANSIENT_PACKAGES = setOf("com.sec.android.app.launcher", "com.android.launcher3",
        "com.google.android.apps.nexuslauncher", "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin")
    private val LAUNCHER_ACCENT_URI =
            Uri.parse("content://com.ken1dot.spartanlauncher.accent/color")
        private val DEFAULT_ACCENT = Color.rgb(0, 120, 215)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val accentExecutor = Executors.newSingleThreadExecutor()
    private lateinit var windowManager: WindowManager
    private var bar: MetroBarView? = null
    private var pullDownHandle: View? = null
    private var actionCenter: ActionCenterView? = null
    private var actionCenterVisible = false
    private var pullingActionCenter = false
    private var notificationToast: MetroNotificationToastView? = null
    private var liveActivity: MetroLiveActivity? = null
    private var liveActivityPanel: MetroLiveActivityView? = null
    private var toastTimeout: Runnable? = null
    private var accentColor = DEFAULT_ACCENT
    private var lastToastKey: String? = null
    private var lastToastAt = 0L
    private var lockScreenVisible = false
    private var fullscreenHidden = false
    private var callStrip: CallStripView? = null
    private var activeCall: MetroLiveActivity? = null
    private var debugCall: CallStripData? = null
    /** Call notifications posted before this are treated as ended (stale). */
    private var callEndedAt = 0L
    private var notInCallTicks = 0
    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d("SpartanBarCall", "CALL_ENDED from phone")
            markCallEnded()
        }
    }

    private fun markCallEnded() {
        callEndedAt = System.currentTimeMillis()
        notInCallTicks = 0
        activeCall = null
        updateCallStrip()
    }

    /** Sanity check once a second: strip up but audio no longer in a call for 5s -> stale. */
    private fun checkCallStale() {
        if (activeCall == null || debugCall != null) { notInCallTicks = 0; return }
        val mode = (getSystemService(AUDIO_SERVICE) as? android.media.AudioManager)?.mode
        val inCall = mode == android.media.AudioManager.MODE_IN_CALL ||
            mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            mode == android.media.AudioManager.MODE_RINGTONE ||
            (Build.VERSION.SDK_INT >= 33 && mode == android.media.AudioManager.MODE_CALL_SCREENING)
        notInCallTicks = if (inCall) 0 else notInCallTicks + 1
        if (notInCallTicks >= 5) {
            android.util.Log.d("SpartanBarCall", "audio mode $mode for 5s, hiding stale call strip")
            markCallEnded()
        }
    }
    private val clearDebugCall = Runnable { debugCall = null; updateCallStrip() }
    private val debugCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // --es left "Song - Artist" --es right "Spotify" previews the live-activity strip.
            debugCall = if (intent.getBooleanExtra("show", true)) CallStripData(
                left = intent.getStringExtra("left"),
                right = intent.getStringExtra("right"),
                name = intent.getStringExtra("name").orEmpty(),
                startedAtMillis = System.currentTimeMillis() - intent.getIntExtra("seconds", 38) * 1000L,
                held = intent.getBooleanExtra("held", false),
                carrier = intent.getStringExtra("carrier").orEmpty(),
            ) else null
            // Debug preview never lingers: auto-clears after 20s.
            mediaHandler.removeCallbacks(clearDebugCall)
            if (debugCall != null) mediaHandler.postDelayed(clearDebugCall, 20_000L)
            updateCallStrip()
        }
    }
    private var foregroundPackage: String? = null
    private var cachedHomePackage: String? = null
    private var homePackageCached = false
    private var colorGeneration = 0
    private fun isVideoApp() = foregroundPackage?.let {
        it == "com.google.android.youtube" || it == "com.google.android.youtube.tv" ||
            it.contains("youtube", ignoreCase = true)
    } == true
    private var statusBarHeightPx = 0
    private var sampleInFlight = false
    private var lastSampleAt = 0L
    private var sampleBurstUntil = 0L
    private var matchedAppColor: Int? = null
    private var scrollingUntil = 0L
    private val colorFilter = StatusBarColorFilter()
    private val sampleColor = Runnable { captureStatusBarColor() }
    private val refresh = object : Runnable {
        override fun run() {
            restoreOverlayVisibility()
            bar?.invalidate()
            liveActivityPanel?.tick()
            if (activeCall == null && debugCall == null && liveActivity != null) updateCallStrip()
            else callStrip?.invalidate()
            checkCallStale()
            actionCenter?.tickNowPlaying()
            handler.postDelayed(this, 1000)
        }
    }
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBar()
            colorGeneration++
            scheduleColorSample()
        }
    }

    private fun keyguardLocked(): Boolean = try {
        getSystemService(KeyguardManager::class.java).isKeyguardLocked
    } catch (_: Exception) { false }

    // Re-evaluate the music/live strip when the screen locks or unlocks so it never shows on the lock screen.
    private val keyguardReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (keyguardLocked() || intent?.action == Intent.ACTION_SCREEN_OFF) closeLiveActivityPanel()
            updateCallStrip()
            handler.postDelayed({ updateCallStrip() }, 600L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(this, keyguardReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(ACTION_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateBar()
        scheduleColorSample()
        // Sent by Spartan Phone the moment a call ends (only hides the strip, so no permission needed).
        ContextCompat.registerReceiver(this, callEndedReceiver,
            IntentFilter("com.example.spartanphone.CALL_ENDED"), ContextCompat.RECEIVER_EXPORTED)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // adb shell am broadcast -a com.ken1dot.spartanbar.DEBUG_CALL_STRIP [--es name X --ei seconds N --ez held true --ez show false]
            ContextCompat.registerReceiver(this, debugCallReceiver,
                IntentFilter(ACTION_DEBUG_CALL_STRIP), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /** W10M in-call status bar: accent top row (MetroBarView call mode) + CallStripView row. */
    private fun updateCallStrip() {
        val currentBar = bar
        val call = activeCall
        val callPackage = call?.notification?.packageName
        val activity = liveActivity?.takeIf { call == null }
        val ownerPackage = callPackage ?: activity?.notification?.packageName
        val data = debugCall ?: call?.let {
            val detail = it.detail
            CallStripData(
                name = it.title,
                startedAtMillis = it.startedAtMillis,
                held = detail.equals("On hold", ignoreCase = true),
                carrier = it.notification.notification.extras
                    .getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            )
        } ?: activity?.let { activityStripData(it) }
        val onCallScreen = debugCall == null && ownerPackage != null && foregroundPackage == ownerPackage
        // Spartan Music already shows its own now-playing UI; hide the music strip while it is foreground.
        val inMusicApp = debugCall == null && call == null && activity?.kind == MetroLiveActivity.Kind.MUSIC &&
            foregroundPackage == "com.example.spartanmusic"
        val show = currentBar != null && data != null && !onCallScreen && !lockScreenVisible && !(activity != null && call == null && keyguardLocked()) && !actionCenterVisible && !inMusicApp
        if (!show || data == null) {
            currentBar?.setCallAccent(null)
            callStrip?.let { strip -> try { windowManager.removeView(strip) } catch (_: Exception) { } }
            callStrip = null
            StatusBarBridgeState.setInset(this, if (currentBar != null) statusBarHeightPx else 0)
            return
        }
        StatusBarBridgeState.setInset(this, statusBarHeightPx + callRowHeight())
        currentBar?.setCallAccent(accentColor)
        val strip = callStrip ?: CallStripView(this) {
            val target = activeCall ?: liveActivity
            if (target != null) NotificationAccessService.openNotification(this, target.notification)
        }.also { view ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                callRowHeight(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                y = statusBarHeightPx
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try { windowManager.addView(view, params); callStrip = view } catch (_: Exception) { }
        }
        strip.accent = accentColor
        strip.data = data
    }

    /** Second-row text for a live activity, W10M call-strip style. */
    private fun activityStripData(a: MetroLiveActivity): CallStripData {
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(a.notification.packageName, 0)).toString()
        } catch (_: Exception) { "" }
        val left = when (a.kind) {
            MetroLiveActivity.Kind.MUSIC ->
                listOf(a.title, a.detail).filter { it.isNotBlank() }.joinToString(" - ")
            MetroLiveActivity.Kind.TIMER -> a.compactText() + "  Timer"
            MetroLiveActivity.Kind.STOPWATCH -> a.compactText() + "  Stopwatch"
            MetroLiveActivity.Kind.ALARM -> "Alarm  " + a.compactText()
            MetroLiveActivity.Kind.CALL -> a.title
        }
        return CallStripData(name = "", startedAtMillis = null, held = false, carrier = "",
            left = left, right = appName)
    }

    /** W10M strip = 0.157 x screen width in total (measured on a Lumia 650 frame); this is the part below the normal bar. */
    private fun callRowHeight(): Int {
        val w = resources.displayMetrics.widthPixels
        return ((w * 0.157f).toInt() - statusBarHeightPx).coerceAtLeast((26 * resources.displayMetrics.density).toInt())
    }

    private fun updateBar() {
        val enabled = getSharedPreferences("status_bar", MODE_PRIVATE).getBoolean("enabled", true)
        if (!enabled) {
            removeBar()
            return
        }
        if (bar != null) {
            restoreOverlayVisibility()
            bar?.refreshSignalAccess()
            return
        }

        val fallbackHeight = (24 * resources.displayMetrics.density).toInt()
        val statusBarHeight = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
                .takeIf { it > 0 } ?: fallbackHeight
        } else {
            resources.getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it != 0 }?.let { resources.getDimensionPixelSize(it) } ?: fallbackHeight
        }
        statusBarHeightPx = statusBarHeight
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        bar = MetroBarView(this, ::handlePull, ::toggleLiveActivity, ::showLowBattery).apply {
            setLockScreenMode(lockScreenVisible)
        }

        windowManager.addView(bar, params)
        StatusBarBridgeState.setActive(this, true)
        val density = resources.displayMetrics.density
        val handleParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.6f).toInt(),
            (24f * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight
        }
        pullDownHandle = PullDownGestureView(this, ::handlePull)
        windowManager.addView(pullDownHandle, handleParams)
        showActionCenter()
        handler.post(refresh)
        scheduleColorSample()
    }

    private fun removeBar() {
        StatusBarBridgeState.setActive(this, false)
        NotificationAccessService.onChanged = null
        NotificationAccessService.onPosted = null
        dismissNotificationToast(false)
        closeLiveActivityPanel()
        liveActivity = null
        actionCenter?.let { windowManager.removeView(it) }
        actionCenter = null
        actionCenterVisible = false
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(sampleColor)
        pullDownHandle?.let { windowManager.removeView(it) }
        pullDownHandle = null
        fullscreenHidden = false
        bar?.let { windowManager.removeView(it) }
        bar = null
    }

    private fun handlePull(distancePx: Float, released: Boolean) {
        if (bar == null || fullscreenHidden) return
        if (distancePx > 0f && !actionCenterVisible) {
            pullingActionCenter = true
            closeLiveActivityPanel()
            dismissNotificationToast(false)
            requestAccentRefresh()
            actionCenterVisible = true
            updateCallStrip()
            colorGeneration++
            handler.removeCallbacks(sampleColor)
            bar?.setAppIconBrightness(null)
            bar?.setBarColor(Color.BLACK)
        }
        if (!pullingActionCenter) return
        actionCenter?.setDragDistance(distancePx)
        if (released) {
            pullingActionCenter = false
            val panel = actionCenter ?: return
            val open = distancePx >= 24f * resources.displayMetrics.density
            if (open) {
                val params = panel.layoutParams as WindowManager.LayoutParams
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager.updateViewLayout(panel, params)
            }
            val v = (pullDownHandle as? PullDownGestureView)?.releaseVelocity ?: 0f
            panel.settle(open, v) { if (!open) closeActionCenter() }
        }
    }

    private fun actionCenterHeight(): Int = if (Build.VERSION.SDK_INT >= 30) {
        val metrics = windowManager.currentWindowMetrics
        val navigation = metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
        (metrics.bounds.height() - statusBarHeightPx - navigation.bottom).coerceAtLeast(1)
    } else (resources.displayMetrics.heightPixels - statusBarHeightPx).coerceAtLeast(1)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bar == null) return
        statusBarHeightPx = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
                .coerceAtLeast((24 * resources.displayMetrics.density).toInt())
        } else (24 * resources.displayMetrics.density).toInt()
        bar?.let {
            val params = it.layoutParams as WindowManager.LayoutParams
            params.height = statusBarHeightPx
            windowManager.updateViewLayout(it, params)
        }
        pullDownHandle?.let {
            val params = it.layoutParams as WindowManager.LayoutParams
            params.y = statusBarHeightPx
            windowManager.updateViewLayout(it, params)
        }
        actionCenter?.let {
            val params = it.layoutParams as WindowManager.LayoutParams
            params.y = statusBarHeightPx
            params.height = actionCenterHeight()
            windowManager.updateViewLayout(it, params)
        }
    }

    private fun showActionCenter() {
        if (actionCenter != null || bar == null) return
        val panel = ActionCenterView(this, ::closeActionCenter) { action ->
            closeActionCenter()
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // The setting is unavailable on this device.
            }
        }
        panel.onMusicTrack = { title, art -> bar?.setMusicTrack(title, art) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            actionCenterHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = statusBarHeightPx
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        actionCenter = panel
        windowManager.addView(panel, params)
        requestAccentRefresh()
        NotificationAccessService.onChanged = {
            panel.refreshNotifications()
            updateLiveActivity()
        }
        NotificationAccessService.onPosted = { showNotificationToast(it) }
        updateLiveActivity()
    }

    // Music strip only for local sessions that are actually PLAYING/BUFFERING (not cast/remote,
    // not stale paused notifications). Paused/stopped hides after a short grace period.
    private val mediaHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mediaControllers = HashMap<android.media.session.MediaSession.Token, android.media.session.MediaController>()
    private val lastPlayingAt = HashMap<android.media.session.MediaSession.Token, Long>()
    private val mediaWatch = object : android.media.session.MediaController.Callback() {
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) { scheduleMediaRecheck() }
        override fun onAudioInfoChanged(info: android.media.session.MediaController.PlaybackInfo) { scheduleMediaRecheck() }
        override fun onSessionDestroyed() { scheduleMediaRecheck() }
    }
    private val mediaRecheck = Runnable { updateLiveActivity() }
    private fun scheduleMediaRecheck() {
        mediaHandler.removeCallbacks(mediaRecheck)
        mediaHandler.post(mediaRecheck)
    }
    private fun musicReallyPlaying(a: MetroLiveActivity): Boolean {
        val token = a.notification.notification.extras.let {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                it.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
            else @Suppress("DEPRECATION") it.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION)
        } ?: return false
        val c = mediaControllers.getOrPut(token) {
            try { android.media.session.MediaController(this, token).also { it.registerCallback(mediaWatch, mediaHandler) } }
            catch (_: Exception) { return false }
        }
        val st = c.playbackState?.state
        val playing = (st == android.media.session.PlaybackState.STATE_PLAYING ||
            st == android.media.session.PlaybackState.STATE_BUFFERING) &&
            c.playbackInfo?.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        val now = android.os.SystemClock.uptimeMillis()
        if (playing) { lastPlayingAt[token] = now; return true }
        val last = lastPlayingAt[token] ?: return false
        val left = 3000L - (now - last)
        if (left <= 0) { lastPlayingAt.remove(token); return false }
        mediaHandler.postDelayed(mediaRecheck, left + 50)
        return true
    }

    private fun updateLiveActivity() {
        val candidates = NotificationAccessService.instance?.liveActivityNotifications()
            ?.mapNotNull { MetroLiveActivity.from(it, this) }.orEmpty()
        activeCall = candidates.filter {
            it.kind == MetroLiveActivity.Kind.CALL && it.notification.postTime > callEndedAt
        }
            .maxByOrNull { it.notification.postTime }
        updateCallStrip()
        // Priority (Kenneth): call > alarm/timer > stopwatch > music, one at a time.
        val selected = candidates.filter { it.kind == MetroLiveActivity.Kind.ALARM }
            .maxByOrNull { it.notification.postTime }
            ?: candidates.filter { it.kind == MetroLiveActivity.Kind.TIMER }
                .minByOrNull { it.endsAtMillis ?: Long.MAX_VALUE }
            ?: candidates.filter { it.kind == MetroLiveActivity.Kind.STOPWATCH }
                .maxByOrNull { it.notification.postTime }
            ?: candidates.filter { it.kind == MetroLiveActivity.Kind.MUSIC && musicReallyPlaying(it) }
                .maxByOrNull { it.notification.postTime }
        liveActivity = selected
        updateCallStrip()
        // The W10M-style strip replaces the old chip.
        bar?.setLiveActivity(null, accentColor)
        val music = candidates.filter { it.kind == MetroLiveActivity.Kind.MUSIC }
            .maxByOrNull { it.notification.postTime }
        actionCenter?.setNowPlaying(music, accentColor)
        if (selected == null || lockScreenVisible || keyguardLocked()) closeLiveActivityPanel()
        else liveActivityPanel?.setActivity(selected)
    }

    private fun toggleLiveActivity() {
        val current = liveActivity ?: return
        if (current.kind == MetroLiveActivity.Kind.CALL) {
            // W10M: tapping the in-call status bar jumps straight back to the call.
            closeLiveActivityPanel()
            NotificationAccessService.openNotification(this, current.notification)
            return
        }
        if (liveActivityPanel != null) {
            closeLiveActivityPanel(true)
            return
        }
        if (actionCenterVisible || lockScreenVisible || fullscreenHidden || notificationToast != null) return
        val panel = MetroLiveActivityView(this,
            onOpen = { activity ->
                NotificationAccessService.openNotification(this, activity.notification)
                closeLiveActivityPanel()
            },
            onAction = { intent: PendingIntent ->
                try { intent.send() } catch (_: PendingIntent.CanceledException) { }
                handler.postDelayed({ updateLiveActivity() }, 350L)
            },
            onClose = { closeLiveActivityPanel(true) }
        ).apply {
            setAccentColor(accentColor)
            setActivity(current)
        }
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx
        }
        liveActivityPanel = panel
        windowManager.addView(panel, params)
        panel.alpha = 0f
        panel.translationY = -24f * density
        panel.animate().alpha(1f).translationY(0f)
            .setDuration(200L).setInterpolator(MetroMotion.Decel).start()
    }

    private fun closeLiveActivityPanel(animated: Boolean = false) {
        val panel = liveActivityPanel ?: return
        liveActivityPanel = null
        panel.animate().cancel()
        if (animated && panel.parent != null) {
            panel.animate().alpha(0f).translationY(-panel.height.toFloat())
                .setDuration(180L).setInterpolator(MetroMotion.Accel).withEndAction {
                    if (panel.parent != null) windowManager.removeView(panel)
                }.start()
        } else if (panel.parent != null) windowManager.removeView(panel)
    }

    private fun showLowBattery(percent: Int) {
        val notification = Notification.Builder(this, "battery_alert")
            .setSmallIcon(R.drawable.metro_battery_0)
            .setContentTitle("Battery low")
            .setContentText("$percent% remaining · Connect your charger")
            .setContentIntent(PendingIntent.getActivity(this, 90,
                Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        val sbn = StatusBarNotification(packageName, packageName, 90, "battery",
            android.os.Process.myUid(), 0, 0, notification,
            android.os.Process.myUserHandle(), System.currentTimeMillis())
        showNotificationToast(sbn, internalAlert = true)
    }

    private fun showNotificationToast(sbn: StatusBarNotification, internalAlert: Boolean = false) {
        if (bar == null || actionCenterVisible || fullscreenHidden) return
        val notification = sbn.notification
        if (MetroLiveActivity.from(sbn, this) != null) return
        if ((!internalAlert && sbn.packageName == packageName) || notification.contentIntent == null ||
            notification.flags and (Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        val message = notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title.isNullOrBlank() && message.isNullOrBlank()) return
        val now = SystemClock.uptimeMillis()
        if (lastToastKey == sbn.key && now - lastToastAt < 8000L) return
        lastToastKey = sbn.key
        lastToastAt = now

        closeLiveActivityPanel()
        dismissNotificationToast(false)
        requestAccentRefresh()
        val onLockScreen = lockScreenVisible ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val toast = MetroNotificationToastView(this, sbn, !onLockScreen,
            onOpen = {
                if (NotificationAccessService.openNotification(this, sbn)) {
                    NotificationAccessService.instance?.dismissNotifications(arrayOf(sbn.key))
                }
                dismissNotificationToast(true)
            },
            onDismiss = { dismissNotificationToast(true) },
            onExpandedChanged = { expanded ->
                if (expanded) enableToastInput()
                else restartToastTimeout()
            }
        ).apply { setAccentColor(accentColor) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = statusBarHeightPx
        }
        toast.translationY = -400f * resources.displayMetrics.density
        notificationToast = toast
        colorGeneration++
        bar?.setToastColor(accentColor)
        bar?.setToastMode(true)
        handler.removeCallbacks(sampleColor)
        windowManager.addView(toast, params)
        toast.post {
            if (notificationToast === toast) {
                val travel = toast.height.takeIf { it > 0 } ?: toast.measuredHeight
                if (travel > 0) toast.translationY = -travel.toFloat()
                toast.animate().translationY(0f).setDuration(240L)
                    .setInterpolator(MetroMotion.Decel).start()
            }
        }
        restartToastTimeout()
    }

    private fun restartToastTimeout() {
        toastTimeout?.let { handler.removeCallbacks(it) }
        val timeout = Runnable { dismissNotificationToast(true) }
        toastTimeout = timeout
        handler.postDelayed(timeout, 4500L)
    }

    private fun enableToastInput() {
        val toast = notificationToast ?: return
        toastTimeout?.let { handler.removeCallbacks(it) }
        toastTimeout = null
        // Accessibility overlays start NOT_FOCUSABLE; reply needs a focusable window + IME.
        // Prefer ADJUST_RESIZE only — STATE_VISIBLE sticks the IME after the overlay is gone.
        val params = toast.layoutParams as WindowManager.LayoutParams
        params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        @Suppress("DEPRECATION")
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        toast.isFocusable = true
        toast.isFocusableInTouchMode = true
        windowManager.updateViewLayout(toast, params)
    }

    private fun dismissNotificationToast(animated: Boolean) {
        toastTimeout?.let { handler.removeCallbacks(it) }
        toastTimeout = null
        val toast = notificationToast ?: return
        notificationToast = null
        toast.releaseInput()
        try {
            val params = toast.layoutParams as WindowManager.LayoutParams
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            toast.isFocusable = false
            toast.isFocusableInTouchMode = false
            if (toast.parent != null) {
                windowManager.updateViewLayout(toast, params)
                // Hide again after the window is no longer focusable so IME detaches cleanly.
                toast.releaseInput()
            }
        } catch (_: RuntimeException) { }
        fun finish() {
            toast.releaseInput()
            if (toast.parent != null) windowManager.removeView(toast)
            if (notificationToast == null) {
                bar?.setToastMode(false)
                scheduleColorSample()
            }
        }
        toast.animate().cancel()
        if (animated) {
            toast.animate().translationY(-toast.height.toFloat())
                .setDuration(200L).setInterpolator(MetroMotion.Accel).withEndAction { finish() }.start()
        } else finish()
    }

    private fun requestAccentRefresh() {
        accentExecutor.execute {
            val color = try {
                contentResolver.query(LAUNCHER_ACCENT_URI, arrayOf("color"),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow("color"))
                    else null
                } ?: DEFAULT_ACCENT
            } catch (_: RuntimeException) {
                DEFAULT_ACCENT
            }
            handler.post {
                accentColor = color
        updateCallStrip()
                actionCenter?.setAccentColor(color)
                notificationToast?.setAccentColor(color)
                liveActivityPanel?.setAccentColor(color)
                bar?.setLiveActivity(null, color)
                bar?.setToastColor(color)
            }
        }
    }

    private fun closeActionCenter() {
        actionCenterVisible = false
        updateCallStrip()
        pullingActionCenter = false
        colorGeneration++
        actionCenter?.let { panel ->
            panel.setDragDistance(0f)
            val params = panel.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(panel, params)
        }
        scheduleColorSample()
    }

    /** Home (Spartan Launcher / default home) always gets a transparent bar. */
    private fun isHome(): Boolean = foregroundPackage != null &&
        (foregroundPackage == "com.ken1dot.spartanlauncher" || foregroundPackage == defaultHomePackage())

    private fun applySharedAppearance(): Boolean {
        val app = foregroundPackage ?: return false
        if (isHome()) return false
        val preferences = getSharedPreferences("shared_bar_appearance", MODE_PRIVATE)
        if (!preferences.contains("$app.color")) return false
        android.util.Log.i("SpartanBarShared", "apply app=$app color=${Integer.toHexString(preferences.getInt("$app.color", 0))}")
        bar?.setAppIconBrightness(if (preferences.contains("$app.dark_icons"))
            preferences.getBoolean("$app.dark_icons", false) else null)
        bar?.setBarColor(preferences.getInt("$app.color", Color.BLACK))
        return true
    }

    private fun defaultHomePackage(): String? {
        if (homePackageCached) return cachedHomePackage
        cachedHomePackage = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        homePackageCached = true
        return cachedHomePackage
    }

    private fun scheduleColorSample() {
        handler.removeCallbacks(sampleColor)
        if (bar == null || actionCenterVisible || lockScreenVisible || notificationToast != null) return
        if (applySharedAppearance()) return
        // A transparent overlay belongs on Home; other apps get a stable black background.
        val isHome = isHome()
        val darkIcons = if (isHome) {
            val preferences = getSharedPreferences("shared_bar_appearance", MODE_PRIVATE)
            foregroundPackage?.let { app ->
                if (preferences.contains("$app.dark_icons"))
                    preferences.getBoolean("$app.dark_icons", false) else null
            }
        } else null
        bar?.setAppIconBrightness(darkIcons)
        bar?.setBarColor(if (isHome) Color.TRANSPARENT else Color.BLACK)
    }

    private fun captureStatusBarColor() {
        if (Build.VERSION.SDK_INT < 30 || bar == null || actionCenterVisible ||
            lockScreenVisible || notificationToast != null || isVideoApp() || isHome()) return
        if (applySharedAppearance() || sampleInFlight ||
            SystemClock.uptimeMillis() < scrollingUntil) return
        val generation = colorGeneration
        val sampledBar = bar
        sampleInFlight = true
        lastSampleAt = SystemClock.uptimeMillis()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    sampleInFlight = false
                    val buffer = result.hardwareBuffer
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        if (bitmap != null) {
                            try {
                                if (generation == colorGeneration && bar === sampledBar &&
                                    !actionCenterVisible && !lockScreenVisible &&
                                    notificationToast == null && !isVideoApp() && !isHome() &&
                                    SystemClock.uptimeMillis() >= scrollingUntil &&
                                    !applySharedAppearance()) {
                                    // Match the actual system-bar background, not scrolling app content.
                                    val x = ((sampledBar?.width ?: bitmap.width) * MetroBarView.SAMPLE_X_FRACTION)
                                        .toInt()
                                    val y = (sampledBar?.height ?: statusBarHeightPx) / 2
                                    val colors = IntArray(9) { index ->
                                        bitmap.getPixel((x + index % 3 - 1).coerceIn(0, bitmap.width - 1),
                                            (y + index / 3 - 1).coerceIn(0, bitmap.height - 1))
                                    }
                                    val color = colorFilter.sample(StatusBarColorFilter.median(colors),
                                        SystemClock.uptimeMillis(), generation)
                                    if (color != null) {
                                        android.util.Log.d("SpartanBarColor",
                                            "matched package=$foregroundPackage color=${Integer.toHexString(color)}")
                                        matchedAppColor = color
                                        sampledBar?.setBarColor(color)
                                        handler.removeCallbacks(sampleColor)
                                    }
                                }
                            } finally { bitmap.recycle() }
                        }
                    } catch (_: RuntimeException) {
                        // Retain the last color on secure screens or failed captures.
                    } finally {
                        buffer.close()
                        if (SystemClock.uptimeMillis() < sampleBurstUntil) scheduleColorSample()
                    }
                }
                override fun onFailure(errorCode: Int) { sampleInFlight = false }
            })
        } catch (_: SecurityException) { sampleInFlight = false }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        // Our notification and live-activity overlays are not foreground app changes.
        if (eventPackage == "android" ||
            (eventPackage == packageName && event.className?.toString() != MainActivity::class.java.name)) return
        // Overlay windows (e.g. a companion app's floating call UI) also fire window-state events;
        // only real activities change the foreground app, so they can't steal the bar color.
        val isActivityWindow = event.className?.toString()?.let { cls ->
            try { packageManager.getActivityInfo(android.content.ComponentName(eventPackage, cls), 0); true }
            catch (_: Exception) { false }
        } ?: false
        if (isActivityWindow) android.util.Log.d("SpartanBarColor", "window event $eventPackage/${event.className}")
        else android.util.Log.d("SpartanBarColor", "ignored non-activity window $eventPackage/${event.className}")
        // Recents / Samsung overlay launchers are transient: they must not replace Home or the app.
        val transient = eventPackage in TRANSIENT_PACKAGES && eventPackage != defaultHomePackage()
        if (isActivityWindow && !transient && eventPackage != "com.android.systemui" && eventPackage != foregroundPackage) {
            android.util.Log.d("SpartanBarColor", "foreground=$eventPackage previous=$foregroundPackage")
            foregroundPackage = eventPackage
            homePackageCached = false
            colorGeneration++
            matchedAppColor = null
            scrollingUntil = 0L
            sampleBurstUntil = SystemClock.uptimeMillis() + 3000L
            handler.removeCallbacks(sampleColor)
            updateCallStrip()
        }
        restoreOverlayVisibility()
        if (eventPackage == LOCK_SCREEN_PACKAGE) {
            setLockScreenVisible(true)
        } else if (eventPackage != "com.android.systemui") {
            setLockScreenVisible(false)
        }
        // Same-app window changes keep the current foreground package.
        scheduleColorSample()
    }

    private fun restoreOverlayVisibility() {
        // Overlay insets do not reliably describe the foreground app's fullscreen state.
        fullscreenHidden = false
        val currentBar = bar ?: return
        currentBar.alpha = 1f
        currentBar.visibility = View.VISIBLE
        val params = currentBar.layoutParams as? WindowManager.LayoutParams
        if (params != null &&
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(currentBar, params)
        }
        pullDownHandle?.visibility = View.VISIBLE
    }

    private fun setLockScreenVisible(visible: Boolean) {
        if (lockScreenVisible == visible) return
        lockScreenVisible = visible
        updateCallStrip()
        bar?.setLockScreenMode(visible)
        if (visible) {
            closeActionCenter()
            handler.removeCallbacks(sampleColor)
            closeLiveActivityPanel()
            dismissNotificationToast(false)
        } else scheduleColorSample()
    }
    private var volumePanel: VolumePanelView? = null
    private val hideVolume = Runnable { hideVolumePanel() }

    private fun pokeVolumeTimer() {
        handler.removeCallbacks(hideVolume)
        handler.postDelayed(hideVolume, 3_000L)
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (code != android.view.KeyEvent.KEYCODE_VOLUME_UP && code != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return false
        // Calls and alarms keep the system behaviour.
        if (audio.mode == android.media.AudioManager.MODE_IN_CALL || audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) return false
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return true
        val stream = if (audio.isMusicActive) android.media.AudioManager.STREAM_MUSIC else android.media.AudioManager.STREAM_RING
        val dir = if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        try {
            audio.adjustStreamVolume(stream, dir, 0)
        } catch (_: SecurityException) {
            // Do Not Disturb blocks ringer changes without policy access: let the system handle it.
            return false
        }
        showVolumePanel(stream)
        return true
    }

    private fun showVolumePanel(stream: Int) {
        volumePanel?.let { it.focusStream = stream; it.accent = accentColor; it.invalidate(); pokeVolumeTimer(); return }
        val inset = 0 // the panel window starts under the bar, which stays on top
        lateinit var panel: VolumePanelView
        panel = VolumePanelView(this, inset, accentColor, stream, ::pokeVolumeTimer) { h ->
            (panel.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.height = h
                try { windowManager.updateViewLayout(panel, p) } catch (_: Exception) { }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panel.panelHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = statusBarHeightPx
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        panel.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) { hideVolumePanel(); true } else false
        }
        try { windowManager.addView(panel, params); volumePanel = panel } catch (_: Exception) { return }
        // W10M keeps the status bar visible above the volume panel. The panel window begins
        // below the bar, so the bar never has to be re-added (no flicker); it just fades to the panel grey.
        bar?.setVolumeBackdrop(Color.rgb(0x2b, 0x2b, 0x2b))
        pokeVolumeTimer()
    }

    private fun hideVolumePanel() {
        handler.removeCallbacks(hideVolume)
        val panel = volumePanel ?: return
        volumePanel = null
        panel.slideAway {
            bar?.setVolumeBackdrop(null)
            try { windowManager.removeView(panel) } catch (_: Exception) { }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        try { unregisterReceiver(keyguardReceiver) } catch (_: IllegalArgumentException) { }
        unregisterReceiver(updateReceiver)
        try { unregisterReceiver(debugCallReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(callEndedReceiver) } catch (_: IllegalArgumentException) { }
        callStrip?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        callStrip = null
        mediaHandler.removeCallbacksAndMessages(null)
        mediaControllers.values.forEach { try { it.unregisterCallback(mediaWatch) } catch (_: Exception) { } }
        mediaControllers.clear()
        volumePanel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        volumePanel = null
        removeBar()
        accentExecutor.shutdownNow()
        super.onDestroy()
    }
}

internal class MetroBarView(
    context: Context,
    private val onDrag: (Float, Boolean) -> Unit,
    private val onLiveActivityTap: () -> Unit,
    private val onLowBattery: (Int) -> Unit
) : View(context) {
    companion object {
        const val SIGNAL_X = 22f
        const val SLOT_DP = 22f
        // W10M glyphs read ~55% of bar height (measured from reference frames).
        const val ICON_SCALE = 0.9f
        // Keep the transparent sample pixel clear of the live chip and camera cutout.
        const val SAMPLE_X_FRACTION = 0.7f
    }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint()
    private val signalIcons = listOf(
        R.drawable.mdl2_signal_0, R.drawable.mdl2_signal_1, R.drawable.mdl2_signal_2,
        R.drawable.mdl2_signal_3, R.drawable.mdl2_signal_4
    ).map { requireNotNull(ContextCompat.getDrawable(context, it)) }
    private val wifiIcons = listOf(
        R.drawable.mdl2_wifi_0, R.drawable.mdl2_wifi_1,
        R.drawable.mdl2_wifi_2, R.drawable.mdl2_wifi_3
    ).map { requireNotNull(ContextCompat.getDrawable(context, it)) }
    private val vibrateIcon = requireNotNull(ContextCompat.getDrawable(context, R.drawable.mdl2_vibrate))
    private val muteIcon = requireNotNull(ContextCompat.getDrawable(context, R.drawable.mdl2_mute))
    private val wifiIcon = requireNotNull(ContextCompat.getDrawable(context, R.drawable.metro_wifi))
    private val fourGIcon = requireNotNull(ContextCompat.getDrawable(context, R.drawable.metro_4g))
    private val chargingIcons = listOf(
        R.drawable.metro_battery_charging, R.drawable.metro_battery_charging_1,
        R.drawable.metro_battery_charging_2, R.drawable.metro_battery_charging_3
    ).map { requireNotNull(ContextCompat.getDrawable(context, it)) }
    private val batteryIcons = listOf(
        R.drawable.metro_battery_0, R.drawable.metro_battery_1,
        R.drawable.metro_battery_2, R.drawable.metro_battery_3
    ).map { requireNotNull(ContextCompat.getDrawable(context, it)) }
    private val batterySaverIcons = listOf(
        R.drawable.metro_battery_saver_0, R.drawable.metro_battery_saver_1,
        R.drawable.metro_battery_saver_2, R.drawable.metro_battery_saver_3
    ).map { requireNotNull(ContextCompat.getDrawable(context, it)) }
    private var barColor = Color.BLACK
    private var chipAnimator: android.animation.ValueAnimator? = null
    private var chipReveal = 0f
    private var targetActivityKey: String? = null
    private var iconColor = Color.WHITE
    private var callAccent: Int? = null

    /** W10M in-call mode: solid accent bar, white icons, no live chip. */
    fun setCallAccent(accent: Int?) {
        val c = accent?.let { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) }
        if (callAccent == c) return
        callAccent = c
        updateIconColor()
        invalidate()
    }
    private var lockScreenMode = false
    private var toastMode = false
    private var toastColor = Color.rgb(0, 120, 215)
    private var liveActivity: MetroLiveActivity? = null
    private var liveAccent = Color.rgb(0, 120, 215)
    private var musicTitle: String? = null
    private var musicArt: Drawable? = null

    fun setMusicTrack(title: String?, art: Drawable?) {
        if (musicTitle == title && musicArt === art) return
        musicTitle = title
        musicArt = art
        invalidate()
    }

    fun setLiveActivity(activity: MetroLiveActivity?, accent: Int) {
        liveAccent = Color.rgb(Color.red(accent), Color.green(accent), Color.blue(accent))
        val key = activity?.notification?.key
        if (targetActivityKey == key) {
            if (activity != null) liveActivity = activity
            invalidate()
            return
        }
        targetActivityKey = key
        chipAnimator?.cancel()
        if (activity != null) liveActivity = activity
        chipAnimator = android.animation.ValueAnimator.ofFloat(chipReveal,
            if (activity != null) 1f else 0f).apply {
            duration = 180L
            interpolator = MetroMotion.Decel
            addUpdateListener {
                chipReveal = it.animatedValue as Float
                if (chipReveal == 0f && targetActivityKey == null) liveActivity = null
                invalidate()
            }
            start()
        }
    }

    fun setToastColor(color: Int) {
        toastColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        if (toastMode) {
            updateIconColor()
            invalidate()
        }
    }

    fun setToastMode(enabled: Boolean) {
        if (toastMode == enabled) return
        toastMode = enabled
        updateIconColor()
        invalidate()
    }

    private var appDarkIcons: Boolean? = null
    fun setAppIconBrightness(darkIcons: Boolean?) {
        if (appDarkIcons == darkIcons) return
        appDarkIcons = darkIcons
        updateIconColor()
        invalidate()
    }

    private fun updateIconColor() {
        iconColor = when {
            lockScreenMode -> Color.WHITE
            callAccent != null && !toastMode -> Color.WHITE
            volumeBackdrop != null && !toastMode -> Color.WHITE
            toastMode -> if (Color.luminance(toastColor) > 0.5) Color.BLACK else Color.WHITE
            else -> if (appDarkIcons ?: (Color.luminance(barColor) > 0.5)) Color.BLACK else Color.WHITE
        }
    }

    fun setLockScreenMode(enabled: Boolean) {
        if (lockScreenMode == enabled) return
        lockScreenMode = enabled
        updateIconColor()
        invalidate()
    }

    /** Volume panel colour shown behind the status icons while the panel is open. */
    private var volumeBackdrop: Int? = null
    fun setVolumeBackdrop(color: Int?) {
        if (volumeBackdrop == color) return
        volumeBackdrop = color
        updateIconColor()
        fadeBackground()
    }

    /** Background actually painted; eases toward the target instead of snapping. */
    private var shownBackground: Int? = null
    private var backgroundAnimator: android.animation.ValueAnimator? = null
    private fun fadeBackground() {
        val target = volumeBackdrop ?: barColor
        val from = shownBackground
        backgroundAnimator?.cancel()
        if (from == null || from == target || !isAttachedToWindow) {
            shownBackground = target; invalidate(); return
        }
        backgroundAnimator = android.animation.ValueAnimator.ofArgb(from, target).apply {
            duration = 200L
            interpolator = MetroMotion.Decel
            addUpdateListener { shownBackground = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    fun setBarColor(color: Int) {
        val target = color
        if (barColor == target) return
        android.util.Log.d("SpartanBarColor",
            "color=${Integer.toHexString(target)} caller=" +
                Throwable().stackTrace.getOrNull(1)?.methodName)
        barColor = target
        updateIconColor()
        fadeBackground()
    }
    private val clock = SimpleDateFormat("h:mm", Locale.getDefault())
    private val date = Date()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var cellularLevel: Int? = null
    private var batteryLevel = 0
    private var pluggedIn = false
    private var powerSave = power.isPowerSaveMode
    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private val bluetoothIcon = requireNotNull(ContextCompat.getDrawable(context,
        R.drawable.metro_action_bluetooth))
    private val airplaneIcon = requireNotNull(ContextCompat.getDrawable(context,
        R.drawable.metro_action_airplane))
    private val quietIcon = requireNotNull(ContextCompat.getDrawable(context,
        R.drawable.metro_action_quiet))
    private val batteryWarnings = context.getSharedPreferences("battery_warnings", Context.MODE_PRIVATE)
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                powerSave = power.isPowerSaveMode
            invalidate()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            batteryLevel = (intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale)
                .coerceIn(0, 100)
            pluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val warned = batteryWarnings.getInt("threshold", 100)
            val threshold = when {
                pluggedIn || batteryLevel > 20 -> 100
                batteryLevel <= 5 -> 5
                batteryLevel <= 15 -> 15
                else -> warned
            }
            if (threshold != warned) {
                batteryWarnings.edit().putInt("threshold", threshold).apply()
                if (threshold < warned) post { onLowBattery(batteryLevel) }
            }
            invalidate()
        }
    }

    private var dragStartY = 0f
    private var dragStartX = 0f


    init {
        isClickable = true
        contentDescription = "Swipe down for action center"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = (event.rawY - dragStartY).coerceAtLeast(0f)
                onDrag(distance, false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val distance = (event.rawY - dragStartY).coerceAtLeast(0f)
                if (liveActivity != null && !lockScreenMode && !toastMode && callAccent == null &&
                    kotlin.math.abs(event.rawY - dragStartY) < 10f * density &&
                    dragStartX >= liveChipLeft() && dragStartX <= liveChipRight()) {
                    performClick()
                    onLiveActivityTap()
                    return true
                }
                val quickFlick = distance >= 10f * density &&
                    event.eventTime - event.downTime <= 350L
                onDrag(if (quickFlick) maxOf(distance, 32f * density) else distance, true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                onDrag(0f, true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun liveChipLeft() = (wifiSlotX() + 28f) * density

    private fun chipLimit(): Float {
        val cutoutLeft = if (Build.VERSION.SDK_INT >= 28) {
            rootWindowInsets?.displayCutout?.boundingRects
                ?.filter { it.top < height }?.minOfOrNull { it.left }?.toFloat()
        } else null
        return minOf((cutoutLeft ?: width.toFloat()) - 6f * density, width - 100f * density)
            .coerceAtLeast(liveChipLeft() + 42f * density)
    }

    private fun liveChipRight(): Float {
        val activity = liveActivity
        val limit = chipLimit()
        if (activity == null) return minOf(liveChipLeft() + 88f * density, limit)
        if (activity.kind != MetroLiveActivity.Kind.MUSIC) {
            paint.textSize = 11f * density
            paint.typeface = MetroFont.semibold(context)
            val prefix = if (activity.kind == MetroLiveActivity.Kind.ALARM ||
                activity.kind == MetroLiveActivity.Kind.CALL) "" else "\u25f7 "
            val text = prefix + activity.compactText()
            val wanted = liveChipLeft() + 16f * density + paint.measureText(text)
            return wanted.coerceIn(liveChipLeft() + 42f * density, limit)
        }
        paint.textSize = 11f * density
        paint.typeface = MetroFont.semibold(context)
        val artWidth = if (musicArt != null) 18f * density else 0f
        val text = (if (musicArt != null) "" else "\u266b ") + (musicTitle ?: activity.title)
        val wanted = liveChipLeft() + 12f * density + artWidth + paint.measureText(text)
        return wanted.coerceIn(liveChipLeft() + 42f * density, limit)
    }

    private var mobileGeneration: String? = null
    private val telephonyCallback = if (Build.VERSION.SDK_INT >= 31) {
        object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                cellularLevel = signalStrength.level.coerceIn(0, 4)
                invalidate()
            }

        }
    } else null

    private val displayCallback = if (Build.VERSION.SDK_INT >= 31) {
        object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                mobileGeneration = when {
                    info.networkType == TelephonyManager.NETWORK_TYPE_NR ||
                        info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
                    info.networkType == TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                    else -> null
                }
                invalidate()
            }
        }
    } else null

    private val legacySignalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            cellularLevel = signalStrength.level.coerceIn(0, 4)
            invalidate()
        }
    }
    private var telephonyRegistered = false
    private var displayRegistered = false
    private val wifiNetworks = mutableMapOf<android.net.Network, NetworkCapabilities>()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
            post { wifiNetworks[network] = caps; invalidate() }
        }
        override fun onLost(network: android.net.Network) {
            post { wifiNetworks.remove(network); invalidate() }
        }
    }

    fun refreshSignalAccess() {
        if (!isAttachedToWindow) return
        if (Build.VERSION.SDK_INT >= 31 && !displayRegistered) {
            try {
                displayCallback?.let {
                    telephony.registerTelephonyCallback(context.mainExecutor, it)
                    displayRegistered = true
                }
            } catch (_: SecurityException) { }
        }
        if (telephonyRegistered) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                telephonyCallback?.let {
                    telephony.registerTelephonyCallback(context.mainExecutor, it)
                    telephonyRegistered = true
                }
            } else {
                telephony.listen(legacySignalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                telephonyRegistered = true
            }
        } catch (_: SecurityException) {
            // Signal strength remains unavailable until phone access is granted.
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        powerSave = power.isPowerSaveMode
        val filter = IntentFilter(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION).apply {
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(statusReceiver, filter)
        connectivity.registerNetworkCallback(android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
        refreshSignalAccess()
    }

    override fun onDetachedFromWindow() {
        chipAnimator?.cancel()
        context.unregisterReceiver(batteryReceiver)
        context.unregisterReceiver(statusReceiver)
        connectivity.unregisterNetworkCallback(networkCallback)
        if (Build.VERSION.SDK_INT >= 31 && displayRegistered) {
            displayCallback?.let { telephony.unregisterTelephonyCallback(it) }
            displayRegistered = false
        }
        if (telephonyRegistered) {
            if (Build.VERSION.SDK_INT >= 31) {
                telephonyCallback?.let { telephony.unregisterTelephonyCallback(it) }
            } else {
                telephony.listen(legacySignalListener, PhoneStateListener.LISTEN_NONE)
            }
            telephonyRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val h = height.toFloat()
        val centerY = h / 2f
        if (!lockScreenMode) {
            backgroundPaint.color = if (toastMode) toastColor else callAccent ?: shownBackground ?: volumeBackdrop ?: barColor
            // Solid fill. Put the 3x3 clear at SAMPLE_X_FRACTION back before
            // captureStatusBarColor is scheduled again, or it samples this overlay.
            canvas.drawRect(0f, 0f, width.toFloat(), h, backgroundPaint)
        }
        paint.color = iconColor
        paint.style = Paint.Style.FILL
        paint.typeface = MetroFont.regular(context)
        paint.textSize = 13f * density
        date.time = System.currentTimeMillis()
        val time = clock.format(date)
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        val right = width - 14f * density
        val timeWidth = if (lockScreenMode) 0f else paint.measureText(time)
        if (!lockScreenMode) canvas.drawText(time, right - timeWidth, baseline, paint)

        liveActivity?.takeIf { !lockScreenMode && !toastMode && callAccent == null }?.let { activity ->
            val left = liveChipLeft()
            val chipRight = liveChipRight()
            if (chipRight - left >= 42f * density) {
                val halfHeight = minOf(10f * density, centerY - density)
                canvas.saveLayerAlpha(left, 0f, chipRight, h, (chipReveal * 255).toInt())
                canvas.translate(0f, (1f - chipReveal) * 5f * density)
                backgroundPaint.color = liveAccent
                canvas.drawRoundRect(left, centerY - halfHeight, chipRight,
                    centerY + halfHeight, 4f * density, 4f * density, backgroundPaint)
                paint.color = Color.WHITE
                paint.textSize = 11f * density
                paint.typeface = MetroFont.semibold(context)
                val music = activity.kind == MetroLiveActivity.Kind.MUSIC
                var textLeft = left + 6f * density
                if (music) musicArt?.let { art ->
                    val size = (14f * density).toInt()
                    val artLeft = textLeft.toInt()
                    val artTop = (centerY - size / 2f).toInt()
                    val previous = art.copyBounds()
                    art.setBounds(artLeft, artTop, artLeft + size, artTop + size)
                    art.draw(canvas)
                    art.bounds = previous
                    textLeft += size + 4f * density
                }
                val prefix = when {
                    music && musicArt != null -> ""
                    music -> "\u266b "
                    activity.kind == MetroLiveActivity.Kind.ALARM -> ""
                    activity.kind == MetroLiveActivity.Kind.CALL -> ""
                    else -> "\u25f7 "
                }
                val body = if (music) musicTitle ?: activity.compactText() else activity.compactText()
                var label = prefix + body
                val available = chipRight - textLeft - 6f * density
                val ellipsis = "\u2026"
                if (music && paint.measureText(label) > available &&
                    available > paint.measureText(ellipsis)) {
                    while (label.isNotEmpty() && paint.measureText(label + ellipsis) > available)
                        label = label.dropLast(1)
                    label += ellipsis
                } else if (!music) {
                    while (label.length > prefix.length && paint.measureText(label) > available)
                        label = label.dropLast(1)
                }
                val labelBaseline = centerY - (paint.ascent() + paint.descent()) / 2f
                val labelX = if (music) textLeft else
                    left + (chipRight - left - paint.measureText(label)) / 2f
                canvas.drawText(label, labelX, labelBaseline, paint)
                canvas.restore()
                paint.color = iconColor
            }
        }

        val capabilities = wifiNetworks.values.firstOrNull {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        val wifi = capabilities != null
        val airplane = android.provider.Settings.Global.getInt(context.contentResolver,
            android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val cellular = !airplane && telephony.simState == TelephonyManager.SIM_STATE_READY
        if (airplane) drawIcon(canvas, airplaneIcon, SIGNAL_X * density, centerY, 28f, true)
        else drawIcon(canvas, signalIcons[if (cellular) cellularLevel?.coerceIn(0, 4) ?: 0 else 0],
            SIGNAL_X * density, centerY, 34f, cellular)
        val wifiInfo = if (Build.VERSION.SDK_INT >= 29) {
            capabilities?.transportInfo as? WifiInfo
        } else null
        val wifiRssi = wifiInfo?.rssi?.takeIf { it in -100..-1 }
            ?: if (wifi) wifiManager.connectionInfo?.rssi?.takeIf { it in -100..-1 }
            else null
        val wifiLevel = wifiRssi?.let { WifiManager.calculateSignalLevel(it, 4) + 1 } ?: 1
        // Kenneth: connectivity (cellular, then Wi-Fi/network type) always leads; extras follow.
        var slot = SIGNAL_X + SLOT_DP * 2
        for (extra in leftExtras()) {
            when (extra) {
                Extra.BLUETOOTH -> drawIcon(canvas, bluetoothIcon, slot * density, centerY, 34f, true)
                Extra.QUIET -> drawIcon(canvas, quietIcon, slot * density, centerY, 30f, true)
                Extra.VIBRATE -> drawIcon(canvas, vibrateIcon, slot * density, centerY, 36f, true)
                Extra.MUTE -> drawIcon(canvas, muteIcon, slot * density, centerY, 36f, true)
            }
            slot += SLOT_DP
        }
        val netX = (SIGNAL_X + SLOT_DP) * density
        when {
            wifi -> drawWifiSignal(canvas, netX, centerY, wifiLevel)
            cellular && mobileGeneration == "4G" ->
                drawIcon(canvas, fourGIcon, netX, centerY, 30f, true)
            cellular && mobileGeneration == "5G" -> {
                paint.typeface = MetroFont.semibold(context)
                paint.textSize = 12f * density
                paint.color = iconColor
                val label = "5G"
                val labelBaseline = centerY - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(label, netX - paint.measureText(label) / 2f,
                    labelBaseline, paint)
            }
            else -> drawIcon(canvas, wifiIcon, netX, centerY, 36f, false)
        }
        val charging = pluggedIn || battery.isCharging
        val batteryIndex = when {
            batteryLevel < 20 -> 0
            batteryLevel < 50 -> 1
            batteryLevel < 80 -> 2
            else -> 3
        }
        val batteryIcon = when {
            charging -> chargingIcons[batteryIndex]
            powerSave -> batterySaverIcons[batteryIndex]
            else -> batteryIcons[batteryIndex]
        }
        val batteryCenterX = if (lockScreenMode) right - 10f * density else
            right - timeWidth - 22f * density
        val savedColor = iconColor
        if (batteryLevel <= 15 && !charging && !powerSave) iconColor = Color.rgb(240, 65, 65)
        drawIcon(canvas, batteryIcon, batteryCenterX, centerY, 50f, true)
        iconColor = savedColor
    }

    private enum class Extra { BLUETOOTH, QUIET, VIBRATE, MUTE }

    // W10M keeps every status glyph in the left cluster after the signal bars;
    // only battery and clock sit on the right.
    private fun leftExtras(): List<Extra> {
        if (lockScreenMode) return emptyList()
        val out = ArrayList<Extra>(3)
        if (android.provider.Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) != 0)
            out += Extra.BLUETOOTH
        val filter = context.getSystemService(android.app.NotificationManager::class.java)
            ?.currentInterruptionFilter ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        if (filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN) out += Extra.QUIET
        when (audio.ringerMode) {
            android.media.AudioManager.RINGER_MODE_VIBRATE -> out += Extra.VIBRATE
            android.media.AudioManager.RINGER_MODE_SILENT -> out += Extra.MUTE
        }
        return out
    }

    private fun wifiSlotX(): Float = SIGNAL_X + SLOT_DP * (1 + leftExtras().size)

    private fun drawWifiSignal(canvas: Canvas, x: Float, y: Float, level: Int) {
        drawIcon(canvas, wifiIcon, x, y, 36f, false)
        drawIcon(canvas, wifiIcons[(level - 1).coerceIn(0, 3)], x, y, 36f, true)
    }

    private fun drawIcon(
        canvas: Canvas, icon: Drawable, centerX: Float, centerY: Float,
        sizeDp: Float, active: Boolean
    ) {
        val size = sizeDp * ICON_SCALE * density
        icon.setTint(if (active) iconColor else Color.GRAY)
        icon.setBounds(
            (centerX - size / 2f).toInt(), (centerY - size / 2f).toInt(),
            (centerX + size / 2f).toInt(), (centerY + size / 2f).toInt()
        )
        icon.draw(canvas)
    }
}
