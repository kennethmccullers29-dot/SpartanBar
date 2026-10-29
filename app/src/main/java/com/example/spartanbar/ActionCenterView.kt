package com.example.spartanbar

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ActionCenterView(
    context: Context,
    private val onClose: () -> Unit,
    private val onOpenSettings: (String) -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var accent = Color.rgb(0, 120, 215)
    private val activeTiles = mutableListOf<View>()
    private val tileStates = mutableListOf<Triple<View, String, String>>()
    private val settingsObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshTileStates() }
    }
    private val panelColor = Color.BLACK
    private val tileColor = Color.rgb(48, 48, 48)
    private val notificationContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val revealedGroups = mutableSetOf<String>()
    private val expandedNotifications = mutableSetOf<String>()
    private val dismissibleRows = mutableMapOf<String, View>()
    private val nowPlaying = MetroNowPlayingView(
        context,
        onOpen = { activity ->
            if (NotificationAccessService.openNotification(context, activity.notification))
                closeAnimated()
        },
        onTrack = { title, art -> onMusicTrack?.invoke(title, art) }
    )
    var onMusicTrack: ((String?, android.graphics.drawable.Drawable?) -> Unit)? = null
    private val expandedTiles = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    private val reveal = ActionCenterReveal()
    private val panelHeight get() = reveal.maxHeight
    private val visibleHeight get() = reveal.visibleHeight
    private var dragStartHeight = 0f
    private var touching = false
    private var refreshPending = false
    private val refreshTask = Runnable { renderNotifications() }
    private val panel = ScrollView(context).apply {
        setBackgroundColor(panelColor)
        isFillViewport = true
        isClickable = true
    }
    private var expanded = false
    private var targetOpen = false
    private var dismissStartY = 0f
    private var animator: ValueAnimator? = null
    private var expandedAnimator: ValueAnimator? = null
    private var dismissingNotifications = false
    private var openingNotification = false
    private var entrancePending = false
    private val dismissInterpolator = MetroMotion.Decel
    private var dragVelocity: android.view.VelocityTracker? = null
    private val handleStrip = View(context).apply { setBackgroundColor(accent) }
    private val dragHandle = FrameLayout(context).apply {
        addView(handleStrip, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, dp(18), Gravity.BOTTOM))
        contentDescription = "Swipe up to close Action Center"
        visibility = View.GONE
        setOnClickListener { closeAnimated() }
        addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(2).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(48), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(7)
        })
    }
    private lateinit var carrierLabel: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var clearAllButton: View
    private var wifiTileLabel: TextView? = null
    private var btTileLabel: TextView? = null
    private var brightnessTileLabel: TextView? = null
    private var torchOn = false
    private var torchId: String? = null
    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (torchId == null) torchId = cameraId
            if (cameraId == torchId) { torchOn = enabled; post { refreshTileStates() } }
        }
    }
    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) { refreshTileStates() }
    }

    init {
        setBackgroundColor(Color.BLACK)
        background.alpha = 0
        setOnClickListener { closeAnimated() }
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP))
        addView(dragHandle, LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP))
        dragHandle.elevation = dp(3).toFloat()
        panel.clipBounds = Rect(0, 0, resources.displayMetrics.widthPixels, 0)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), dp(56))
        }
        panel.addView(content)
        // Carrier sits under the status-bar signal + Wi‑Fi cluster (~10–66dp);
        // battery % and date stay on the right with fixed gaps so they don't collide.
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(14), 0)
        }
        carrierLabel = text("", 12f, Color.WHITE).apply {
            typeface = MetroFont.regular(context)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(carrierLabel, LinearLayout.LayoutParams(0, dp(24), 1f))
        batteryLabel = text("", 10f, Color.WHITE).apply {
            typeface = MetroFont.regular(context)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        details.addView(batteryLabel, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, dp(24)).apply { leftMargin = dp(12) })
        dateLabel = text("", 10f, Color.WHITE).apply {
            typeface = MetroFont.regular(context)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        details.addView(dateLabel, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, dp(24)).apply { leftMargin = dp(10) })
        content.addView(details, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, dp(24)))
        updateStatusDetails()
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPanelAnimation()
                    dismissStartY = event.rawY
                    dragStartHeight = visibleHeight
                    dragVelocity?.recycle()
                    dragVelocity = android.view.VelocityTracker.obtain().also { it.addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    dragVelocity?.addMovement(event)
                    showHeight(dragStartHeight + event.rawY - dismissStartY)
                }
                MotionEvent.ACTION_UP -> {
                    val tracker = dragVelocity
                    tracker?.addMovement(event); tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle(); dragVelocity = null
                    // A flick decides on its own; a slow drag uses the 65% threshold.
                    val open = when {
                        vy > dp(600) -> true
                        vy < -dp(600) -> false
                        else -> reveal.shouldStayOpen()
                    }
                    if (kotlin.math.abs(event.rawY - dismissStartY) < dp(8)) {
                        dragHandle.performClick()
                    } else settle(open, vy) {
                        if (visibleHeight == 0f) onClose()
                    }
                }
                MotionEvent.ACTION_CANCEL -> settle(reveal.shouldStayOpen()) {
                    if (visibleHeight == 0f) onClose()
                }
            }
            true
        }

        val expandLabel = text("Expand", 13f, Color.WHITE).apply {
            typeface = MetroFont.regular(context)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        val expandChevron = ImageView(context).apply {
            setImageResource(R.drawable.metro_chevron_up)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.WHITE)
            rotation = 180f
        }
        val expandButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, dp(4), 0)
            addView(expandLabel, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(expandChevron, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                leftMargin = dp(6)
            })
            setOnClickListener {
                expanded = !expanded
                animateExpandedTiles(expanded)
                expandLabel.text = if (expanded) "Collapse" else "Expand"
                expandChevron.animate().rotation(if (expanded) 0f else 180f)
                    .setDuration(200L).setInterpolator(MetroMotion.Decel).start()
            }
        }

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifiActive = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val powerSaver = context.getSystemService(PowerManager::class.java).isPowerSaveMode
        content.addView(tileRow(
            Tile("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, R.drawable.metro_wifi, wifiActive),
            Tile("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS,
                R.drawable.metro_action_bluetooth),
            Tile("Rotation lock", Settings.ACTION_DISPLAY_SETTINGS, R.drawable.metro_action_rotation),
            Tile("All settings", Settings.ACTION_SETTINGS,
                R.drawable.metro_action_settings)
        ))
        expandedTiles.addView(tileRow(
            Tile("Cellular data", Settings.ACTION_WIRELESS_SETTINGS,
                R.drawable.metro_signal),
            Tile("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS,
                R.drawable.metro_action_airplane),
            Tile("Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS,
                R.drawable.metro_action_power, powerSaver),
            Tile("Brightness", Settings.ACTION_DISPLAY_SETTINGS, R.drawable.metro_action_lightbulb)
        ))
        expandedTiles.addView(tileRow(
            Tile("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                R.drawable.metro_action_location),
            Tile("Quiet hours", "android.settings.ZEN_MODE_SETTINGS",
                R.drawable.metro_action_quiet),
            Tile("Connect", Settings.ACTION_BLUETOOTH_SETTINGS,
                R.drawable.metro_action_connect),
            Tile("Camera", android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
                R.drawable.metro_action_camera)
        ))
        expandedTiles.addView(tileRow(
            Tile("Flashlight", android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
                R.drawable.metro_action_flashlight),
            Tile("Vibrate", Settings.ACTION_SOUND_SETTINGS, R.drawable.mdl2_vibrate),
            Tile("Mobile hotspot", "android.settings.TETHER_SETTINGS", R.drawable.metro_action_hotspot),
            Tile("Project", "android.settings.CAST_SETTINGS", R.drawable.metro_action_connect)
        ))
        content.addView(expandedTiles)


        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(2), 0)
        }
        clearAllButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
            visibility = View.GONE
            addView(text("Clear all", 13f, Color.WHITE).apply {
                typeface = MetroFont.regular(context)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(text("×", 16f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = MetroFont.semibold(context)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                leftMargin = dp(6)
            })
            setOnClickListener { clearAllNotifications() }
            contentDescription = "Clear all notifications"
        }
        header.addView(clearAllButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(expandButton, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, dp(40)))
        content.addView(header)
        content.addView(nowPlaying, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(20)
        })
        content.addView(notificationContainer)
        refreshNotifications()
    }

    fun setAccentColor(color: Int) {
        accent = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        handleStrip.setBackgroundColor(accent)
        activeTiles.forEach { it.setBackgroundColor(accent) }
        nowPlaying.setAccent(accent)
    }

    fun setNowPlaying(activity: MetroLiveActivity?, color: Int) {
        if (activity == null || activity.kind != MetroLiveActivity.Kind.MUSIC) {
            nowPlaying.clearSession()
            nowPlaying.visibility = View.GONE
            return
        }
        nowPlaying.visibility = View.VISIBLE
        nowPlaying.setSession(activity, color)
    }

    fun tickNowPlaying() {
        if (nowPlaying.visibility == View.VISIBLE) nowPlaying.tick()
    }

    fun setDragDistance(distancePx: Float) {
        cancelPanelAnimation()
        openingNotification = false
        if (visibleHeight == 0f && distancePx > 0f) {
            entrancePending = true
            updateStatusDetails()
            refreshNotifications()
        }
        showHeight(distancePx)
    }

    private fun updateStatusDetails() {
        refreshTileStates()
        val telephony = context.getSystemService(TelephonyManager::class.java)
        carrierLabel.text = if (telephony.simState == TelephonyManager.SIM_STATE_READY) {
            telephony.networkOperatorName.takeIf { it.isNotBlank() }
                ?: telephony.simOperatorName.takeIf { it.isNotBlank() }
                ?: "No service"
        } else {
            "No service"
        }
        val battery = context.getSystemService(BatteryManager::class.java)
        batteryLabel.text = "${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)}%"
        dateLabel.text = SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date())
    }

    private fun animateExpandedTiles(show: Boolean) {
        val previous = expandedAnimator
        expandedAnimator = null
        previous?.cancel()
        val targetHeight = dp(180)
        if (show) expandedTiles.visibility = View.VISIBLE
        val startHeight = expandedTiles.layoutParams.height.coerceAtLeast(0)
        expandedAnimator = ValueAnimator.ofInt(startHeight, if (show) targetHeight else 0).apply {
            duration = 240L
            interpolator = MetroMotion.Decel
            addUpdateListener { animation ->
                expandedTiles.layoutParams = expandedTiles.layoutParams.apply {
                    height = animation.animatedValue as Int
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (expandedAnimator === animation && !show) {
                        expandedTiles.visibility = View.GONE
                    }
                }
            })
            start()
        }

    }

    private fun showHeight(height: Float) {
        reveal.show(height)
        panel.clipBounds = Rect(0, 0, width, visibleHeight.toInt())
        background.alpha = (145 * visibleHeight / panelHeight).toInt()
        dragHandle.visibility = if (visibleHeight > dp(48)) View.VISIBLE else View.GONE
        dragHandle.translationY = (visibleHeight - dp(48)).coerceAtLeast(0f)
    }

    fun settle(open: Boolean, velocity: Float = 0f, onFinished: () -> Unit = {}) {
        if (open) {
            if (!targetOpen) entrancePending = true
            updateStatusDetails()
            refreshNotifications()
        } else {
            entrancePending = false
            revealedGroups.clear()
        }
        cancelPanelAnimation()
        targetOpen = open
        val end = if (open) panelHeight else 0f
        animator = ValueAnimator.ofFloat(visibleHeight, end).apply {
            duration = MetroMotion.settleDuration(end - visibleHeight, velocity,
                (280 * kotlin.math.abs(end - visibleHeight) / panelHeight).toLong().coerceIn(140L, 280L))
            interpolator = if (open) MetroMotion.Decel else MetroMotion.Accel
            setLayerType(LAYER_TYPE_HARDWARE, null)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { setLayerType(LAYER_TYPE_NONE, null) }
            })
            addUpdateListener { showHeight(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) {
                        animator = null
                        showHeight(end)
                        onFinished()
                    }
                }
            })
        }
        animator?.start()
    }

    private fun cancelPanelAnimation() {
        val previous = animator
        animator = null
        previous?.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wasSettling = animator != null
        cancelPanelAnimation()
        reveal.resize(h.toFloat())
        showHeight(visibleHeight)
        updateStatusDetails()
        if (wasSettling) settle(targetOpen) {
            if (!targetOpen) onClose()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (refreshPending) refreshNotifications()
        }
        return handled
    }

    private fun closeAnimated(afterClose: () -> Unit = {}) = settle(false) {
        onClose()
        afterClose()
    }

    private fun refreshTileStates() {
        activeTiles.clear()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        tileStates.forEach { (view, action, label) ->
            val active = when {
                action == Settings.ACTION_WIFI_SETTINGS -> wifi.isWifiEnabled
                label == "Bluetooth" ->
                    Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) != 0
                label == "Cellular data" ->
                    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0 &&
                        Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
                label == "Quiet hours" -> quietHoursOn()
                label == "Flashlight" -> torchOn
                label == "Vibrate" -> context.getSystemService(android.media.AudioManager::class.java)
                    .ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL
                label == "Mobile hotspot" -> hotspotOn()
                action == Settings.ACTION_AIRPLANE_MODE_SETTINGS ->
                    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                action == Settings.ACTION_BATTERY_SAVER_SETTINGS ->
                    context.getSystemService(PowerManager::class.java).isPowerSaveMode
                label == "Rotation lock" ->
                    Settings.System.getInt(context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, 1) == 0
                action == Settings.ACTION_LOCATION_SOURCE_SETTINGS ->
                    androidx.core.location.LocationManagerCompat.isLocationEnabled(
                        context.getSystemService(android.location.LocationManager::class.java))
                else -> false
            }
            view.isSelected = active
            androidx.core.view.ViewCompat.setStateDescription(view,
                if (active) "On" else "Off")
            view.setBackgroundColor(if (active) accent else tileColor)
            if (active) activeTiles.add(view)
        }
        wifiTileLabel?.text = wifiTileCaption()
        btTileLabel?.text = btTileCaption()
        brightnessTileLabel?.text = brightnessCaption()
    }

    private fun hotspotOn(): Boolean = try {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
    } catch (_: Throwable) { false }

    /** W10M brightness tile shows the level: 25/50/75/100% or Auto. */
    private fun brightnessCaption(): String {
        val cr = context.contentResolver
        if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) return "Auto"
        val v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        return "${((v * 100 / 255 + 12) / 25 * 25).coerceIn(25, 100)}%"
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()

    /** Writes a secure/global setting (needs WRITE_SECURE_SETTINGS via adb); verifies, else opens settings. */
    private fun trySecure(tile: Tile, write: () -> Unit, check: () -> Boolean, want: Boolean) {
        try { write() } catch (_: Throwable) { onOpenSettings(tile.action); return }
        postDelayed({ refreshTileStates(); if (check() != want) onOpenSettings(tile.action) }, 700L)
    }

    /** W10M shows Bluetooth state as the tile label: "Not connected" / "Connected" when on. */
    private fun btTileCaption(): String {
        if (Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) == 0) return "Bluetooth"
        return try {
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                ?: return "Bluetooth"
            val connected = listOf(android.bluetooth.BluetoothProfile.A2DP,
                android.bluetooth.BluetoothProfile.HEADSET).any {
                adapter.getProfileConnectionState(it) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            }
            if (connected) "Connected" else "Not connected"
        } catch (_: SecurityException) { "Bluetooth" }
    }

    private fun wifiTileCaption(): String {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (!wifi.isWifiEnabled) return "Wi-Fi"
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return "Wi-Fi"
        @Suppress("DEPRECATION")
        val ssid = wifi.connectionInfo?.ssid
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", true) }
        return ssid ?: "Wi-Fi"
    }

    private fun quietHoursOn(): Boolean = try {
        val filter = context.getSystemService(android.app.NotificationManager::class.java)
            .currentInterruptionFilter
        filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (_: SecurityException) {
        false
    }

    private fun dismissNotificationRows(keys: Array<String>) {
        if (dismissingNotifications) return
        val rows = keys.mapNotNull { key -> dismissibleRows[key]?.let { key to it } }
        if (rows.isEmpty()) {
            NotificationAccessService.instance?.dismissNotifications(keys)
            return
        }
        dismissingNotifications = true
        var remaining = rows.size
        rows.forEachIndexed { index, (key, row) ->
            row.animate().cancel()
            val travel = maxOf(row.width, width, resources.displayMetrics.widthPixels).toFloat()
            val dir = if (row.translationX < 0f) -1f else 1f
            row.animate()
                .translationX(dir * travel)
                .alpha(0f)
                .setStartDelay((index.coerceAtMost(6) * 28L))
                .setDuration(170L)
                .setInterpolator(dismissInterpolator)
                .withEndAction {
                    dismissibleRows.remove(key)
                    (row.parent as? ViewGroup)?.removeView(row)
                    remaining--
                    if (remaining == 0) {
                        NotificationAccessService.instance?.dismissNotifications(keys)
                        dismissingNotifications = false
                        pruneEmptyNotificationGroups()
                        refreshNotifications()
                    }
                }.start()
        }
    }

    private fun pruneEmptyNotificationGroups() {
        for (i in notificationContainer.childCount - 1 downTo 0) {
            val group = notificationContainer.getChildAt(i) as? ViewGroup ?: continue
            val hasRow = (0 until group.childCount).any { index ->
                group.getChildAt(index).tag is String
            }
            if (!hasRow) {
                group.animate().cancel()
                notificationContainer.removeViewAt(i)
            }
        }
    }

    private fun clearAllNotifications() =
        dismissNotificationRows(dismissibleRows.keys.toTypedArray())

    fun refreshNotifications() {
        refreshPending = true
        removeCallbacks(refreshTask)
        postDelayed(refreshTask, 100L)
    }

    private fun renderNotifications() {
        if (touching || dismissingNotifications || openingNotification) return
        refreshPending = false
        val scrollPosition = panel.scrollY
        cancelNotificationAnimations()
        notificationContainer.removeAllViews()
        dismissibleRows.clear()
        val playEntrance = entrancePending
        entrancePending = false
        val listener = NotificationAccessService.instance
        if (listener == null) {
            notificationContainer.addView(text(
                "Allow notification access to see alerts here", 14f, Color.WHITE
            ).apply {
                setPadding(dp(12), dp(18), dp(12), dp(18))
                setOnClickListener {
                    onOpenSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
            })
            updateClearAllVisibility()
            return
        }
        val notifications = listener.visibleNotifications().filter { sbn ->
            val notification = sbn.notification
            !notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) &&
                notification.category != Notification.CATEGORY_TRANSPORT
        }
        if (notifications.isEmpty()) {
            revealedGroups.clear()
            notificationContainer.addView(text("No notifications", 15f, Color.LTGRAY).apply {
                setPadding(dp(12), dp(18), dp(12), dp(18))
            })
            updateClearAllVisibility()
            return
        }
        notifications.groupBy { it.packageName }.forEach { (packageName, group) ->
            val appName = appDisplayName(packageName, group.first().notification.extras)
            val appIcon = try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val groupView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            // W10M: large light app name, no header glyph — icons live on each item.
            groupView.addView(text(appName, 24f, Color.WHITE).apply {
                typeface = MetroFont.light(context)
                includeFontPadding = false
                setPadding(dp(10), dp(10), dp(10), dp(6))
            }, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            groupView.addView(View(context).apply {
                setBackgroundColor(Color.rgb(40, 40, 40))
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(4)
            })

            group.forEachIndexed { index, sbn ->
                if (index > 0) {
                    groupView.addView(View(context).apply {
                        setBackgroundColor(Color.rgb(32, 32, 32))
                    }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                        leftMargin = dp(52)
                        rightMargin = dp(10)
                    })
                }
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?.takeIf { it.isNotBlank() } ?: appName
                val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                        ?.joinToString("\n")
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    tag = sbn.key
                    setPadding(dp(10), dp(8), dp(6), dp(10))
                    var startX = 0f
                    var startY = 0f
                    var swiping = false
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = event.rawX
                                startY = event.rawY
                                swiping = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - startX
                                val dy = event.rawY - startY
                                if (swiping || (NotificationDismissal.canDismiss(sbn.isClearable, sbn.notification.flags) && kotlin.math.abs(dx) > dp(16) &&
                                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f)) {
                                    swiping = true
                                    view.parent.requestDisallowInterceptTouchEvent(true)
                                    view.translationX = dx
                                    view.alpha = (1f - kotlin.math.abs(view.translationX) /
                                        width.coerceAtLeast(1)).coerceAtLeast(0.3f)
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                view.parent.requestDisallowInterceptTouchEvent(false)
                                if (swiping) {
                                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                                        kotlin.math.abs(view.translationX) > dp(64)) {
                                        dismissNotificationRows(arrayOf(sbn.key))
                                    } else view.animate().translationX(0f).alpha(1f)
                                        .setDuration(160L)
                                        .setInterpolator(dismissInterpolator)
                                        .start()
                                    return@setOnTouchListener true
                                }
                            }
                        }
                        swiping
                    }
                    setOnLongClickListener { anchor ->
                        android.widget.PopupMenu(context, anchor).apply {
                            menu.add("Notification settings")
                            setOnMenuItemClickListener {
                                launchControl(android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                                true
                            }
                            show()
                        }
                        true
                    }
                    setOnClickListener {
                        if (openingNotification || sbn.notification.contentIntent == null)
                            return@setOnClickListener
                        openingNotification = true
                        if (NotificationAccessService.openNotification(context, sbn)) {
                            closeAnimated {
                                openingNotification = false
                                NotificationAccessService.instance?.dismissNotifications(arrayOf(sbn.key))
                                refreshNotifications()
                            }
                        } else {
                            openingNotification = false
                            refreshNotifications()
                        }
                    }
                }
                val smallIcon = try {
                    sbn.notification.smallIcon?.loadDrawable(context)?.mutate()
                } catch (_: RuntimeException) {
                    null
                }
                val tileBg = opaqueColor(sbn.notification.color).takeIf { it != 0 } ?: accent
                val iconTile = FrameLayout(context).apply {
                    setBackgroundColor(tileBg)
                }
                val glyph = (smallIcon ?: appIcon)?.mutate()
                if (glyph != null) {
                    if (smallIcon != null) glyph.setTint(Color.WHITE)
                    iconTile.addView(ImageView(context).apply {
                        setImageDrawable(glyph)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }
                row.addView(iconTile, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    rightMargin = dp(10)
                    topMargin = dp(1)
                })
                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutTransition = LayoutTransition().apply {
                        setDuration(LayoutTransition.CHANGING, 180L)
                        setDuration(LayoutTransition.APPEARING, 160L)
                        setDuration(LayoutTransition.DISAPPEARING, 140L)
                        setInterpolator(LayoutTransition.CHANGING, dismissInterpolator)
                        setInterpolator(LayoutTransition.APPEARING, dismissInterpolator)
                        setInterpolator(LayoutTransition.DISAPPEARING, dismissInterpolator)
                        enableTransitionType(LayoutTransition.CHANGING)
                    }
                }
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                titleRow.addView(text(title, 16f, Color.WHITE).apply {
                    typeface = MetroFont.semibold(context)
                    includeFontPadding = false
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                titleRow.addView(text(formatToastTime(sbn.postTime), 13f, Color.WHITE).apply {
                    typeface = MetroFont.light(context)
                    includeFontPadding = false
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                })
                textColumn.addView(titleRow)
                @Suppress("DEPRECATION")
                val picture = extras.getParcelable<android.graphics.Bitmap>(Notification.EXTRA_PICTURE)
                val pictureView = picture?.takeUnless { it.isRecycled }?.let { bitmap ->
                    ImageView(context).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = "Notification image"
                        visibility = if (sbn.key in expandedNotifications) View.VISIBLE else View.GONE
                        textColumn.addView(this, LinearLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT, dp(160)).apply {
                            topMargin = dp(8)
                            bottomMargin = dp(4)
                        })
                    }
                }
                var messageView: TextView? = null
                val bodyRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                if (!body.isNullOrBlank()) {
                    val message = text(body.toString(), 14f, Color.rgb(180, 180, 180)).apply {
                        typeface = MetroFont.regular(context)
                        includeFontPadding = false
                        maxLines = if (sbn.key in expandedNotifications) Int.MAX_VALUE else 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    messageView = message
                    bodyRow.addView(message, LinearLayout.LayoutParams(
                        0, LayoutParams.WRAP_CONTENT, 1f))
                } else {
                    bodyRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                }
                val actions = sbn.notification.actions.orEmpty().filter {
                    it.remoteInputs.isNullOrEmpty()
                }.take(3)
                val actionRow = LinearLayout(context).apply {
                    visibility = if (sbn.key in expandedNotifications) View.VISIBLE else View.GONE
                }
                if (actions.isNotEmpty()) {
                    actions.forEach { action ->
                        actionRow.addView(text(action.title.toString(), 12f, Color.WHITE).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(8), 0, dp(8), 0)
                            setBackgroundColor(tileColor)
                            setOnClickListener {
                                try { action.actionIntent.send() }
                                catch (_: android.app.PendingIntent.CanceledException) { }
                                refreshNotifications()
                            }
                        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                            rightMargin = dp(6)
                        })
                    }
                }
                val canExpandItem = pictureView != null || actions.isNotEmpty() ||
                    (body?.length ?: 0) > 90 || body?.contains('\n') == true
                val chevron = ImageView(context).apply {
                    setImageResource(R.drawable.metro_chevron_up)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setColorFilter(Color.WHITE)
                    rotation = if (sbn.key in expandedNotifications) 0f else 180f
                    visibility = if (canExpandItem) View.VISIBLE else View.INVISIBLE
                    contentDescription = if (sbn.key in expandedNotifications)
                        "Collapse notification" else "Expand notification"
                    setOnClickListener {
                        if (!canExpandItem) return@setOnClickListener
                        val show = if (sbn.key in expandedNotifications) {
                            expandedNotifications.remove(sbn.key)
                            false
                        } else {
                            expandedNotifications.add(sbn.key)
                            true
                        }
                        if (show) {
                            pictureView?.let { picture ->
                                picture.alpha = 0f
                                picture.visibility = View.VISIBLE
                                picture.animate().alpha(1f).setDuration(160L)
                                    .setInterpolator(dismissInterpolator).start()
                            }
                            if (actions.isNotEmpty()) {
                                actionRow.alpha = 0f
                                actionRow.visibility = View.VISIBLE
                                actionRow.animate().alpha(1f).setDuration(160L)
                                    .setInterpolator(dismissInterpolator).start()
                            }
                            messageView?.maxLines = Int.MAX_VALUE
                        } else {
                            pictureView?.animate()?.alpha(0f)?.setDuration(120L)
                                ?.withEndAction {
                                    pictureView.visibility = View.GONE
                                    pictureView.alpha = 1f
                                }?.start()
                            if (actions.isNotEmpty()) {
                                actionRow.animate().alpha(0f).setDuration(120L)
                                    .withEndAction {
                                        actionRow.visibility = View.GONE
                                        actionRow.alpha = 1f
                                    }.start()
                            } else {
                                actionRow.visibility = View.GONE
                            }
                            messageView?.maxLines = 2
                        }
                        animate().cancel()
                        this.animate().rotation(if (show) 0f else 180f)
                            .setDuration(160L)
                            .setInterpolator(dismissInterpolator)
                            .start()
                        contentDescription = if (show)
                            "Collapse notification" else "Expand notification"
                    }
                }
                bodyRow.addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    leftMargin = dp(8)
                    topMargin = dp(2)
                })
                if (!body.isNullOrBlank() || canExpandItem) {
                    textColumn.addView(bodyRow, LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) })
                }
                if (actions.isNotEmpty()) {
                    textColumn.addView(actionRow, rowParams(10))
                }
                row.addView(textColumn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                if (NotificationDismissal.canDismiss(sbn.isClearable, sbn.notification.flags))
                    dismissibleRows[sbn.key] = row
                groupView.addView(row)
            }
            val slot = notificationContainer.childCount
            notificationContainer.addView(groupView, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
            val fresh = packageName !in revealedGroups
            revealedGroups.add(packageName)
            if (fresh && playEntrance) {
                groupView.alpha = 0f
                groupView.translationY = dp(8).toFloat()
                groupView.animate().alpha(1f).translationY(0f)
                    .setStartDelay(slot * 24L)
                    .setDuration(160L)
                    .setInterpolator(dismissInterpolator)
                    .start()
            } else if (fresh) {
                groupView.alpha = 0f
                groupView.animate().alpha(1f).setDuration(120L)
                    .setInterpolator(dismissInterpolator).start()
            }
        }
        revealedGroups.retainAll(notifications.map { it.packageName }.toSet())
        expandedNotifications.retainAll(notifications.map { it.key }.toSet())
        updateClearAllVisibility()
        panel.post {
            if (!touching && panel.childCount > 0) {
                val content = panel.getChildAt(0)
                panel.scrollTo(0, scrollPosition.coerceAtMost(
                    (content.height - panel.height).coerceAtLeast(0)))
            }
        }
    }

    private fun updateClearAllVisibility() {
        clearAllButton.visibility =
            if (dismissibleRows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun cancelNotificationAnimations() {
        for (i in 0 until notificationContainer.childCount) {
            val group = notificationContainer.getChildAt(i)
            group.animate().cancel()
            if (group is ViewGroup) {
                for (c in 0 until group.childCount) group.getChildAt(c).animate().cancel()
            }
        }
    }

    private data class Tile(
        val label: String,
        val action: String,
        val icon: Int? = null,
        val active: Boolean = false,
        val symbol: String = ""
    )

    private fun tileRow(vararg tiles: Tile) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        tiles.forEach { tile ->
            val cell = FrameLayout(context).apply {
                setBackgroundColor(if (tile.active) accent else tileColor)
                setOnClickListener { activateTile(tile) }
                setOnLongClickListener { onOpenSettings(tile.action); true }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> view.animate().alpha(0.72f)
                            .scaleX(0.97f).scaleY(0.97f).setDuration(90L).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                                .setDuration(140L).start()
                    }
                    false
                }
                contentDescription = "${tile.label}. Hold for settings"
            }
            tileStates.add(Triple(cell, tile.action, tile.label))
            if (tile.active) activeTiles.add(cell)
            // W10M: glyph sits above the first letter of the caption (vectors have
            // empty padding, so the view itself starts a bit left of the text).
            if (tile.icon != null) {
                cell.addView(ImageView(context).apply {
                    setImageDrawable(ContextCompat.getDrawable(context, tile.icon))
                    setColorFilter(Color.WHITE)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(1)
                    topMargin = dp(6)
                })
            } else {
                cell.addView(text(tile.symbol, 30f, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(4)
                    topMargin = dp(6)
                })
            }
            val isBt = tile.label == "Bluetooth"
            val caption = if (tile.action == Settings.ACTION_WIFI_SETTINGS)
                wifiTileCaption() else if (isBt) btTileCaption()
                else if (tile.label == "Brightness") brightnessCaption() else tile.label
            val labelView = text(caption, 12f, Color.WHITE).apply {
                maxLines = 2
                gravity = Gravity.START or Gravity.BOTTOM
                typeface = MetroFont.regular(context)
                includeFontPadding = false
                setPadding(dp(8), 0, dp(4), dp(6))
            }
            if (tile.action == Settings.ACTION_WIFI_SETTINGS) wifiTileLabel = labelView
            if (isBt) btTileLabel = labelView
            if (tile.label == "Brightness") brightnessTileLabel = labelView
            cell.addView(labelView, FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(34), Gravity.BOTTOM))
            addView(cell, LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
    }

    private fun activateTile(tile: Tile) {
        if (tile.label == "Rotation lock" || tile.label == "Brightness") {
            if (!Settings.System.canWrite(context)) {
                android.widget.Toast.makeText(context,
                    "Allow Modify system settings, then tap the tile again",
                    android.widget.Toast.LENGTH_LONG).show()
                launchControl(android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + context.packageName)))
                return
            }
            try {
                if (tile.label == "Rotation lock") {
                    val current = Settings.System.getInt(context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, 1)
                    Settings.System.putInt(context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, if (current == 1) 0 else 1)
                } else {
                    val cr = context.contentResolver
                    // W10M cycle: 25 -> 50 -> 75 -> 100 -> Auto -> 25
                    val next = when (brightnessCaption()) {
                        "25%" -> 50; "50%" -> 75; "75%" -> 100; "100%" -> -1; else -> 25
                    }
                    if (next < 0) {
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    } else {
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next * 255 / 100)
                    }
                }
                refreshTileStates()
            } catch (_: SecurityException) { onOpenSettings(tile.action) }
        } else toggleTile(tile)
    }

    private fun toggleTile(tile: Tile) {
        val cr = context.contentResolver
        when (tile.label) {
            "Flashlight" -> try {
                val cm = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val id = torchId ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if (id != null) { torchId = id; cm.setTorchMode(id, !torchOn) }
            } catch (_: Exception) { toast("Flashlight is in use") }
            "Quiet hours", "Vibrate" -> {
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    toast("Allow Do Not Disturb access for SpartanBar, then tap again")
                    launchControl(android.content.Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return
                }
                if (tile.label == "Quiet hours") nm.setInterruptionFilter(
                    if (quietHoursOn()) android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    else android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                else {
                    val am = context.getSystemService(android.media.AudioManager::class.java)
                    am.ringerMode = if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL)
                        android.media.AudioManager.RINGER_MODE_VIBRATE else android.media.AudioManager.RINGER_MODE_NORMAL
                }
            }
            // Samsung keeps its own power-saving state; writing Settings.Global low_power left
            // apps and One UI disagreeing. Open the system battery saver page instead.
            "Battery saver" -> launchControl(android.content.Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            "Location" -> {
                val lm = context.getSystemService(android.location.LocationManager::class.java)
                val on = androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
                @Suppress("DEPRECATION")
                trySecure(tile, { Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE,
                    if (on) Settings.Secure.LOCATION_MODE_OFF else Settings.Secure.LOCATION_MODE_HIGH_ACCURACY) },
                    { androidx.core.location.LocationManagerCompat.isLocationEnabled(lm) }, !on)
            }
            "Bluetooth" -> {
                @Suppress("DEPRECATION", "MissingPermission")
                val done = Build.VERSION.SDK_INT < 33 && try {
                    val a = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
                    if (a.isEnabled) a.disable() else a.enable()
                } catch (_: Throwable) { false }
                if (!done) launchControl(android.content.Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            "Wi-Fi" -> launchControl(android.content.Intent(Settings.Panel.ACTION_WIFI))
            "Cellular data" ->
                launchControl(android.content.Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            "Airplane mode" -> {
                // No public direct toggle (needs NETWORK_SETTINGS); open the airplane mode page.
                val airplane = android.content.Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                closeAnimated {
                    val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    try { context.startActivity(airplane.addFlags(flags)) }
                    catch (_: android.content.ActivityNotFoundException) {
                        try { context.startActivity(android.content.Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(flags)) }
                        catch (_: android.content.ActivityNotFoundException) { onOpenSettings(Settings.ACTION_SETTINGS) }
                    }
                }
            }
            "Mobile hotspot" -> launchControl(android.content.Intent().setComponent(
                android.content.ComponentName("com.android.settings", "com.android.settings.TetherSettings")))
            else -> onOpenSettings(tile.action)
        }
        postDelayed({ refreshTileStates() }, 250L)
    }

    private fun launchControl(intent: android.content.Intent) {
        closeAnimated {
            try { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: android.content.ActivityNotFoundException) { onOpenSettings(Settings.ACTION_SETTINGS) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
        context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)
        context.contentResolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, settingsObserver)
        try { context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            .registerTorchCallback(torchCallback, null) } catch (_: Exception) {}
        ContextCompat.registerReceiver(context, stateReceiver, android.content.IntentFilter().apply {
            addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
            addAction(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        refreshTileStates()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
        try { context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            .unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
        try { context.unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        cancelPanelAnimation()
        val previous = expandedAnimator
        expandedAnimator = null
        previous?.cancel()
        removeCallbacks(refreshTask)
        dismissingNotifications = false
        openingNotification = false
        touching = false
        super.onDetachedFromWindow()
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = MetroFont.regular(context)
    }

    private fun formatToastTime(postTime: Long): String {
        val stamped = SimpleDateFormat("h:mma", Locale.US).format(Date(postTime))
            .lowercase(Locale.US)
        return stamped.removeSuffix("m")
    }

    private fun opaqueColor(color: Int): Int {
        if (color == 0) return 0
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun appDisplayName(packageName: String, extras: Bundle?): String {
        extras?.getString("android.substName")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        @Suppress("DEPRECATION")
        val fromExtras = if (Build.VERSION.SDK_INT >= 33)
            extras?.getParcelable("android.appInfo", ApplicationInfo::class.java)
        else extras?.getParcelable("android.appInfo")
        val pm = context.packageManager
        fromExtras?.let { info ->
            pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }?.let { return it }
        }
        try {
            val info = if (Build.VERSION.SDK_INT >= 33)
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            else pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(info).toString()
            if (label.isNotBlank() &&
                !label.equals(packageName.substringAfterLast('.'), ignoreCase = true))
                return label
            pm.getLaunchIntentForPackage(packageName)?.let { launch ->
                pm.resolveActivity(launch, 0)?.loadLabel(pm)?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            if (label.isNotBlank()) return label
        } catch (_: PackageManager.NameNotFoundException) {
            pm.getLaunchIntentForPackage(packageName)?.let { launch ->
                try {
                    pm.resolveActivity(launch, 0)?.loadLabel(pm)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                } catch (_: RuntimeException) { }
            }
        }
        return packageName.substringAfterLast('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun rowParams(topMargin: Int) =
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = dp(topMargin)
        }

    private fun dp(value: Int) = (value * density).toInt()
}
