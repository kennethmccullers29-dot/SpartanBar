package com.example.spartanbar

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser

/** W10M / WP8.1 volume drop-down: Ringer + Notifications, Media + Apps, Vibrate toggle. */
@SuppressLint("ViewConstructor")
internal class VolumePanelView(
    context: Context,
    private val topInset: Int,
    var accent: Int,
    /** Stream the hardware key just changed; shown alone when collapsed. */
    var focusStream: Int,
    private val onInteract: () -> Unit,
    private val onSizeChange: (Int) -> Unit
) : View(context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    // Layout is drawn at 1.4x the WP8.1 metrics, text at 1.15x.
    private val d = resources.displayMetrics.density * 1.4f
    private val sp = resources.displayMetrics.scaledDensity * 1.15f
    var expanded = false
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = MetroFont.regular(context)
    private val light = MetroFont.light(context)
    private var dragging = -1
    private var downX = 0f; private var downY = 0f

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (dragging < 0) invalidate() }
    }

    private class Glyph(val path: Path, val box: Float)
    private fun glyph(data: String, box: Float) = Glyph(PathParser.createPathFromPathData(data), box)
    private val ringer = glyph("M1792 1536v128h-512q0 53-20 99t-55 82t-81 55t-100 20q-53 0-99-20t-82-55t-55-81t-20-100H256v-128h128V768q0-88 23-170t64-153t100-129t130-100t153-65t170-23t170 23t153 64t129 100t100 130t65 153t23 170v768zm-256 0V768q0-106-40-199t-110-162t-163-110t-199-41t-199 40t-162 110t-110 163t-41 199v768zm-512 256q27 0 50-10t40-27t28-41t10-50H896q0 27 10 50t27 40t41 28t50 10", 2048f)
    private val ringerOff = glyph("M896 1664q0 26 10 49t27 41t40 28t50 10q23 65 57 121q-14 3-28 5t-28 2q-53 0-99-20t-82-55t-55-81t-20-100H256v-128h128V768q0-88 23-170t64-153t100-129t130-100t153-65t170-23t170 23t153 64t129 100t100 130t65 153t23 170v228q-16-2-32-3t-32-1t-32 1t-32 3V768q0-106-40-199t-110-162t-163-110t-199-41t-199 40t-162 110t-110 163t-41 199v768h484q-2 16-3 32t-1 32t1 32t3 32zm704-512q93 0 174 35t143 96t96 142t35 175q0 93-35 174t-96 143t-142 96t-175 35q-93 0-174-35t-143-96t-96-142t-35-175q0-93 35-174t96-143t142-96t175-35m-320 448q0 66 25 124t68 102t102 69t125 25q47 0 92-13t84-40l-443-443q-26 39-39 84t-14 92m587 176q26-39 39-84t14-92q0-66-25-124t-69-101t-102-69t-124-26q-47 0-92 13t-84 40z", 2048f)
    private val speaker = glyph("M1673 119q90 90 160 195t117 220t73 239t25 251q0 128-25 251t-72 239t-118 220t-160 195l-91-91q81-81 144-175t106-198t65-215t22-226t-22-226t-65-214t-106-198t-144-176zm-9 905q0 180-68 343t-194 291l-91-91q109-109 167-249t58-294t-58-294t-167-249l91-91q126 128 194 291t68 343m-534-362q73 73 111 166t39 196q0 103-38 196t-112 166l-90-90q54-54 83-124t29-148q0-77-29-147t-83-125zM677 256h91v1536h-90l-385-384H0V640h293zm-37 219L347 768H128v512h219l293 293z", 2048f)
    // MDL2 has no vibrate glyph; Shake Device is the closest one.
    private val vibrate = glyph("M1897 1527q0 34-18 63t-49 45l-804 400q-13 6-26 9t-27 4q-35 0-63-17t-45-50L164 574q-13-28-13-53q0-34 18-63t49-45l804-400q28-13 53-13q35 0 64 18t44 49l701 1407q13 28 13 53m-921 390l790-393l-694-1393l-790 393zm427-383l-201 100l-57-115l201-100zm261-1187l-83 82l-90-90l237-237l237 237l-90 90l-83-82v293h-128zM384 1701l83-82l90 90l-237 237l-237-237l90-90l83 82v-293h128z", 2048f)
    private val volume0 = glyph("M677 256h91v1536h-90l-385-384H0V640h293zm-37 1317V475L347 768H128v512h219z", 2048f)
    private val volume1 = glyph("M1130 662q73 73 111 166t39 196q0 103-38 196t-112 166l-90-90q54-54 83-124t29-148q0-77-29-147t-83-125zM677 256h91v1536h-90l-385-384H0V640h293zm-37 219L347 768H128v512h219l293 293z", 2048f)
    private val volume2 = glyph("M1402 390q126 128 194 291t68 343t-68 343t-194 291l-91-91q109-109 167-249t58-294t-58-294t-167-249zm-272 272q73 73 111 166t39 196q0 103-38 196t-112 166l-90-90q54-54 83-124t29-148q0-77-29-147t-83-125zM677 256h91v1536h-90l-385-384H0V640h293zm-37 219L347 768H128v512h219l293 293z", 2048f)
    private val volumeMuted = glyph("M1472 896q119 0 224 45t183 124t123 183t46 224q0 119-45 224t-124 183t-183 123t-224 46q-119 0-224-45t-183-124t-123-183t-46-224q0-119 45-224t124-183t183-123t224-46m0 1024q93 0 174-35t142-96t96-142t36-175q0-93-35-174t-96-142t-142-96t-175-36q-93 0-174 35t-142 96t-96 142t-36 175q0 93 35 174t96 142t142 96t175 36M640 768v512h122q-8 32-14 64t-9 64H512V640h293l384-384h91v506q-67 18-128 48V475L858 768zm1069 557l-147 147l147 147l-90 90l-147-146l-147 146l-90-90l146-147l-146-147l90-90l147 147l147-147z", 2048f)
    private fun speakerFor(stream: Int): Glyph {
        val f = level(stream) / max(stream).toFloat()
        return when { f <= 0f -> volumeMuted; f < 0.34f -> volume1; f < 0.67f -> volume2; else -> speaker }
    }
    private val chevronDown = glyph("m1939 467l90 90l-1005 1005L19 557l90-90l915 915z", 2048f)
    private val chevronUp = glyph("m1955 1533l-931-930l-931 930l-90-90L1024 421l1021 1022z", 2048f)

    // WP8.1 proportions scaled up; compact sits a little higher, still clear of the camera hole.
    private val compactH get() = 32f * d
    private val padTop get() = 6f * d
    private val rowH get() = 56f * d
    private val footH get() = 38f * d
    private val compactInset get() = (topInset - 5f * resources.displayMetrics.density)
    private fun streams() = listOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)
    private fun compactTotal() = compactInset + compactH
    private fun expandedTotal() = topInset + padTop + rowH * 2 + footH
    fun panelHeight(): Int = (if (expanded) expandedTotal() else compactTotal()).toInt()

    /** 0 = compact, 1 = expanded; drives height, crossfade, staggered rows and the chevron. */
    private var progress = 0f
    private var expandAnim: android.animation.ValueAnimator? = null
    private val decel = MetroMotion.Decel
    private val shown = HashMap<Int, Float>()

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (value) onSizeChange(expandedTotal().toInt())
        expandAnim?.cancel()
        expandAnim = android.animation.ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
            duration = if (value) 340L else 260L
            interpolator = decel
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!expanded && progress == 0f) onSizeChange(compactTotal().toInt())
                }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(context, volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply { addAction(AudioManager.RINGER_MODE_CHANGED_ACTION) },
            ContextCompat.RECEIVER_EXPORTED)
        translationY = -panelHeight().toFloat()
        setLayerType(LAYER_TYPE_HARDWARE, null)
        animate().translationY(0f).setDuration(280).setInterpolator(decel)
            .withEndAction { setLayerType(LAYER_TYPE_NONE, null) }.start()
    }

    override fun onDetachedFromWindow() {
        expandAnim?.cancel()
        try { context.unregisterReceiver(volumeReceiver) } catch (_: Exception) { }
        super.onDetachedFromWindow()
    }

    fun slideAway(done: () -> Unit) {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        animate().translationY(-currentHeight()).setDuration(200)
            .setInterpolator(MetroMotion.Accel).withEndAction(done).start()
    }

    private fun label(stream: Int) = if (stream == AudioManager.STREAM_RING) "Ringer + Notifications" else "Media + Apps"
    private fun level(stream: Int) = audio.getStreamVolume(stream)
    private fun max(stream: Int) = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    private fun trackLeft() = 36f * d
    private fun trackRight() = width - 14f * d
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun currentHeight() = lerp(compactTotal(), expandedTotal(), progress)

    /** Eases the displayed level toward the real one so key presses glide instead of jumping. */
    private fun animatedLevel(stream: Int): Float {
        val target = level(stream).toFloat()
        val now = shown[stream] ?: target
        val next = if (dragging >= 0 || kotlin.math.abs(target - now) < 0.02f) target else now + (target - now) * 0.24f
        shown[stream] = next
        if (next != target) postInvalidateOnAnimation()
        return next
    }

    private fun drawGlyph(c: Canvas, g: Glyph, x: Float, y: Float, size: Float, color: Int) {
        val p = Path(g.path)
        p.transform(Matrix().apply { setScale(size / g.box, size / g.box); postTranslate(x, y) })
        paint.style = Paint.Style.FILL; paint.color = color
        c.drawPath(p, paint)
    }

    private fun vibrateOn(): Boolean =
        if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) true
        else Settings.System.getInt(context.contentResolver, "vibrate_when_ringing", 0) == 1

    /** "04/10 Ringer + Notifications": large light number, small max, small label. */
    private fun drawHeader(c: Canvas, stream: Int, baseline: Float, value: Float) {
        var x = 12f * d
        paint.textAlign = Paint.Align.LEFT; paint.color = Color.WHITE
        paint.typeface = light; paint.textSize = 21f * sp
        val num = "%02d".format(Math.round(value)); c.drawText(num, x, baseline, paint); x += paint.measureText(num)
        paint.typeface = regular; paint.textSize = 12f * sp; paint.color = Color.rgb(0xb0, 0xb0, 0xb0)
        val mx = "/${max(stream)}"; c.drawText(mx, x, baseline, paint); x += paint.measureText(mx) + 8f * d
        paint.textSize = 13.5f * sp; paint.color = Color.WHITE
        c.drawText(label(stream), x, baseline, paint)
    }

    private inline fun faded(c: Canvas, alpha: Float, dy: Float, block: () -> Unit) {
        if (alpha <= 0.01f) return
        val save = if (alpha < 0.99f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
            else c.save()
        c.translate(0f, dy)
        block()
        c.restoreToCount(save)
    }

    override fun onDraw(c: Canvas) {
        val h = currentHeight()
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(0x2b, 0x2b, 0x2b)
        c.drawRect(0f, 0f, width.toFloat(), h, paint)
        c.save(); c.clipRect(0f, 0f, width.toFloat(), h)
        val p = progress
        // Compact strip fades out as the panel opens.
        val compactMid = compactInset + compactH / 2f
        faded(c, (1f - p * 2.2f).coerceIn(0f, 1f), -p * 8f * d) {
            drawHeader(c, focusStream, compactMid + 7f * d, animatedLevel(focusStream))
        }
        var top = topInset + padTop
        val dnd = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        streams().forEachIndexed { i, stream ->
            val a = ((p - 0.2f - i * 0.15f) / 0.5f).coerceIn(0f, 1f)
            val rowTop = top
            faded(c, a, (1f - a) * -12f * d) {
                val v = animatedLevel(stream)
                drawHeader(c, stream, rowTop + 22f * d, v)
                val cy = rowTop + 40f * d
                val icon = if (stream == AudioManager.STREAM_RING) (if (dnd || level(stream) == 0) ringerOff else ringer) else speakerFor(stream)
                drawGlyph(c, icon, 12f * d, cy - 7f * d, 14f * d, Color.WHITE)
                val l = trackLeft(); val r = trackRight(); val x = l + (r - l) * (v / max(stream))
                paint.color = Color.rgb(0x80, 0x80, 0x80); c.drawRect(l, cy - 1f * d, r, cy + 1f * d, paint)
                paint.color = accent; c.drawRect(l, cy - 1f * d, x, cy + 1f * d, paint)
                c.drawRect(x - 3f * d, cy - 8f * d, x + 3f * d, cy + 8f * d, paint)
            }
            top += rowH
        }
        val fy = top + footH / 2
        val fa = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
        faded(c, fa, (1f - fa) * -12f * d) {
            drawGlyph(c, vibrate, 12f * d, fy - 7f * d, 14f * d, accent)
            paint.typeface = MetroFont.semibold(context); paint.textSize = 11.5f * sp; paint.color = accent
            paint.textAlign = Paint.Align.LEFT; paint.letterSpacing = 0.04f
            c.drawText(if (vibrateOn()) "VIBRATE ON" else "VIBRATE OFF", 32f * d, fy + 4f * d, paint)
            paint.letterSpacing = 0f
        }
        // One chevron travels from the strip to the footer, turning over on the way.
        val chevY = lerp(compactMid, fy, p)
        val chevX = width - 20f * d
        c.save(); c.rotate(180f * p, chevX, chevY)
        drawGlyph(c, chevronDown, chevX - 6f * d, chevY - 6f * d, 12f * d, Color.WHITE)
        c.restore()
        c.restore()
    }

    private fun rowAt(y: Float): Int {
        if (!expanded || progress < 1f) return -1
        val i = ((y - padTop) / rowH).toInt()
        return if (y >= padTop && i in streams().indices) i else -1
    }

    private fun setFrom(row: Int, x: Float) {
        val stream = streams()[row]
        val frac = ((x - trackLeft()) / (trackRight() - trackLeft())).coerceIn(0f, 1f)
        val v = Math.round(frac * max(stream))
        if (v != level(stream)) try { audio.setStreamVolume(stream, v, 0) } catch (_: SecurityException) { }
        shown[stream] = v.toFloat()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        onInteract()
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) return false
        e.offsetLocation(0f, -topInset.toFloat())
        val bodyBottom = currentHeight() - topInset
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                val row = rowAt(e.y)
                if (row >= 0 && e.y - padTop - row * rowH > 26f * d && e.x > trackLeft() - 16f * d && e.x < trackRight() + 16f * d) {
                    dragging = row; setFrom(row, e.x)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) setFrom(dragging, e.x)
            MotionEvent.ACTION_UP -> {
                if (dragging < 0 && !expanded) setExpanded(true)
                else if (dragging < 0 && progress >= 1f && e.y > bodyBottom - footH) {
                    if (e.x > width - 72f * d) setExpanded(false)
                    else if (e.x < width / 2) toggleVibrate()
                }
                dragging = -1
            }
            MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return true
    }
    private fun toggleVibrate() {
        val on = vibrateOn()
        if (Settings.System.canWrite(context) && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            Settings.System.putInt(context.contentResolver, "vibrate_when_ringing", if (on) 0 else 1)
        } else try {
            audio.ringerMode = if (on) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE
        } catch (_: SecurityException) { }
        invalidate()
    }
}