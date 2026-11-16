package com.dompka.dashlink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Colours scaled by a drawable's own alpha (0..255), shared by the small canvas drawables below. */
private fun scaled(color: Int, drawableAlpha: Int): Int = withAlpha(color, drawableAlpha / 255f)

/**
 * The soft colour field behind every screen (2): a base colour, two wallpaper-tinted blobs, one warm
 * headlight blob at the bottom end and a faint wash towards the bottom. It is opaque, static, and only
 * rebuilds its gradients when its bounds change. Install with `window.setBackgroundDrawable(FieldBackground(this))`
 * and build a new one when the light/dark mode changes.
 */
internal class FieldBackground(context: Context) : Drawable() {
    private val t = Tokens.of(context)
    private val basePaint = Paint().apply { color = t.fieldBase }
    private val blobA = radial(t.blobA, 0.12f, -0.04f)
    private val blobB = radial(t.blobB, 1.05f, 0.42f)
    private val headlight = radial(t.headlight, 0.95f, 1.02f)
    private val wash = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (t.dark) intArrayOf(withAlpha(Color.BLACK, 0f), withAlpha(Color.BLACK, 0.30f))
        else intArrayOf(withAlpha(t.fieldBase, 0f), withAlpha(t.fieldBase, 0.35f)),
    )
    private val layers = arrayOf(blobA, blobB, headlight, wash)

    private fun radial(color: Int, cx: Float, cy: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        // Fade to the same colour at alpha 0: fading to transparent black would leave grey fringes.
        colors = intArrayOf(color, withAlpha(color, 0f))
        setGradientCenter(cx, cy)
        gradientRadius = 1f
    }

    override fun onBoundsChange(bounds: Rect) {
        val longer = maxOf(bounds.width(), bounds.height()).toFloat()
        blobA.gradientRadius = maxOf(1f, longer * 0.75f)
        blobB.gradientRadius = maxOf(1f, longer * 0.60f)
        headlight.gradientRadius = maxOf(1f, longer * 0.45f)
        for (l in layers) l.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, basePaint)
        for (l in layers) l.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        for (l in layers) l.alpha = alpha
    }

    override fun getAlpha(): Int = basePaint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * The camera-lens glyph (5.2, 7.1): OFFLINE is a hollow ring, SEARCHING a dashed ring that rotates (1200 ms),
 * CONNECTED a solid dot with a pulse ring (2000 ms) and LOST a ring with a slash. It animates while it is
 * visible and animators are enabled; otherwise it shows a still picture without the pulse.
 */
internal class LensGlyphDrawable(
    private val sizePx: Int,
    ringColor: Int,
    dotColor: Int = ringColor,
    state: State = State.OFFLINE,
) : Drawable(), Animatable {

    enum class State { OFFLINE, SEARCHING, CONNECTED, LOST }

    private var ring = ringColor
    private var dot = dotColor
    private var alphaValue = 255
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedFraction
            invalidateSelf()
        }
    }

    var state: State = state
        set(value) {
            if (field == value) return
            field = value
            phase = 0f
            updateBounds()
            syncAnimation()
            invalidateSelf()
        }

    /** Sets the ring and dot colours, for example from `Tokens.pillLook(...).glyph`. */
    fun setColors(ringColor: Int, dotColor: Int = ringColor) {
        ring = ringColor
        dot = dotColor
        invalidateSelf()
    }

    private fun updateBounds() {
        val s = minOf(bounds.width(), bounds.height()).toFloat()
        val circumference = (2 * Math.PI * 0.39 * s).toFloat()
        val dash = circumference / 16f
        paint.pathEffect = if (state == State.SEARCHING && dash > 0f) DashPathEffect(floatArrayOf(dash, dash), 0f) else null
    }

    override fun onBoundsChange(bounds: Rect) = updateBounds()

    private fun wantsAnimation() = isVisible && (state == State.SEARCHING || state == State.CONNECTED)

    private fun syncAnimation() {
        animator.cancel()
        if (!wantsAnimation() || !ValueAnimator.areAnimatorsEnabled()) {
            phase = 0f
            return
        }
        animator.duration = if (state == State.SEARCHING) 1200L else 2000L
        animator.start()
    }

    override fun start() = syncAnimation()

    override fun stop() {
        animator.cancel()
        phase = 0f
    }

    override fun isRunning(): Boolean = animator.isRunning

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) syncAnimation() else animator.cancel()
        return changed
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val s = minOf(bounds.width(), bounds.height()).toFloat()
        if (s <= 0f) return
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val stroke = s * 0.11f
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.BUTT
        when (state) {
            State.OFFLINE -> {
                paint.style = Paint.Style.STROKE
                paint.color = scaled(ring, alphaValue)
                canvas.drawCircle(cx, cy, s * 0.39f, paint)
            }
            State.SEARCHING -> {
                paint.style = Paint.Style.STROKE
                paint.color = scaled(ring, alphaValue)
                val save = canvas.save()
                canvas.rotate(phase * 360f, cx, cy)
                canvas.drawCircle(cx, cy, s * 0.39f, paint)
                canvas.restoreToCount(save)
            }
            State.CONNECTED -> {
                paint.style = Paint.Style.FILL
                paint.color = scaled(dot, alphaValue)
                canvas.drawCircle(cx, cy, s * 0.22f, paint)
                if (phase > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = s * 0.08f
                    paint.color = withAlpha(dot, 0.5f * (1f - phase) * alphaValue / 255f)
                    canvas.drawCircle(cx, cy, s * 0.24f * (1f + 0.8f * phase), paint)
                }
            }
            State.LOST -> {
                paint.style = Paint.Style.STROKE
                paint.color = scaled(ring, alphaValue)
                canvas.drawCircle(cx, cy, s * 0.39f, paint)
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(cx - s * 0.27f, cy + s * 0.27f, cx + s * 0.27f, cy - s * 0.27f, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The four viewfinder corners (7.2), drawn [insetPx] inside the bounds with arms of [armPx] and a
 * [strokePx] line. Use [video] over the live tile and player, [placeholder] on a thumbnail that has not loaded.
 * Fade it out with `alpha`.
 */
internal class BracketsDrawable(
    private val armPx: Float,
    private val strokePx: Float,
    private val insetPx: Float,
    private val color: Int,
) : Drawable() {
    private var alphaValue = 255
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }

    override fun onBoundsChange(bounds: Rect) {
        val o = insetPx + strokePx / 2f
        val l = bounds.left + o
        val t = bounds.top + o
        val r = bounds.right - o
        val b = bounds.bottom - o
        path.rewind()
        path.moveTo(l, t + armPx); path.lineTo(l, t); path.lineTo(l + armPx, t)
        path.moveTo(r - armPx, t); path.lineTo(r, t); path.lineTo(r, t + armPx)
        path.moveTo(r, b - armPx); path.lineTo(r, b); path.lineTo(r - armPx, b)
        path.moveTo(l + armPx, b); path.lineTo(l, b); path.lineTo(l, b - armPx)
    }

    override fun draw(canvas: Canvas) {
        paint.color = scaled(color, alphaValue)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Over video: 8dp arms, 1.5dp, white 75%, 6dp in from the edge. */
        fun video(c: Context) = BracketsDrawable(c.dp(8f), c.dp(1.5f), c.dp(6f), withAlpha(Color.WHITE, 0.75f))

        /** On an unloaded thumbnail: 6dp arms, 1dp, text3 60%. */
        fun placeholder(c: Context) = BracketsDrawable(c.dp(6f), c.dp(1f), c.dp(4f), withAlpha(Tokens.of(c).text3, 0.6f))
    }
}

/**
 * A dashed lane line (7.3) through the middle of the bounds: horizontal, or [vertical]. [widthPx] is the line
 * thickness; [dashPx] and [gapPx] the pattern. The factories give the few lines the spec uses.
 */
internal class DashLineDrawable(
    private val color: Int,
    private val widthPx: Float,
    dashPx: Float,
    gapPx: Float,
    private val vertical: Boolean = false,
) : Drawable() {
    private var alphaValue = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthPx
        strokeCap = Paint.Cap.BUTT
        pathEffect = DashPathEffect(floatArrayOf(dashPx, gapPx), 0f)
    }

    override fun getIntrinsicWidth(): Int = if (vertical) widthPx.toInt() else -1
    override fun getIntrinsicHeight(): Int = if (vertical) -1 else widthPx.toInt()

    override fun draw(canvas: Canvas) {
        paint.color = scaled(color, alphaValue)
        if (vertical) {
            canvas.drawLine(bounds.exactCenterX(), bounds.top.toFloat(), bounds.exactCenterX(), bounds.bottom.toFloat(), paint)
        } else {
            canvas.drawLine(bounds.left.toFloat(), bounds.exactCenterY(), bounds.right.toFloat(), bounds.exactCenterY(), paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** The amber grabber above the download panel and the stamp line on the viewer: 4dp thick, dash 8, gap 6. Decorative. */
        fun amberLane(c: Context) = DashLineDrawable(Tokens.of(c).lamp, c.dp(4f), c.dp(8f), c.dp(6f))

        /** Vertical divider between the wide-layout columns: 2dp, dash 14, gap 10, text1 at 18%. */
        fun columnDivider(c: Context) = DashLineDrawable(withAlpha(Tokens.of(c).text1, 0.18f), c.dp(2f), c.dp(14f), c.dp(10f), vertical = true)

        /** Small vertical separator before "Rear cam": 1.5dp, dash 4, gap 3, text3 at 50%. */
        fun chipSeparator(c: Context) = DashLineDrawable(withAlpha(Tokens.of(c).text3, 0.5f), c.dp(1.5f), c.dp(4f), c.dp(3f), vertical = true)
    }
}

/** The road-style progress bar (5.7). */
internal object RoadProgressDrawable {
    /**
     * A drawable for `ProgressBar.setProgressDrawable`: a glass track with a dashed centre line (layer id
     * `android.R.id.background`) and an accent fill clipped to the progress (`android.R.id.progress`). Give the
     * bar a height of 8dp; `setProgress(value, true)` animates the change.
     */
    fun create(c: Context): Drawable {
        val t = Tokens.of(c)
        val dash = DashLineDrawable(withAlpha(t.text1, 0.25f), c.dp(2f), c.dp(10f), c.dp(8f))
        val track = LayerDrawable(arrayOf(Glass.surface(c, 1, Corners.PILL), dash)).apply {
            setLayerGravity(1, Gravity.CENTER_VERTICAL)
            setLayerHeight(1, c.dpi(2f))
            setLayerInsetLeft(1, c.dpi(4f))
            setLayerInsetRight(1, c.dpi(4f))
        }
        val fill = ClipDrawable(
            GradientDrawable().apply {
                setColor(t.accent)
                cornerRadius = c.dp(100f)
            },
            Gravity.START, ClipDrawable.HORIZONTAL,
        )
        return LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }
}

/** The "Saved" rubber stamp (5.6, 7.4). */
internal object Stamp {
    /**
     * Turns [tv] into a stamp: overline text, a dashed 1.5dp outline with 4dp corners and no fill, 6 x 2dp
     * padding, rotated -3 degrees. The text is not changed. [color] defaults to the connected green.
     */
    fun apply(tv: TextView, color: Int = Tokens.of(tv.context).connectedText) {
        val c = tv.context
        Type.overline(tv, color)
        tv.background = GradientDrawable().apply {
            cornerRadius = c.dp(4f)
            setColor(Color.TRANSPARENT)
            setStroke(c.dpi(1.5f), color, c.dp(4f), c.dp(2f))
        }
        tv.setPadding(c.dpi(6f), c.dpi(2f), c.dpi(6f), c.dpi(2f))
        tv.rotation = -3f
    }
}

/**
 * A play glyph (or, with [paused], two pause bars) in a filled circle, for over a thumbnail or video (5.6, 5.10).
 * Use [thumb] for the 22dp badge on a clip row and [feedback] for the 56dp tap feedback on the player.
 */
internal class PlayGlyphDrawable(
    private val sizePx: Int,
    private val circleColor: Int,
    private val glyphColor: Int,
    paused: Boolean = false,
) : Drawable() {
    private var alphaValue = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val play = Path()
    private val pause = Path()

    /** True draws the pause bars instead of the triangle. */
    var paused: Boolean = paused
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun onBoundsChange(bounds: Rect) {
        val s = minOf(bounds.width(), bounds.height()).toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        // The triangle is shifted right by a tenth of its width so it looks centred.
        val w = s * 0.34f
        val h = s * 0.40f
        val left = cx - w / 2f + w * 0.1f
        play.rewind()
        play.moveTo(left, cy - h / 2f)
        play.lineTo(left + w, cy)
        play.lineTo(left, cy + h / 2f)
        play.close()
        val bar = s * 0.10f
        val gap = s * 0.09f
        val bh = s * 0.36f
        pause.rewind()
        pause.addRect(cx - gap / 2f - bar, cy - bh / 2f, cx - gap / 2f, cy + bh / 2f, Path.Direction.CW)
        pause.addRect(cx + gap / 2f, cy - bh / 2f, cx + gap / 2f + bar, cy + bh / 2f, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val r = minOf(bounds.width(), bounds.height()) / 2f
        paint.style = Paint.Style.FILL
        paint.color = scaled(circleColor, alphaValue)
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, paint)
        paint.color = scaled(glyphColor, alphaValue)
        canvas.drawPath(if (paused) pause else play, paint)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 22dp, black 35% circle with a white 85% glyph, over a loaded thumbnail. */
        fun thumb(c: Context) = PlayGlyphDrawable(c.dpi(22f), withAlpha(Color.BLACK, 0.35f), withAlpha(Color.WHITE, 0.85f))

        /** 56dp, black 55% circle with a white glyph: the flash on the player when playback toggles. Not a control. */
        fun feedback(c: Context, paused: Boolean = false) =
            PlayGlyphDrawable(c.dpi(56f), withAlpha(Color.BLACK, 0.55f), Color.WHITE, paused)
    }
}

/** The small amber lamp dot (decorative). */
internal object Lamp {
    /** An amber dot of [sizeDp], for the wordmark and the selected chip. */
    fun drawable(c: Context, sizeDp: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Tokens.of(c).lamp)
        setSize(c.dpi(sizeDp), c.dpi(sizeDp))
    }
}
