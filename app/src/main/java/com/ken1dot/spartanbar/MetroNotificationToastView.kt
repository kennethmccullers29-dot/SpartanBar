package com.ken1dot.spartanbar

import android.animation.LayoutTransition
import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Windows 10 Mobile toast: compact banner with a dark grabber that pulls open
 * into the inline-reply sheet (white box + message glyph + chevron).
 */
internal class MetroNotificationToastView(
    context: Context,
    notification: StatusBarNotification,
    allowReply: Boolean,
    onOpen: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onExpandedChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var touchStartY = 0f
    private var dragging = false
    private var expanded = false
    private var accent = Color.rgb(0, 120, 215)
    private var focusRequestGeneration = 0

    private val titleView: android.widget.TextView
    private val bodyView: android.widget.TextView?
    private val iconView: ImageView
    private val iconTile: FrameLayout
    private val replyRow: LinearLayout?
    private val replyField: EditText?
    private val sendView: ImageView?
    private val handleStrip: FrameLayout
    private val grabber: View
    private val chevron: ImageView
    private val replyAction =
        if (allowReply) NotificationReply.action(notification.notification) else null
    private val canExpand = replyAction != null ||
        (messageText(notification)?.length ?: 0) > 48

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        elevation = 0f
        layoutTransition = LayoutTransition().apply {
            setDuration(LayoutTransition.CHANGING, 180L)
            setDuration(LayoutTransition.APPEARING, 180L)
            setDuration(LayoutTransition.DISAPPEARING, 140L)
            setInterpolator(LayoutTransition.CHANGING, MetroMotion.Decel)
            enableTransitionType(LayoutTransition.CHANGING)
        }

        val appName = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(notification.packageName, 0)
            ).toString()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            notification.packageName.substringAfterLast('.')
        }
        val title = notification.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() } ?: appName
        val message = messageText(notification)?.takeUnless { it == title }

        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(10), dp(8), dp(12), dp(6))
        }

        iconTile = FrameLayout(context)
        iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val drawable = try {
                notification.notification.smallIcon?.loadDrawable(context)?.mutate()
            } catch (_: RuntimeException) {
                null
            } ?: context.getDrawable(R.drawable.metro_message)?.mutate()
            setImageDrawable(drawable)
        }
        iconTile.addView(iconView, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        body.addView(iconTile, LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) })

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.TOP
        }
        titleView = android.widget.TextView(context).apply {
            text = title
            textSize = 16f
            typeface = MetroFont.semibold(context)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textColumn.addView(titleView, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bodyView = message?.let { bodyText ->
            android.widget.TextView(context).apply {
                text = bodyText
                textSize = 15f
                typeface = MetroFont.light(context)
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(dp(1).toFloat(), 1f)
            }.also {
                textColumn.addView(it, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1) })
            }
        }
        body.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        if (replyAction != null) {
            val field = EditText(context).apply {
                // Flat white W10M field: no Material underline/ripple chrome.
                setBackgroundColor(Color.TRANSPARENT)
                backgroundTintList = null
                setTextColor(Color.BLACK)
                setHintTextColor(Color.rgb(120, 120, 120))
                hint = "Type a message"
                textSize = 17f
                typeface = MetroFont.regular(context)
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                isLongClickable = true
                if (Build.VERSION.SDK_INT >= 29) {
                    textCursorDrawable = ColorDrawable(Color.BLACK)
                    textSelectHandle?.mutate()?.setTint(accent)
                }
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
                contentDescription = "Reply"
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        submit()
                        true
                    } else false
                }
                setOnClickListener { requestReplyFocus() }
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) onExpandedChanged(true)
                }
            }
            replyField = field
            val fieldChrome = FrameLayout(context).apply {
                setBackgroundColor(Color.WHITE)
                minimumHeight = dp(44)
                addView(field, FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            }
            val send = ImageView(context).apply {
                setImageResource(R.drawable.metro_message)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, 0, 0, 0)
                isClickable = true
                contentDescription = "Send reply"
                setOnClickListener { submit() }
            }
            sendView = send
            replyRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(2), dp(10), dp(6))
                visibility = GONE
                addView(fieldChrome, LayoutParams(0, dp(44), 1f))
                addView(send, LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(12) })
            }
            addView(replyRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            replyField = null
            sendView = null
            replyRow = null
        }

        grabber = View(context)
        chevron = ImageView(context).apply {
            setImageResource(R.drawable.metro_chevron_up)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            visibility = GONE
        }
        handleStrip = FrameLayout(context).apply {
            // Keep the strip taller than the glyph so Gravity.CENTER can actually center it.
            addView(grabber, FrameLayout.LayoutParams(dp(32), dp(3), Gravity.CENTER))
            addView(chevron, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener {
                if (!canExpand) return@setOnClickListener
                if (expanded) collapse() else expand(focus = replyAction != null)
            }
        }
        addView(handleStrip, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))

        contentDescription = "$appName notification: $title${message?.let { ", $it" } ?: ""}"
        applyAccent(accent)
        setOnClickListener { if (!expanded) onOpen() }
        setOnTouchListener { _, event -> handleGesture(event) }
        updateHandleMark()
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        // Never steal touches aimed at the reply field / send control.
        if (expanded && touchOnReplyChrome(event)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                onDismiss()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - touchStartY
                if (!dragging && kotlin.math.abs(dy) > dp(8)) dragging = true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.rawY - touchStartY
                if (dragging) {
                    when {
                        dy > dp(28) && canExpand && !expanded -> {
                            expand(focus = replyAction != null)
                            return true
                        }
                        dy < -dp(24) && expanded -> {
                            collapse()
                            return true
                        }
                        dy < -dp(24) && !expanded -> {
                            onDismiss()
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun touchOnReplyChrome(event: MotionEvent): Boolean {
        val row = replyRow ?: return false
        if (row.visibility != VISIBLE) return false
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        val x = event.rawX
        val y = event.rawY
        return x >= loc[0] && x <= loc[0] + row.width &&
            y >= loc[1] && y <= loc[1] + row.height
    }

    fun expand(focus: Boolean) {
        if (expanded || !canExpand) return
        expanded = true
        bodyView?.maxLines = if (replyAction != null) 4 else Int.MAX_VALUE
        replyRow?.let { row ->
            row.alpha = 0f
            row.translationY = -dp(8).toFloat()
            row.visibility = VISIBLE
            row.animate().alpha(1f).translationY(0f).setDuration(180L)
                .setInterpolator(MetroMotion.Decel).start()
        }
        chevron.rotation = 180f
        chevron.animate().rotation(0f).setDuration(180L)
            .setInterpolator(MetroMotion.Decel).start()
        applyIconChrome()
        updateHandleMark()
        onExpandedChanged(true)
        if (focus && replyAction != null) requestReplyFocus()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        hideKeyboard()
        replyField?.clearFocus()
        bodyView?.maxLines = 2
        replyRow?.let { row ->
            row.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(140L).setInterpolator(MetroMotion.Accel)
                .withEndAction {
                    row.visibility = GONE
                    row.alpha = 1f
                    row.translationY = 0f
                }.start()
        }
        applyIconChrome()
        updateHandleMark()
        onExpandedChanged(false)
    }

    fun requestReplyFocus() {
        val field = replyField ?: return
        if (!expanded) {
            expand(focus = false)
            onExpandedChanged(true)
        } else {
            onExpandedChanged(true)
        }
        val generation = ++focusRequestGeneration
        // Window flags update in onExpandedChanged; wait a couple frames before IME.
        field.post {
            field.post {
                if (generation != focusRequestGeneration || !isAttachedToWindow) return@post
                field.requestFocus()
                context.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun releaseInput() {
        focusRequestGeneration++
        val field = replyField
        field?.clearFocus()
        clearFocus()
        val token = field?.windowToken ?: windowToken ?: return
        context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(token, 0)
    }

    private fun hideKeyboard() = releaseInput()

    private fun submit() {
        val action = replyAction ?: return
        val text = replyField?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            requestReplyFocus()
            return
        }
        releaseInput()
        if (NotificationReply.send(context, action, text)) onDismiss()
    }

    fun setAccentColor(color: Int) {
        accent = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        applyAccent(accent)
    }

    private fun darkAccent() = mix(accent, Color.BLACK, 0.45f)

    private fun applyAccent(color: Int) {
        setBackgroundColor(color)
        val foreground = if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE
        titleView.setTextColor(foreground)
        bodyView?.setTextColor(foreground)
        iconView.setColorFilter(foreground)
        sendView?.setColorFilter(foreground)
        chevron.setColorFilter(foreground)
        grabber.setBackgroundColor(foreground)
        applyIconChrome()
        handleStrip.setBackgroundColor(darkAccent())
    }

    private fun applyIconChrome() {
        if (expanded) {
            iconTile.background = null
            iconView.layoutParams = (iconView.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(26)
                height = dp(26)
            }
        } else {
            iconTile.background = GradientDrawable().apply {
                setColor(mix(accent, Color.WHITE, 0.18f))
                cornerRadius = 0f
            }
            iconView.layoutParams = (iconView.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(22)
                height = dp(22)
            }
        }
        iconView.requestLayout()
    }

    private fun updateHandleMark() {
        grabber.visibility = if (expanded) GONE else VISIBLE
        chevron.visibility = if (expanded) VISIBLE else GONE
        handleStrip.setBackgroundColor(darkAccent())
        handleStrip.contentDescription = when {
            !canExpand -> "Notification"
            expanded -> "Collapse notification"
            else -> "Expand notification"
        }
    }

    private fun mix(base: Int, blend: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) + (Color.red(blend) - Color.red(base)) * t).toInt(),
            (Color.green(base) + (Color.green(blend) - Color.green(base)) * t).toInt(),
            (Color.blue(base) + (Color.blue(blend) - Color.blue(base)) * t).toInt()
        )
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        private fun messageText(notification: StatusBarNotification): String? {
            val extras = notification.notification.extras
            return listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .firstNotNullOfOrNull { key ->
                    extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
                }
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.joinToString("\n")?.takeIf { it.isNotBlank() }
        }
    }
}
