package com.example.spartanbar

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal fun formatLiveDuration(millis: Long, roundUp: Boolean): String {
    val seconds = (millis + if (roundUp) 999 else 0) / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
    else String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
}

internal data class MetroLiveActivity(
    val notification: StatusBarNotification,
    val kind: Kind,
    val title: String,
    val detail: String,
    val endsAtMillis: Long?,
    val actions: List<Notification.Action>,
    val startedAtMillis: Long? = null,
    val frozenTimeMillis: Long? = null
) {
    enum class Kind { MUSIC, TIMER, STOPWATCH, ALARM, CALL }

    fun compactText(now: Long = System.currentTimeMillis()): String {
        if (kind == Kind.MUSIC) return title
        if (kind == Kind.CALL) return startedAtMillis?.let {
            "Call " + formatLiveDuration((now - it).coerceAtLeast(0), roundUp = false)
        } ?: detail.takeIf { it.length in 1..12 } ?: "Call"
        if (kind == Kind.ALARM) return detail.ifBlank { title }
        val millis = frozenTimeMillis ?: if (kind == Kind.TIMER) {
            endsAtMillis?.let { (it - now).coerceAtLeast(0) }
        } else startedAtMillis?.let { (now - it).coerceAtLeast(0) }
        if (millis == null) return if (kind == Kind.TIMER) "TIMER" else "STOPWATCH"
        return formatLiveDuration(millis, roundUp = kind == Kind.TIMER)
    }

    companion object {
        fun from(sbn: StatusBarNotification, context: Context): MetroLiveActivity? {
            val notification = sbn.notification
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?.takeIf { it.isNotBlank() } ?: "Music"
            val detail = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val actions = notification.actions?.toList().orEmpty()
            // Ongoing (answered) phone call: CATEGORY_CALL + ongoing, no full-screen ring intent.
            val ongoingCall = notification.category == Notification.CATEGORY_CALL &&
                notification.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
                notification.fullScreenIntent == null &&
                extras.getInt("android.callType", 2) != 1
            if (ongoingCall) {
                val started = notification.`when`.takeIf {
                    extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && it > 0
                }
                val name = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                return MetroLiveActivity(sbn, Kind.CALL, name.ifBlank { "Call" }, detail, null, actions,
                    startedAtMillis = started)
            }
            val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                notification.category == Notification.CATEGORY_TRANSPORT
            if (isMedia) return MetroLiveActivity(sbn, Kind.MUSIC, title, detail, null, actions)
            return ClockNotificationReader.read(context, sbn)
        }
    }
}

internal class MetroLiveActivityView(
    context: Context,
    private val onOpen: (MetroLiveActivity) -> Unit,
    private val onAction: (PendingIntent) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val artwork = ImageView(context)
    private val artFallback = TextView(context)
    private val artFrame = FrameLayout(context)
    private val heading = TextView(context)
    private val detail = TextView(context)
    private val timer = TextView(context)
    private val accentEdge = View(context)
    private var scrubLabel: String? = null
    private val scrubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scrubOpacity = 0f
    private var scrubAnimator: android.animation.ValueAnimator? = null
    private val progress = MetroTrackProgressView(context,
        onSeek = { position -> controller?.transportControls?.seekTo(position) },
        onScrubChanged = { label ->
            scrubAnimator?.cancel()
            if (label != null) scrubLabel = label
            scrubAnimator = android.animation.ValueAnimator.ofFloat(scrubOpacity,
                if (label != null) 1f else 0f).apply {
                duration = 140L
                addUpdateListener {
                    scrubOpacity = it.animatedValue as Float
                    if (scrubOpacity == 0f) scrubLabel = null
                    invalidate()
                }
                start()
            }
        }
    )
    private val controls = LinearLayout(context)
    private var activity: MetroLiveActivity? = null
    private var controller: MediaController? = null
    private var callbackRegistered = false
    private var accent = Color.rgb(0, 120, 215)
    private var contentIdentity: String? = null
    private val swipeSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeEligible = false
    private var swiping = false

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderContent()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            buildControls()
            tick()
        }
        override fun onSessionDestroyed() {
            detachController()
            controller = null
            buildControls()
            tick()
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(42, 42, 42))
        accentEdge.setBackgroundColor(accent)
        addView(accentEdge, LayoutParams(LayoutParams.MATCH_PARENT, dp(3)))
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(8))
        }
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.apply {
            textSize = 17f
            typeface = MetroFont.light(context)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { activity?.let(onOpen) }
        }
        textColumn.addView(heading)
        detail.apply {
            textSize = 12f
            typeface = MetroFont.light(context)
            setTextColor(Color.LTGRAY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textColumn.addView(detail, LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        timer.apply {
            textSize = 34f
            fontFeatureSettings = "tnum"
            setTextColor(accent)
            typeface = MetroFont.light(context)
            setTextColor(Color.WHITE)
        }
        textColumn.addView(timer)
        top.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(12)
        })
        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artFrame.addView(artwork, FrameLayout.LayoutParams(dp(52), dp(52)))
        artFallback.apply {
            gravity = Gravity.CENTER
            textSize = 25f
            typeface = MetroFont.light(context)
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
        }
        artFrame.addView(artFallback, FrameLayout.LayoutParams(dp(52), dp(52)))
        artFrame.setOnClickListener { activity?.let(onOpen) }
        top.addView(artFrame, 0, LayoutParams(dp(52), dp(52)).apply {
            rightMargin = dp(14)
        })
        top.addView(TextView(context).apply {
            text = "×"
            textSize = 22f
            typeface = MetroFont.light(context)
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            contentDescription = "Close live activity"
            setOnClickListener { onClose() }
        }, LayoutParams(dp(32), dp(40)))
        addView(top)
        controls.gravity = Gravity.CENTER
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
    }

    fun setActivity(value: MetroLiveActivity) {
        activity = value
        val token = if (value.kind == MetroLiveActivity.Kind.MUSIC) {
            val extras = value.notification.notification.extras
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            }
        } else null
        if (controller?.sessionToken != token) {
            detachController()
            controller = try { token?.let { MediaController(context, it) } }
                catch (_: RuntimeException) { null }
            attachController()
        }
        renderContent()
        buildControls()
        tick()
    }

    private fun renderContent() {
        val current = activity ?: return
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: current.title
        heading.text = title
        detail.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: current.detail
        timer.visibility = if (current.kind != MetroLiveActivity.Kind.MUSIC) VISIBLE else GONE
        if (current.kind == MetroLiveActivity.Kind.TIMER) {
            detail.text = if (current.frozenTimeMillis != null) "Paused" else "Time remaining"
        }
        val art = try {
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (bitmap != null) BitmapDrawable(resources, bitmap)
            else current.notification.notification.getLargeIcon()?.loadDrawable(context)
        } catch (_: RuntimeException) { null }
        artwork.setImageDrawable(art)
        artwork.visibility = if (art != null && current.kind == MetroLiveActivity.Kind.MUSIC)
            VISIBLE else GONE
        artFallback.visibility = if (artwork.visibility == VISIBLE) GONE else VISIBLE
        artFallback.text = "♫"
        artFrame.visibility = if (current.kind == MetroLiveActivity.Kind.MUSIC) VISIBLE else GONE
        if (current.kind == MetroLiveActivity.Kind.STOPWATCH)
            detail.text = if (current.frozenTimeMillis != null) "Paused" else "Elapsed time"
        if (current.kind == MetroLiveActivity.Kind.ALARM) {
            heading.text = "Alarm"
            detail.text = "Next alarm"
            timer.visibility = VISIBLE
            timer.text = current.compactText()
            artFrame.visibility = GONE
        }
        val newIdentity = current.notification.key + title
        if (contentIdentity != newIdentity) {
            contentIdentity = newIdentity
            listOf(artFrame, heading, detail, timer).forEach {
                it.alpha = 0f
                it.animate().alpha(1f).setDuration(180L).start()
            }
        }
    }

    private fun buildControls() {
        controls.removeAllViews()
        val current = activity ?: return
        val media = controller
        if (current.kind == MetroLiveActivity.Kind.MUSIC && media != null) {
            val state = media.playbackState
            val actions = state?.actions ?: 0L
            val playing = state?.state == PlaybackState.STATE_PLAYING ||
                state?.state == PlaybackState.STATE_BUFFERING
            addMediaControl(MetroPlaybackIcon.PREVIOUS, "Previous track",
                actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
                media.transportControls.skipToPrevious()
            }
            addMediaControl(if (playing) MetroPlaybackIcon.PAUSE else MetroPlaybackIcon.PLAY,
                if (playing) "Pause" else "Play",
                actions and (PlaybackState.ACTION_PLAY_PAUSE or
                    if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) != 0L) {
                if (playing) media.transportControls.pause() else media.transportControls.play()
            }
            addMediaControl(MetroPlaybackIcon.NEXT, "Next track",
                actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                media.transportControls.skipToNext()
            }
        } else if (current.actions.isNotEmpty()) {
            current.actions.take(3).forEach { action ->
                val icon = try { action.getIcon()?.loadDrawable(context)?.mutate() }
                    catch (_: RuntimeException) { null }
                val button = ImageView(context).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    icon?.setTint(Color.WHITE)
                    setImageDrawable(icon)
                    contentDescription = action.title
                    setOnClickListener { onAction(action.actionIntent) }
                }
                if (icon != null && current.kind == MetroLiveActivity.Kind.MUSIC)
                    controls.addView(button, LayoutParams(dp(48), dp(46)))
                else controls.addView(TextView(context).apply {
                    text = action.title
                    textSize = 13f
                    typeface = MetroFont.regular(context)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    contentDescription = action.title
                    setOnClickListener { onAction(action.actionIntent) }
                }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(46)))
            }
        } else {
            addMediaControl(MetroPlaybackIcon.OPEN, "Open activity", true) { onOpen(current) }
        }
    }

    private fun addMediaControl(icon: MetroPlaybackIcon, label: String,
        available: Boolean, action: () -> Unit) {
        controls.addView(MetroPlaybackIconView(context, icon).apply {
            contentDescription = label
            isEnabled = available
            alpha = if (available) 1f else 0.3f
            setOnClickListener {
                scaleX = 0.84f
                scaleY = 0.84f
                animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                action()
            }
        }, LayoutParams(dp(64), dp(46)))
    }

    fun tick() {
        val current = activity ?: return
        if (current.kind == MetroLiveActivity.Kind.TIMER ||
            current.kind == MetroLiveActivity.Kind.STOPWATCH ||
            current.kind == MetroLiveActivity.Kind.ALARM) timer.text = current.compactText()
        val state = controller?.playbackState
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val hasProgress = current.kind == MetroLiveActivity.Kind.MUSIC && duration > 0 &&
            state != null && state.position >= 0
        progress.visibility = if (hasProgress) VISIBLE else GONE
        if (!hasProgress || state == null) return
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
                * state.playbackSpeed).toLong()
        }
        progress.update(position.coerceIn(0, duration), duration,
            state.actions and PlaybackState.ACTION_SEEK_TO != 0L)
    }

    fun setAccentColor(color: Int) {
        accent = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        accentEdge.setBackgroundColor(accent)
        timer.setTextColor(accent)
        artFallback.setBackgroundColor(accent)
        progress.setAccentColor(accent)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        scrubLabel?.let { label ->
            scrubPaint.color = Color.argb((165 * scrubOpacity).toInt(), 35, 35, 35)
            canvas.drawRect(0f, 0f, width.toFloat(), progress.top.toFloat(), scrubPaint)
            scrubPaint.color = Color.argb((255 * scrubOpacity).toInt(), 255, 255, 255)
            scrubPaint.setShadowLayer(3f * density, 0f, density, Color.BLACK)
            scrubPaint.textSize = 24f * density
            scrubPaint.typeface = MetroFont.light(context)
            val baseline = height / 2f - (scrubPaint.ascent() + scrubPaint.descent()) / 2f
            canvas.drawText(label, (width - scrubPaint.measureText(label)) / 2f,
                baseline, scrubPaint)
            scrubPaint.clearShadowLayer()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                swiping = false
                swipeEligible = activity?.kind == MetroLiveActivity.Kind.MUSIC &&
                    event.y < getChildAt(0).bottom
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - swipeStartX)
                val dy = abs(event.y - swipeStartY)
                if (swipeEligible && dx > swipeSlop && dx > dy * 1.5f) {
                    swiping = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(event)
        val dx = event.x - swipeStartX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val offset = dx.coerceIn(-64f * density, 64f * density)
                listOf(heading, detail, artFrame).forEach { it.translationX = offset }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    abs(dx) >= 48f * density &&
                    abs(dx) > abs(event.y - swipeStartY) * 1.5f) {
                    switchTrack(next = dx < 0)
                }
                listOf(heading, detail, artFrame).forEach {
                    it.animate().translationX(0f).setDuration(180L).start()
                }
                swiping = false
                swipeEligible = false
                return true
            }
        }
        return true
    }

    private fun switchTrack(next: Boolean) {
        val media = controller
        if (media != null) {
            val required = if (next) PlaybackState.ACTION_SKIP_TO_NEXT
                else PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if ((media.playbackState?.actions ?: 0L) and required != 0L) {
                if (next) media.transportControls.skipToNext()
                else media.transportControls.skipToPrevious()
            }
        } else {
            val action = activity?.actions?.firstOrNull {
                val label = it.title?.toString().orEmpty()
                if (next) label.contains("next", true) || label.contains("skip", true)
                else label.contains("previous", true) || label.contains("back", true)
            }
            action?.let { onAction(it.actionIntent) }
        }
    }

    private fun attachController() {
        if (isAttachedToWindow && !callbackRegistered && controller != null) {
            controller?.registerCallback(mediaCallback)
            callbackRegistered = true
        }
    }
    private fun detachController() {
        if (callbackRegistered) controller?.unregisterCallback(mediaCallback)
        callbackRegistered = false
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachController()
    }
    override fun onDetachedFromWindow() {
        scrubAnimator?.cancel()
        scrubLabel = null
        scrubOpacity = 0f
        detachController()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * density).toInt()
}

internal class MetroNowPlayingView(
    context: Context,
    private val onOpen: (MetroLiveActivity) -> Unit,
    private val onTrack: (String?, Drawable?) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val accentEdge = View(context)
    private val artwork = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artFallback = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "\u266b"
        textSize = 28f
        typeface = MetroFont.light(context)
        setTextColor(Color.WHITE)
    }
    private val artFrame = FrameLayout(context)
    private val heading = TextView(context).apply {
        textSize = 17f
        typeface = MetroFont.light(context)
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = TextView(context).apply {
        textSize = 13f
        typeface = MetroFont.light(context)
        setTextColor(Color.LTGRAY)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val progress = MetroTrackProgressView(context,
        onSeek = { position -> controller?.transportControls?.seekTo(position) },
        onScrubChanged = { }
    )
    private var activity: MetroLiveActivity? = null
    private var controller: MediaController? = null
    private var callbackRegistered = false
    private var accent = Color.rgb(0, 120, 215)
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            buildControls()
            tick()
        }
        override fun onSessionDestroyed() {
            detachController()
            controller = null
            buildControls()
            tick()
        }
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.rgb(44, 44, 44))
        visibility = GONE
        accentEdge.setBackgroundColor(accent)
        addView(accentEdge, LayoutParams(dp(4), LayoutParams.MATCH_PARENT))
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(4), dp(8))
        }
        artFrame.addView(artwork, FrameLayout.LayoutParams(dp(72), dp(72)))
        artFallback.setBackgroundColor(accent)
        artFrame.addView(artFallback, FrameLayout.LayoutParams(dp(72), dp(72)))
        val open = { activity?.let(onOpen) }
        artFrame.setOnClickListener { open() }
        heading.setOnClickListener { open() }
        row.addView(artFrame, LayoutParams(dp(72), dp(72)))
        val textColumn = LinearLayout(context).apply { orientation = VERTICAL }
        textColumn.addView(heading)
        textColumn.addView(artist, LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        row.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
            rightMargin = dp(4)
        })
        row.addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)))
        column.addView(row)
        column.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    fun setAccent(color: Int) {
        accent = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        accentEdge.setBackgroundColor(accent)
        artFallback.setBackgroundColor(accent)
        progress.setAccentColor(accent)
    }

    fun clearSession() {
        activity = null
        detachController()
        controller = null
        onTrack(null, null)
    }

    fun setSession(value: MetroLiveActivity, color: Int) {
        activity = value
        setAccent(color)
        val token = mediaToken(value)
        if (controller?.sessionToken != token) {
            detachController()
            controller = try { token?.let { MediaController(context, it) } }
                catch (_: RuntimeException) { null }
            attachController()
        }
        render()
        buildControls()
        tick()
    }

    fun tick() {
        val state = controller?.playbackState
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val hasProgress = duration > 0 && state != null && state.position >= 0
        progress.visibility = if (hasProgress) VISIBLE else GONE
        if (!hasProgress || state == null) return
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
                * state.playbackSpeed).toLong()
        }
        progress.update(position.coerceIn(0, duration), duration,
            state.actions and PlaybackState.ACTION_SEEK_TO != 0L)
    }

    private fun render() {
        val current = activity ?: return
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: current.title
        val subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: current.detail
        heading.text = title
        artist.text = subtitle
        contentDescription = "$title, $subtitle"
        val art = try {
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (bitmap != null) BitmapDrawable(resources, bitmap)
            else current.notification.notification.getLargeIcon()?.loadDrawable(context)
        } catch (_: RuntimeException) { null }
        artwork.setImageDrawable(art)
        artwork.visibility = if (art != null) VISIBLE else GONE
        artFallback.visibility = if (art != null) GONE else VISIBLE
        onTrack(title, art)
    }

    private fun buildControls() {
        controls.removeAllViews()
        val media = controller
        if (media == null) {
            addControl(MetroPlaybackIcon.OPEN, "Open", true) { activity?.let(onOpen) }
            return
        }
        val state = media.playbackState
        val actions = state?.actions ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING
        addControl(MetroPlaybackIcon.PREVIOUS, "Previous track",
            actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
            media.transportControls.skipToPrevious()
        }
        addControl(if (playing) MetroPlaybackIcon.PAUSE else MetroPlaybackIcon.PLAY,
            if (playing) "Pause" else "Play",
            actions and (PlaybackState.ACTION_PLAY_PAUSE or
                if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) != 0L) {
            if (playing) media.transportControls.pause() else media.transportControls.play()
        }
        addControl(MetroPlaybackIcon.NEXT, "Next track",
            actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
            media.transportControls.skipToNext()
        }
    }

    private fun addControl(icon: MetroPlaybackIcon, label: String,
        available: Boolean, action: () -> Unit) {
        controls.addView(MetroPlaybackIconView(context, icon).apply {
            contentDescription = label
            isEnabled = available
            alpha = if (available) 1f else 0.3f
            setOnClickListener {
                scaleX = 0.84f
                scaleY = 0.84f
                animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                action()
            }
        }, LayoutParams(dp(40), dp(44)))
    }

    private fun mediaToken(value: MetroLiveActivity): MediaSession.Token? {
        val extras = value.notification.notification.extras
        return if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }
    }

    private fun attachController() {
        if (isAttachedToWindow && !callbackRegistered && controller != null) {
            controller?.registerCallback(mediaCallback)
            callbackRegistered = true
        }
    }
    private fun detachController() {
        if (callbackRegistered) controller?.unregisterCallback(mediaCallback)
        callbackRegistered = false
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachController()
    }
    override fun onDetachedFromWindow() {
        detachController()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * density).toInt()
}

private enum class MetroPlaybackIcon { PREVIOUS, PLAY, PAUSE, NEXT, OPEN }

private class MetroPlaybackIconView(context: Context, private val icon: MetroPlaybackIcon) :
    View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        val size = 24f * resources.displayMetrics.density
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        when (icon) {
            MetroPlaybackIcon.PAUSE -> {
                canvas.drawRect(5f, 3f, 9f, 21f, paint)
                canvas.drawRect(15f, 3f, 19f, 21f, paint)
            }
            MetroPlaybackIcon.PLAY -> triangle(canvas, 5f, 3f, 20f, 12f, 5f, 21f)
            MetroPlaybackIcon.NEXT -> {
                triangle(canvas, 2f, 4f, 12f, 12f, 2f, 20f)
                triangle(canvas, 12f, 4f, 22f, 12f, 12f, 20f)
            }
            MetroPlaybackIcon.PREVIOUS -> {
                triangle(canvas, 22f, 4f, 12f, 12f, 22f, 20f)
                triangle(canvas, 12f, 4f, 2f, 12f, 12f, 20f)
            }
            MetroPlaybackIcon.OPEN -> {
                triangle(canvas, 6f, 5f, 20f, 12f, 6f, 19f)
            }
        }
        canvas.restore()
    }
    private fun triangle(canvas: Canvas, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        canvas.drawPath(path, paint)
    }
}
