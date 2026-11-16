package com.dompka.dashlink

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.PowerManager

/**
 * # How to use the design system ("Lens Glass")
 *
 * Screens are built only from the pieces below. All of them are plain views and drawables, there is no
 * blur and no library. Section numbers refer to the visual spec. Every builder takes a [Context] and
 * reads [Tokens] itself, so after a uiMode / contrast change simply build the drawables again.
 *
 * Colours and units
 * - [Tokens.of]: all colours (wallpaper-derived neutrals and accents, fixed signal colours) for the current light/dark mode (1a-1c).
 * - [Tokens.glassBoost] / [Tokens.reduceSheen]: the sunlight, high-contrast and battery-saver multipliers (3.7).
 * - [Corners]: per-corner radii in dp (3.4), mirrored for right-to-left layouts by every builder.
 * - [dp] / [dpi]: dp to float px / rounded px. [withAlpha] / [blend]: colour helpers.
 *
 * Background (2)
 * - [FieldBackground]: the soft colour field; `window.setBackgroundDrawable(FieldBackground(context))`, rebuilds only when its size changes.
 *
 * Glass surfaces ([Glass], 3)
 * - `Glass.surface(context, level, corners)`: glass level 0-3, the generic builder behind the next ones (3.1, 3.2).
 * - `Glass.card`: level 2, 28/28/28/8, link card, live tile card, facts card (3.4).
 * - `Glass.subCard`: level 2, 24/24/24/8, transport and state cards.
 * - `Glass.panel(context, floating)`: level 3 download panel, flat bottom on phones, notched card when floating (5.7).
 * - `Glass.row`: level 1 clip row with ripple, 18/18/18/6 (3.2, 5.6); `Glass.selectedOverlay`: the tint for a ticked row.
 * - `Glass.chip(context, on)`: folder chip off (glass) or on (accent container with stroke); `Glass.iconButton`: round glass button (5.4).
 * - `Glass.statePill(context, state, reconnecting)`: the link-card state pill background (5.2); text colour via `Tokens.pillLook`.
 * - `Glass.buttonFilled` / `buttonTonal` / `buttonOutline`: full-round buttons with ripple (5.2, 5.3, 5.7).
 * - `Glass.field(context)`: inset text field with a 2dp accent stroke on focus (3.3).
 * - `Glass.inset(context, corners, dashed)`: inset glass, optionally with a dashed border ("Live view not available", 5.8).
 * - `Glass.videoOverlay`: black 55% pill with a hairline for text over video (3.8).
 * - `Glass.notice(context)`: amber warning glass with the start bar (5.3).
 * - `Glass.thumbPlaceholder`: 135 degree gradient for a thumbnail that has not loaded (5.6).
 * - `Glass.iconBadge`: a vector icon centred in a coloured circle, e.g. the play badge on a thumbnail (5.6, 5.10).
 * - `Glass.elevate(view, dp)`: small elevation with the tinted shadow (3.5); `Glass.clipTo(view, corners)`: rounds the view's content (thumbnails, video).
 * - `Glass.pressable(view)`: scale .97 press feedback (6); `Glass.setEnabledLook(view, enabled)`: alpha .38 when disabled (5.2).
 *
 * Text ([Type], 4)
 * - `Type.wordmark`: "DashLink" in two weights plus the amber lamp dot. `headline`, `title(screen)`, `body`, `meta`, `caption`, `label`, `button`: sizes and weights.
 * - `Type.overline`: small bold condensed label (state pill, badges), `osd` / `osdL`: monospace camera-overlay text, `count`: "3 / 12".
 * - `Type.monoSpans(text, parts)` and `Type.monoDigits(text)`: monospace spans inside a normal sentence.
 *   No style uses an all-caps transformation, so texts read by tests and accessibility stay as written.
 *
 * Drawables (Drawables.kt)
 * - [LensGlyphDrawable]: the camera-lens glyph with states OFFLINE, SEARCHING, CONNECTED, LOST (5.2, 7.1).
 * - [BracketsDrawable]: the four viewfinder corners over thumbnails and video (5.8).
 * - [DashLineDrawable]: a dashed lane line, horizontal or vertical (7.3).
 * - [RoadProgressDrawable.create]: progress drawable for a ProgressBar, track with a dashed centre line (5.7).
 * - [Stamp.apply]: turns a TextView into the dashed, slightly rotated "Saved" stamp (5.6, 7.4).
 * - [PlayGlyphDrawable]: a play or pause glyph in a circle for over video (5.6, 5.10).
 * - [Lamp.drawable]: the small amber lamp dot (wordmark, selected chip).
 *
 * Icons (res/drawable, tint them with `setTint`): ic_lens, ic_lens_off, ic_play, ic_pause, ic_close, ic_camera,
 * ic_rear_camera, ic_warning, ic_replay_10, ic_forward_10, ic_back, ic_live_dot.
 */
internal class Tokens private constructor(
    val dark: Boolean,
    /** Multiplier for every glass alpha (1.0 normally, more in sunlight / high contrast / battery saver). */
    val glassBoost: Float,
    /** True when the glass should drop its top sheen (battery saver). */
    val reduceSheen: Boolean,
    /** True when the system asks for high contrast: hairlines are drawn in the text colour. */
    val highContrast: Boolean,
    private val palette: Palette,
) {
    val fieldBase get() = palette.fieldBase
    val blobA get() = palette.blobA
    val blobB get() = palette.blobB
    val headlight get() = palette.headlight
    val text1 get() = palette.text1
    val text2 get() = palette.text2
    val text3 get() = palette.text3
    val accent get() = palette.accent
    val onAccent get() = palette.onAccent
    val accentContainer get() = palette.accentContainer
    val onAccentContainer get() = palette.onAccentContainer
    val accentText get() = palette.accentText
    val tonal get() = palette.tonal
    val glassBase get() = palette.glassBase
    val edgeShade get() = palette.edgeShade
    val connectedContainer get() = palette.connectedContainer
    val connectedText get() = palette.connectedText
    val connectedDot get() = palette.connectedDot
    val warnFill get() = palette.warnFill
    val warnText get() = palette.warnText
    val warnStroke get() = palette.warnStroke
    val errorContainer get() = palette.errorContainer
    val errorText get() = palette.errorText

    /** Amber headlight lamp. Decorative only, it never carries meaning alone. */
    val lamp get() = palette.lamp

    /** The red of the LIVE / REC chip fill (same in both modes). */
    val liveRed = 0xFFD92D27.toInt()

    /** The red of a dot: the lighter red on dark surfaces and video. */
    val liveDot get() = if (dark) 0xFFFF5A52.toInt() else 0xFFD92D27.toInt()

    /** Amber used for "lagging" numbers on video. */
    val laggingAmber = 0xFFFFD66B.toInt()

    /** Fixed colour of text on video (OSD). */
    val osdText = Color.WHITE
    val osdShadow = 0x99000000.toInt()

    /** Glass alpha for [level] (0-3) after the boost, never above [MAX_GLASS_ALPHA]. */
    fun glassAlpha(level: Int): Float {
        val base = when (level) {
            0, 1 -> if (dark) 0.55f else 0.68f
            2 -> if (dark) 0.64f else 0.78f
            else -> if (dark) 0.78f else 0.88f
        }
        return minOf(MAX_GLASS_ALPHA, base * glassBoost)
    }

    /** The hairline colour: white, or the text colour at 30% when contrast is raised (3.7). */
    val hairline: Int
        get() = if (highContrast) withAlpha(text1, 0.30f) else withAlpha(Color.WHITE, if (dark) 0.16f else 0.70f)

    /** The colours of the state pill and its lens glyph for [state] (5.2). */
    fun pillLook(state: LensGlyphDrawable.State, reconnecting: Boolean = false): PillLook = when (state) {
        LensGlyphDrawable.State.OFFLINE -> PillLook(withAlpha(tonal, 0.8f), text2, text3, 0)
        LensGlyphDrawable.State.SEARCHING -> PillLook(
            accentContainer, onAccentContainer, onAccentContainer,
            if (reconnecting) withAlpha(accent, 0.5f) else 0,
        )
        LensGlyphDrawable.State.CONNECTED -> PillLook(connectedContainer, connectedText, connectedDot, 0)
        LensGlyphDrawable.State.LOST -> PillLook(errorContainer, errorText, errorText, 0)
    }

    /** Fill, text, glyph and optional 1dp stroke colour (0 = none) of a state pill. */
    class PillLook(val fill: Int, val text: Int, val glyph: Int, val stroke: Int)

    /** The raw colour set of one mode. Kept apart so the JVM tests can build it without a device. */
    internal class Palette(
        val fieldBase: Int, val blobA: Int, val blobB: Int, val headlight: Int,
        val text1: Int, val text2: Int, val text3: Int,
        val accent: Int, val onAccent: Int, val accentContainer: Int, val onAccentContainer: Int, val accentText: Int,
        val tonal: Int, val glassBase: Int, val edgeShade: Int,
        val connectedContainer: Int, val connectedText: Int, val connectedDot: Int,
        val warnFill: Int, val warnText: Int, val warnStroke: Int,
        val errorContainer: Int, val errorText: Int, val lamp: Int,
    )

    companion object {
        const val MAX_GLASS_ALPHA = 0.96f

        /**
         * The multiplier for glass alpha: [contrast] is the system contrast setting (0 to 1, API 34+, 0 below),
         * battery saver gives at least 1.2 (3.7).
         */
        fun glassBoostFor(contrast: Float, batterySaver: Boolean): Float {
            val byContrast = when {
                contrast >= 1f -> 1.3f
                contrast >= 0.5f -> 1.15f
                else -> 1f
            }
            return if (batterySaver) maxOf(byContrast, 1.2f) else byContrast
        }

        /** The palette of one mode from the fallback hex values only (used when a system colour cannot be read, and by tests). */
        internal fun fallbackPalette(dark: Boolean): Palette = if (dark) Palette(
            fieldBase = 0xFF0F1415.toInt(), blobA = withAlpha(0xFF00525A.toInt(), 0.50f), blobB = withAlpha(0xFF1F3437.toInt(), 0.45f),
            headlight = withAlpha(0xFFFFB74D.toInt(), 0.14f),
            text1 = 0xFFE1E3E3.toInt(), text2 = 0xFFBFC8C9.toInt(), text3 = 0xFFA4ADAE.toInt(),
            accent = 0xFF8FD4DA.toInt(), onAccent = 0xFF003A40.toInt(), accentContainer = 0xFF00525A.toInt(),
            onAccentContainer = 0xFFC4EFF2.toInt(), accentText = 0xFF8FD4DA.toInt(),
            tonal = 0xFF364B4E.toInt(), glassBase = 0xFF2E3131.toInt(), edgeShade = 0xFF000000.toInt(),
            connectedContainer = 0xFF123A24.toInt(), connectedText = 0xFF8EE3AE.toInt(), connectedDot = 0xFF5FD38D.toInt(),
            warnFill = 0xFF3D2E00.toInt(), warnText = 0xFFFFD66B.toInt(), warnStroke = 0xFFF2B800.toInt(),
            errorContainer = 0xFF5C1A15.toInt(), errorText = 0xFFFFB4AB.toInt(), lamp = 0xFFFFC247.toInt(),
        ) else Palette(
            fieldBase = 0xFFF4F8F8.toInt(), blobA = withAlpha(0xFF8FD4DA.toInt(), 0.65f), blobB = withAlpha(0xFF9BB0B3.toInt(), 0.50f),
            headlight = withAlpha(0xFFFFB74D.toInt(), 0.24f),
            text1 = 0xFF191C1D.toInt(), text2 = 0xFF3F4849.toInt(), text3 = 0xFF566061.toInt(),
            accent = 0xFF00696F.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentContainer = 0xFFC4EFF2.toInt(),
            onAccentContainer = 0xFF002023.toInt(), accentText = 0xFF00525A.toInt(),
            tonal = 0xFFD2E8EA.toInt(), glassBase = 0xFFFFFFFF.toInt(), edgeShade = 0xFF191C1D.toInt(),
            connectedContainer = 0xFFCDEFD6.toInt(), connectedText = 0xFF0B5A2F.toInt(), connectedDot = 0xFF1E9E5A.toInt(),
            warnFill = 0xFFFFE9B0.toInt(), warnText = 0xFF5A3C00.toInt(), warnStroke = 0xFFE0A100.toInt(),
            errorContainer = 0xFFFFDAD6.toInt(), errorText = 0xFF7A1410.toInt(), lamp = 0xFFE69B00.toInt(),
        )

        private var cached: Tokens? = null
        private var cachedKey: Long = -1

        /**
         * The tokens for the current configuration. Cached until light/dark, contrast or battery saver changes,
         * so the system colours are read once per configuration change.
         */
        fun of(context: Context): Tokens {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val contrast = contrastOf(context)
            val saver = try {
                context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            } catch (_: RuntimeException) {
                false
            }
            val key = (if (dark) 1L else 0L) or ((contrast * 100).toLong() shl 1) or (if (saver) 1L shl 20 else 0L)
            cached?.let { if (cachedKey == key) return it }
            val tokens = Tokens(
                dark = dark,
                glassBoost = glassBoostFor(contrast, saver),
                reduceSheen = saver,
                highContrast = contrast >= 0.5f,
                palette = if (dark) darkPalette(context) else lightPalette(context),
            )
            cached = tokens
            cachedKey = key
            return tokens
        }

        private fun contrastOf(context: Context): Float {
            if (Build.VERSION.SDK_INT < 34) return 0f
            return try {
                context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f
            } catch (_: RuntimeException) {
                0f
            }
        }

        /** One system colour, or [fallback] when the phone does not provide it. */
        private fun sys(context: Context, id: Int, fallback: Int): Int = try {
            context.getColor(id)
        } catch (_: Exception) {
            fallback
        }

        private fun lightPalette(c: Context): Palette {
            val f = fallbackPalette(false)
            return Palette(
                fieldBase = sys(c, android.R.color.system_neutral1_50, f.fieldBase),
                blobA = withAlpha(sys(c, android.R.color.system_accent1_200, 0xFF8FD4DA.toInt()), 0.65f),
                blobB = withAlpha(sys(c, android.R.color.system_accent2_300, 0xFF9BB0B3.toInt()), 0.50f),
                headlight = f.headlight,
                text1 = sys(c, android.R.color.system_neutral1_900, f.text1),
                text2 = sys(c, android.R.color.system_neutral2_700, f.text2),
                text3 = sys(c, android.R.color.system_neutral2_600, f.text3),
                accent = sys(c, android.R.color.system_accent1_600, f.accent),
                onAccent = sys(c, android.R.color.system_accent1_0, f.onAccent),
                accentContainer = sys(c, android.R.color.system_accent1_100, f.accentContainer),
                onAccentContainer = sys(c, android.R.color.system_accent1_900, f.onAccentContainer),
                accentText = sys(c, android.R.color.system_accent1_700, f.accentText),
                tonal = sys(c, android.R.color.system_accent2_100, f.tonal),
                glassBase = sys(c, android.R.color.system_neutral1_0, f.glassBase),
                edgeShade = sys(c, android.R.color.system_neutral1_900, f.edgeShade),
                connectedContainer = f.connectedContainer, connectedText = f.connectedText, connectedDot = f.connectedDot,
                warnFill = f.warnFill, warnText = f.warnText, warnStroke = f.warnStroke,
                errorContainer = f.errorContainer, errorText = f.errorText, lamp = f.lamp,
            )
        }

        private fun darkPalette(c: Context): Palette {
            val f = fallbackPalette(true)
            return Palette(
                fieldBase = try {
                    blend(c.getColor(android.R.color.system_neutral1_900), Color.BLACK, 0.35f)
                } catch (_: Exception) {
                    f.fieldBase
                },
                blobA = withAlpha(sys(c, android.R.color.system_accent1_700, 0xFF00525A.toInt()), 0.50f),
                blobB = withAlpha(sys(c, android.R.color.system_accent2_800, 0xFF1F3437.toInt()), 0.45f),
                headlight = f.headlight,
                text1 = sys(c, android.R.color.system_neutral1_100, f.text1),
                text2 = sys(c, android.R.color.system_neutral2_200, f.text2),
                text3 = sys(c, android.R.color.system_neutral2_300, f.text3),
                accent = sys(c, android.R.color.system_accent1_200, f.accent),
                onAccent = sys(c, android.R.color.system_accent1_800, f.onAccent),
                accentContainer = sys(c, android.R.color.system_accent1_700, f.accentContainer),
                onAccentContainer = sys(c, android.R.color.system_accent1_100, f.onAccentContainer),
                accentText = sys(c, android.R.color.system_accent1_200, f.accentText),
                tonal = sys(c, android.R.color.system_accent2_700, f.tonal),
                glassBase = sys(c, android.R.color.system_neutral1_800, f.glassBase),
                edgeShade = f.edgeShade,
                connectedContainer = f.connectedContainer, connectedText = f.connectedText, connectedDot = f.connectedDot,
                warnFill = f.warnFill, warnText = f.warnText, warnStroke = f.warnStroke,
                errorContainer = f.errorContainer, errorText = f.errorText, lamp = f.lamp,
            )
        }
    }
}

/** [color] with its alpha multiplied by [factor] (0..1). Own helper, no androidx ColorUtils. */
internal fun withAlpha(color: Int, factor: Float): Int {
    val a = (((color ushr 24) and 0xFF) * factor.coerceIn(0f, 1f) + 0.5f).toInt()
    return (a shl 24) or (color and 0x00FFFFFF)
}

/** Linear mix of two colours: [t] = 0 gives [from], 1 gives [to]; alpha is mixed too. */
internal fun blend(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val x = (from ushr shift) and 0xFF
        val y = (to ushr shift) and 0xFF
        return (x + (y - x) * k + 0.5f).toInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** dp to pixels as a float. */
internal fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

/** dp to whole pixels, rounded. */
internal fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
