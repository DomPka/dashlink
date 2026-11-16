package com.dompka.dashlink

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import java.util.Locale

/** Small pieces the live viewer and the clip player share: both put cards and controls over video. */
internal object MediaChrome {

    /**
     * [ctx] with night mode forced on: views and glass built from it come out dark whatever the phone's mode, for
     * cards that sit on black video. Tokens and the Glass builders read the mode from the context they are given.
     */
    fun dark(ctx: Context): Context = ContextThemeWrapper(ctx, R.style.AppTheme).apply {
        applyOverrideConfiguration(Configuration().also {
            it.uiMode = Configuration.UI_MODE_NIGHT_YES or (ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        })
    }

    /**
     * A full-round text button on [background] with [label] in [color] and the press feedback. The platform's
     * button style would show the label in capitals and add its own height and shadow; all of that is taken off,
     * so the text reads exactly as written.
     */
    fun button(c: Context, label: String, id: Int, background: Drawable, color: Int): Button {
        val b = Button(c)
        b.id = id
        b.text = label
        b.transformationMethod = null
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(c.dpi(16f), 0, c.dpi(16f), 0)
        b.background = background
        Type.button(b, color)
        Glass.pressable(b)
        return b
    }

    /** A 1dp hairline in white at [alpha] around a frame with [corners], drawn over video (5.8, 5.10). */
    fun hairline(c: Context, corners: Corners, alpha: Float): Drawable = GradientDrawable().apply {
        val rtl = c.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        cornerRadii = corners.radii(c.resources.displayMetrics.density, rtl)
        setColor(Color.TRANSPARENT)
        setStroke(c.dpi(1f), withAlpha(Color.WHITE, alpha))
    }

    /** "mm:ss" for [ms] (minutes go past 59 rather than adding hours: no camera clip is that long). */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
