package com.dompka.dashlink

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Corner radii in dp in the order top-left, top-right, bottom-right, bottom-left, as written for a left-to-right
 * layout (3.4). The tight "signature" corner is the bottom-left one there; [radii] mirrors the corners for
 * right-to-left layouts so it stays at the bottom start.
 */
internal class Corners(val tl: Float, val tr: Float, val br: Float, val bl: Float) {

    /** The eight values `GradientDrawable.setCornerRadii` wants, in px, mirrored when [rtl]. */
    fun radii(density: Float, rtl: Boolean): FloatArray {
        val (a, b, c, d) = if (rtl) listOf(tr, tl, bl, br) else listOf(tl, tr, br, bl)
        return floatArrayOf(a, a, b, b, c, c, d, d).also { for (i in it.indices) it[i] *= density }
    }

    val isUniform: Boolean get() = tl == tr && tr == br && br == bl

    companion object {
        fun uniform(v: Float) = Corners(v, v, v, v)

        /** Cards: link, live tile, facts. */
        val CARD = Corners(28f, 28f, 28f, 8f)

        /** Sub-cards: notice, transport, state cards. */
        val SUB = Corners(24f, 24f, 24f, 8f)
        val ROW = Corners(18f, 18f, 18f, 6f)
        val FIELD = uniform(16f)
        val THUMB = uniform(10f)
        val VIDEO = Corners(20f, 20f, 20f, 6f)
        val DIALOG = CARD

        /** Download panel pinned to the bottom of a phone. */
        val PANEL_PHONE = Corners(28f, 28f, 0f, 0f)

        /** Chips, pills and buttons: the radius is clamped to half the height, so any big number is fully round. */
        val PILL = uniform(1000f)
    }
}

/** Builders for the glass look (spec 3). Each call returns a fresh drawable that is safe to set on one view. */
internal object Glass {

    private fun isRtl(c: Context) = c.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun shape(
        c: Context, corners: Corners, fill: Int = Color.TRANSPARENT, strokeDp: Float = 0f, stroke: Int = 0,
        dashed: Boolean = false,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        if (corners.isUniform) cornerRadius = c.dp(corners.tl) else cornerRadii = corners.radii(c.resources.displayMetrics.density, isRtl(c))
        setColor(fill)
        if (strokeDp > 0f) {
            if (dashed) setStroke(c.dpi(strokeDp), stroke, c.dp(6f), c.dp(4f)) else setStroke(c.dpi(strokeDp), stroke)
        }
    }

    /** A 1 dp-style hairline stroke only. */
    private fun hairline(c: Context, corners: Corners, color: Int) = shape(c, corners, strokeDp = 1f, stroke = color)

    /** The ripple colour: dark teal 14% on light, white 18% on dark (3.6). */
    private fun rippleColor(t: Tokens) = if (t.dark) withAlpha(Color.WHITE, 0.18f) else withAlpha(t.onAccentContainer, 0.14f)

    /** Wraps [content] in a bounded ripple that is masked by [corners]. */
    private fun ripple(c: Context, content: Drawable, corners: Corners, color: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(color), content, shape(c, corners, fill = Color.BLACK))

    /**
     * Glass of [level] 0-3 with [corners] (3.1, 3.2). Levels 0 and 1 are the cheap two-layer recipe (fill and
     * hairline) for list rows, chips and round buttons. Levels 2 and 3 add the top sheen, a bright top edge and a
     * soft bottom shade for cards and panels; the sheen is dropped in battery saver.
     */
    fun surface(c: Context, level: Int, corners: Corners): Drawable {
        val t = Tokens.of(c)
        val fill = shape(c, corners, withAlpha(t.glassBase, t.glassAlpha(level)))
        val line = hairline(c, corners, t.hairline)
        if (level <= 1) return LayerDrawable(arrayOf(fill, line))

        val r = corners.radii(c.resources.displayMetrics.density, isRtl(c))
        val layers = ArrayList<Drawable>(5)
        layers += fill
        if (!t.reduceSheen) {
            val sheenColor = withAlpha(Color.WHITE, if (t.dark) 0.10f else 0.30f)
            layers += GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sheenColor, withAlpha(Color.WHITE, 0f))).apply {
                cornerRadii = floatArrayOf(r[0], r[1], r[2], r[3], 0f, 0f, 0f, 0f)
            }
        }
        layers += line
        layers += GradientDrawable().apply { setColor(withAlpha(Color.WHITE, if (t.dark) 0.28f else 0.90f)) }
        layers += GradientDrawable().apply { setColor(withAlpha(t.edgeShade, if (t.dark) 0.35f else 0.07f)) }

        val ld = LayerDrawable(layers.toTypedArray())
        var i = 0
        i++ // fill
        if (!t.reduceSheen) {
            ld.setLayerGravity(i, Gravity.TOP)
            ld.setLayerHeight(i, c.dpi(72f))
            i++
        }
        i++ // hairline
        ld.setLayerGravity(i, Gravity.TOP)
        ld.setLayerHeight(i, c.dpi(1f))
        ld.setLayerInsetLeft(i, r[0].toInt())
        ld.setLayerInsetRight(i, r[2].toInt())
        i++
        ld.setLayerGravity(i, Gravity.BOTTOM)
        ld.setLayerHeight(i, c.dpi(1f))
        ld.setLayerInsetLeft(i, r[6].toInt())
        ld.setLayerInsetRight(i, r[4].toInt())
        return ld
    }

    /** Card: link card, live tile card, facts (level 2, 28/28/28/8). Give it elevation 2 with [elevate]. */
    fun card(c: Context): Drawable = surface(c, 2, Corners.CARD)

    /** Sub-card: transport and state cards (level 2, 24/24/24/8). */
    fun subCard(c: Context): Drawable = surface(c, 2, Corners.SUB)

    /**
     * Download panel (level 3). On a phone it is pinned to the bottom with a flat lower edge; [floating] gives
     * the notched card used on wide screens. Elevation 6 with [elevate].
     */
    fun panel(c: Context, floating: Boolean = false): Drawable =
        surface(c, 3, if (floating) Corners.CARD else Corners.PANEL_PHONE)

    /** A clip row: level 1 glass, 18/18/18/6, with ripple; [selected] adds the accent overlay (5.6). */
    fun row(c: Context, selected: Boolean = false): Drawable {
        val t = Tokens.of(c)
        val base = surface(c, 1, Corners.ROW)
        val content = if (selected) LayerDrawable(arrayOf(base, selectedOverlay(c))) else base
        return ripple(c, content, Corners.ROW, rippleColor(t))
    }

    /** Accent at 12% with a 1dp accent stroke at 60%, drawn over a ticked row (5.6). */
    fun selectedOverlay(c: Context): Drawable {
        val t = Tokens.of(c)
        return shape(c, Corners.ROW, withAlpha(t.accent, 0.12f), 1f, withAlpha(t.accent, 0.60f))
    }

    /** Folder chip, fully round (5.4): off is glass with the text1 colour, on is the accent container with an accent stroke. */
    fun chip(c: Context, on: Boolean): Drawable {
        val t = Tokens.of(c)
        val content = if (on) shape(c, Corners.PILL, withAlpha(t.accentContainer, 0.92f), 1.5f, withAlpha(t.accent, 0.60f))
        else surface(c, 1, Corners.PILL)
        return ripple(c, content, Corners.PILL, rippleColor(t))
    }

    /** Round glass button for icons (refresh, back, +-10 s), level 1 glass with ripple. */
    fun iconButton(c: Context): Drawable =
        ripple(c, surface(c, 1, Corners.PILL), Corners.PILL, rippleColor(Tokens.of(c)))

    /** Background of the link card's state pill (5.2): colours by state, plus a 1dp accent stroke while reconnecting. */
    fun statePill(c: Context, state: LensGlyphDrawable.State, reconnecting: Boolean = false): Drawable {
        val look = Tokens.of(c).pillLook(state, reconnecting)
        return shape(c, Corners.PILL, look.fill, if (look.stroke != 0) 1f else 0f, look.stroke)
    }

    /**
     * Filled accent button (5.2): fully round, accent fill, a bright 1dp top edge and a 2dp bottom shade.
     * [heightDp] must be the height the button will have so the edge lines stop before the round ends.
     */
    fun buttonFilled(c: Context, heightDp: Float = 56f): Drawable {
        val t = Tokens.of(c)
        val inset = c.dpi(heightDp / 2f)
        val ld = LayerDrawable(
            arrayOf(
                shape(c, Corners.PILL, t.accent),
                GradientDrawable().apply { setColor(withAlpha(Color.WHITE, 0.35f)) },
                GradientDrawable().apply { setColor(withAlpha(Color.BLACK, 0.12f)) },
            ),
        )
        ld.setLayerGravity(1, Gravity.TOP)
        ld.setLayerHeight(1, c.dpi(1f))
        ld.setLayerInsetLeft(1, inset)
        ld.setLayerInsetRight(1, inset)
        ld.setLayerGravity(2, Gravity.BOTTOM)
        ld.setLayerHeight(2, c.dpi(2f))
        ld.setLayerInsetLeft(2, inset)
        ld.setLayerInsetRight(2, inset)
        return ripple(c, ld, Corners.PILL, withAlpha(t.onAccent, 0.20f))
    }

    /** Tonal button (Disconnect, Stop, Open); [outlined] adds the 1dp accent stroke at 35% used by Disconnect. Never red. */
    fun buttonTonal(c: Context, outlined: Boolean = false): Drawable {
        val t = Tokens.of(c)
        val content = shape(c, Corners.PILL, t.tonal, if (outlined) 1f else 0f, withAlpha(t.accent, 0.35f))
        return ripple(c, content, Corners.PILL, rippleColor(t))
    }

    /** Outline button without fill (the notice action, 5.3): a [strokeDp] stroke in [color]. */
    fun buttonOutline(c: Context, color: Int, strokeDp: Float = 1.5f): Drawable =
        ripple(c, shape(c, Corners.PILL, strokeDp = strokeDp, stroke = color), Corners.PILL, withAlpha(color, 0.18f))

    /** Inset text field (3.3), 16dp radius; the stroke becomes 2dp accent while focused. */
    fun field(c: Context): Drawable {
        val t = Tokens.of(c)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(c, Corners.FIELD, insetFill(t), 2f, t.accent))
            addState(intArrayOf(), inset(c, Corners.FIELD, dashed = false))
        }
    }

    private fun insetFill(t: Tokens) = if (t.dark) withAlpha(Color.WHITE, 0.08f) else withAlpha(t.edgeShade, 0.06f)

    /** Inset glass with a 1dp border at text1 18%; [dashed] draws the border as dashes (the "not available" tile, 5.8). */
    fun inset(c: Context, corners: Corners, dashed: Boolean): Drawable {
        val t = Tokens.of(c)
        return shape(c, corners, insetFill(t), 1f, withAlpha(t.text1, 0.18f), dashed)
    }

    /** Text or icon pill over video (3.8): black 55% with a white 20% hairline, no blur. Fixed in both modes. */
    fun videoOverlay(c: Context, corners: Corners = Corners.PILL): Drawable =
        shape(c, corners, withAlpha(Color.BLACK, 0.55f), 1f, withAlpha(Color.WHITE, 0.20f))

    /** Amber warning glass (5.3): 24/24/24/8, warning fill and stroke, with a 4dp bar at the start. */
    fun notice(c: Context): Drawable {
        val t = Tokens.of(c)
        val fill = shape(c, Corners.SUB, withAlpha(t.warnFill, if (t.dark) 0.80f else 0.88f), 1f, t.warnStroke)
        val bar = GradientDrawable().apply {
            setColor(t.warnStroke)
            cornerRadius = c.dp(2f)
        }
        val ld = LayerDrawable(arrayOf(fill, bar))
        ld.setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
        ld.setLayerWidth(1, c.dpi(4f))
        // Starting 4dp in keeps the bar inside the rounded corners, which are 24dp (top) and 8dp (bottom).
        ld.setLayerInsetStart(1, c.dpi(4f))
        ld.setLayerInsetTop(1, c.dpi(12f))
        ld.setLayerInsetBottom(1, c.dpi(12f))
        return ld
    }

    /** A thumbnail that has not loaded yet: 135 degree gradient from the accent container to the tonal colour (5.6). */
    fun thumbPlaceholder(c: Context): Drawable {
        val t = Tokens.of(c)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.accentContainer, t.tonal)).apply {
            cornerRadius = c.dp(Corners.THUMB.tl)
        }
    }

    /** A vector [icon] tinted [iconTint] in a circle of [circleColor]; the badge is [sizeDp] wide, the icon [iconDp]. */
    fun iconBadge(
        c: Context, icon: Int, sizeDp: Float, iconDp: Float, circleColor: Int, iconTint: Int,
    ): Drawable {
        val glyph = checkNotNull(c.getDrawable(icon)).mutate().also { it.setTint(iconTint) }
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(circleColor)
            setSize(c.dpi(sizeDp), c.dpi(sizeDp))
        }
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerGravity(1, Gravity.CENTER)
            setLayerSize(1, c.dpi(iconDp), c.dpi(iconDp))
        }
    }

    /**
     * Small elevation (3.5) for cards (2dp) and panels (6dp). The shadow is tinted dark teal in light mode;
     * the view's background must be a glass drawable so it supplies the outline.
     */
    fun elevate(view: View, elevationDp: Float) {
        val t = Tokens.of(view.context)
        view.elevation = view.context.dp(elevationDp)
        val shadow = if (t.dark) Color.BLACK else withAlpha(t.onAccentContainer, 0.35f)
        view.outlineSpotShadowColor = shadow
        view.outlineAmbientShadowColor = shadow
    }

    /** Rounds what [view] draws (thumbnail, video) to [corners]; the view needs a size before it shows. */
    fun clipTo(view: View, corners: Corners) {
        val density = view.resources.displayMetrics.density
        val rtl = view.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val radii = corners.radii(density, rtl)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (v.width == 0 || v.height == 0) return
                if (corners.isUniform) {
                    outline.setRoundRect(0, 0, v.width, v.height, radii[0])
                } else {
                    val path = Path()
                    path.addRoundRect(RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()), radii, Path.Direction.CW)
                    outline.setPath(path)
                }
            }
        }
        view.clipToOutline = true
    }

    /** Press feedback (6): the view scales to .97 in 100 ms and back in 200 ms. Does not consume the touch. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            false
        }
    }

    /** Enabled or disabled button look: disabled is drawn at 38% (5.2). */
    fun setEnabledLook(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }
}
