package com.dompka.dashlink

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.widget.TextView

/**
 * Typography helpers (spec 4): each function styles a TextView in place (size, line height, typeface, letter
 * spacing, colour) and returns it. Only system fonts are used. None of them transforms the text, so
 * `getText()` and accessibility report exactly what was set; text that must look upper case is written in
 * capitals (LIVE, REC, PAUSED). Colours default to the token the spec names for that style.
 */
internal object Type {

    private val medium: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    private val light: Typeface = Typeface.create(Typeface.SANS_SERIF, 300, false)
    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensedMedium: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun TextView.style(
        sp: Float, lineSp: Float?, face: Typeface, tracking: Float, color: Int, tnum: Boolean = false,
    ): TextView {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (lineSp != null) setLineHeight(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineSp, resources.displayMetrics).toInt())
        typeface = face
        letterSpacing = tracking
        fontFeatureSettings = if (tnum) "tnum" else null
        setTextColor(color)
        return this
    }

    /**
     * "DashLink" at 26sp: "Dash" medium, "Link" light, then the amber lamp dot after the text (4). The text
     * stays "DashLink"; only spans are added.
     */
    fun wordmark(tv: TextView, color: Int = Tokens.of(tv.context).text1): TextView {
        tv.style(26f, null, medium, -0.01f, color)
        val text = SpannableString("DashLink")
        text.setSpan(TypefaceSpan(medium), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(TypefaceSpan(light), 4, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = text
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Lamp.drawable(tv.context, 6f), null)
        tv.compoundDrawablePadding = tv.context.dpi(6f)
        return tv
    }

    /** Status headline, 20sp / 26, medium. */
    fun headline(tv: TextView, color: Int = Tokens.of(tv.context).text1) = tv.style(20f, 26f, medium, 0f, color)

    /** Row time (16sp) or, with [screen], a screen title (18sp); medium with tabular figures. */
    fun title(tv: TextView, screen: Boolean = false, color: Int = Tokens.of(tv.context).text1) =
        tv.style(if (screen) 18f else 16f, if (screen) 24f else 22f, medium, 0f, color, tnum = true)

    /** Body, 14sp / 20, regular. */
    fun body(tv: TextView, color: Int = Tokens.of(tv.context).text2) = tv.style(14f, 20f, Typeface.SANS_SERIF, 0.01f, color)

    /** Meta, 13sp / 18, medium: the caption row, "Live view". */
    fun meta(tv: TextView, color: Int = Tokens.of(tv.context).text2) = tv.style(13f, 18f, medium, 0.01f, color)

    /** Caption, 12sp / 16, regular: subtitles. */
    fun caption(tv: TextView, color: Int = Tokens.of(tv.context).text2) = tv.style(12f, 16f, Typeface.SANS_SERIF, 0.02f, color)

    /** Label, 14sp medium: chips and text buttons. */
    fun label(tv: TextView, color: Int = Tokens.of(tv.context).text1) = tv.style(14f, null, medium, 0.01f, color)

    /** Button text, 15sp medium. Never upper case. */
    fun button(tv: TextView, color: Int = Tokens.of(tv.context).text1) = tv.style(15f, null, medium, 0.02f, color)

    /**
     * Overline, 11sp condensed bold, wide tracking: state pill, badges, fact labels, LIVE. This is the smallest
     * text in the app. No caps transformation, so a text such as "Connected" keeps its spelling.
     */
    fun overline(tv: TextView, color: Int = Tokens.of(tv.context).text2) = tv.style(11f, null, condensedBold, 0.10f, color)

    /**
     * OSD, 12sp monospace with tabular figures: file names, timecodes, fps, latency. With [onVideo] the text is
     * white with the OSD shadow so it reads on any frame.
     */
    fun osd(tv: TextView, onVideo: Boolean = false, color: Int = Tokens.of(tv.context).text2): TextView {
        val t = Tokens.of(tv.context)
        tv.style(12f, null, Typeface.MONOSPACE, 0.04f, if (onVideo) t.osdText else color, tnum = true)
        if (onVideo) osdShadow(tv) else tv.setShadowLayer(0f, 0f, 0f, 0)
        return tv
    }

    /** OSD-L, 14sp bold monospace with shadow: the date stamp on video. */
    fun osdL(tv: TextView): TextView {
        val t = Tokens.of(tv.context)
        tv.style(14f, null, monoBold, 0.06f, t.osdText, tnum = true)
        osdShadow(tv)
        return tv
    }

    /** Counts such as "3 / 12": 18sp condensed medium. */
    fun count(tv: TextView, color: Int = Tokens.of(tv.context).text1) = tv.style(18f, null, condensedMedium, 0f, color, tnum = true)

    private fun osdShadow(tv: TextView) {
        tv.setShadowLayer(tv.context.dp(2f), 0f, tv.context.dp(1f), Tokens.of(tv.context).osdShadow)
    }

    /** [text] with every occurrence of each of [parts] in monospace (file name inside a caption). The text is unchanged. */
    fun monoSpans(text: CharSequence, vararg parts: String): CharSequence {
        val out = SpannableString(text)
        for (part in parts) {
            if (part.isEmpty()) continue
            var from = text.indexOf(part)
            while (from >= 0) {
                out.setSpan(TypefaceSpan("monospace"), from, from + part.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                from = text.indexOf(part, from + part.length)
            }
        }
        return out
    }

    /** [text] with every run of digits in monospace ("Normal: 9 files"). The text is unchanged. */
    fun monoDigits(text: CharSequence): CharSequence {
        val out = SpannableString(text)
        var i = 0
        while (i < text.length) {
            if (text[i].isDigit()) {
                var j = i
                while (j < text.length && text[j].isDigit()) j++
                out.setSpan(TypefaceSpan("monospace"), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = j
            } else {
                i++
            }
        }
        return out
    }
}
