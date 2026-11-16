package com.dompka.dashlink

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full-screen live viewer (spec 5.9): the camera's picture on black, letterboxed, with the screen kept on and
 * the system bars out of the way until swiped in. Sideways it fills the screen and its controls (close, LIVE,
 * the readout) fade out after three seconds; a tap brings them back. Upright the picture sits at the top with a
 * card of readouts below it.
 *
 * It streams only while it is on screen (onStart to onStop) and the camera is connected. Whatever else needs the
 * camera comes first: a download pauses it altogether, anything shorter only for a moment, and it picks up again
 * by itself. The date stamp is the phone's clock: the camera's own clock is not read.
 */
class LiveActivity : Activity() {
    companion object {
        fun open(ctx: Context) {
            val intent = Intent(ctx, LiveActivity::class.java)
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }

        /** Controls fade out this long after they were last shown, while the picture plays. */
        private const val HIDE_MS = 3_000L
        private const val FADE_MS = 180L

        /**
         * Right after the viewer opens, the screen it came from may still hold the camera for a moment (its own live
         * tile). A "busy" within this time is shown as starting rather than as a problem.
         */
        private const val GRACE_MS = 2_500L

        private val stampFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
    }

    private val ui = Handler(Looper.getMainLooper())

    /** The cards are dark glass in light mode too: they sit on black. */
    private lateinit var dark: Context
    private lateinit var t: Tokens

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var surface: LiveSurface
    private lateinit var lower: FrameLayout
    private lateinit var info: LinearLayout
    private lateinit var infoFacts: LinearLayout
    private lateinit var infoSub: TextView
    private lateinit var scrim: View
    private lateinit var bar: LinearLayout
    private lateinit var close: ImageButton
    private lateinit var chip: TextView
    private lateinit var readout: TextView
    private lateinit var stamp: TextView
    private lateinit var dim: View
    private lateinit var card: LinearLayout
    private lateinit var cardIcon: ImageView
    private lateinit var cardTitle: TextView
    private lateinit var cardDetail: TextView
    private lateinit var retry: Button
    private lateinit var cardClose: Button

    private val dot = GradientDrawable()
    private var blink: ValueAnimator? = null

    /** What the LIVE chip shows now (null: not drawn since the screen came back). */
    private var chipLive: Boolean? = null

    /** The picture was playing at the last render: the controls start their countdown when it begins. */
    private var wasPlaying = false
    private var cardMono: Boolean? = null
    private var portrait: Boolean? = null
    private var started = false
    private var startedAt = 0L
    private var controlsShown = true
    private val factValues = HashMap<String, TextView>()

    private val hideControls = Runnable { showControls(false) }
    private val rerender = Runnable { render() }
    private val clock = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            stamp.text = stampFormat.format(Date(now))
            ui.postDelayed(this, 1000 - now % 1000)
        }
    }
    private val linkListener: () -> Unit = { linkChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = MediaChrome.dark(this)
        t = Tokens.of(dark)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        build()
        setContentView(root)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Bars swiped in show over black: their icons must be light whatever the phone's mode.
            it.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        arrange()
    }

    override fun onStart() {
        super.onStart()
        started = true
        Thumbs.hold(this, true)
        Link.addListener(linkListener)
        if (Link.ready) begin()
        ui.post(clock)
        render()
    }

    override fun onPause() {
        super.onPause()
        // Closing: let the camera go now rather than in onStop, which comes only after the screen underneath has
        // started and asked for it (the main screen's tile would see "busy" for a moment).
        if (isFinishing) surface.stop()
    }

    override fun onStop() {
        started = false
        surface.stop()
        Thumbs.hold(this, false)
        Link.removeListener(linkListener)
        ui.removeCallbacks(clock)
        ui.removeCallbacks(hideControls)
        ui.removeCallbacks(rerender)
        blink?.cancel()
        blink = null
        chipLive = null
        wasPlaying = false
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Same views, same stream: only where they sit changes.
        arrange()
        render()
    }

    private fun begin() {
        startedAt = SystemClock.uptimeMillis()
        surface.start()
        ui.removeCallbacks(rerender)
        ui.postDelayed(rerender, GRACE_MS)
    }

    private fun linkChanged() {
        if (!started) return
        if (Link.ready) {
            if (surface.status.phase == Live.Phase.STOPPED) begin()
        } else {
            surface.stop()
        }
        render()
    }

    // ---------------------------------------------------------------- views

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun build() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.setOnApplyWindowInsetsListener { v, insets ->
            // The bars are hidden; only a notch or punch hole has to be kept clear of.
            val c = insets.getInsets(WindowInsets.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }

        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        surface = LiveSurface(this)
        surface.id = R.id.live_surface
        surface.contentDescription = "Live picture from the camera. Tap to show or hide the controls."
        surface.setOnClickListener { if (portrait == false) showControls(!controlsShown) }
        surface.onStatus = { render() }
        column.addView(surface, LinearLayout.LayoutParams(match, 0, 1f))
        lower = FrameLayout(this)
        column.addView(lower, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(match, match))
        val hint = TextView(dark)
        hint.text = "Turn the phone sideways for the full picture."
        Type.caption(hint, t.text3)
        hint.gravity = Gravity.CENTER
        lower.addView(hint, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dpi(24f), 0, dpi(24f), dpi(28f))
        })

        // Over the picture itself: a dimming veil for the state cards, and the date stamp at the bottom start.
        dim = View(this)
        dim.setBackgroundColor(withAlpha(Color.BLACK, 0.5f))
        dim.visibility = View.GONE
        surface.addView(dim, FrameLayout.LayoutParams(match, match))
        surface.addView(buildStamp(), FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dpi(16f)
            bottomMargin = dpi(14f)
        })

        scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(Color.BLACK, 0.60f), withAlpha(Color.BLACK, 0f)),
        )
        root.addView(scrim, FrameLayout.LayoutParams(match, dpi(96f), Gravity.TOP))

        bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dpi(12f), dpi(12f), dpi(16f), 0)
        close = ImageButton(this)
        close.id = R.id.live_close
        close.contentDescription = "Close"
        close.setImageResource(R.drawable.ic_close)
        close.imageTintList = ColorStateList.valueOf(Color.WHITE)
        close.background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(Color.WHITE, 0.25f)),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(Color.BLACK, 0.45f))
                setStroke(dpi(1f), withAlpha(Color.WHITE, 0.20f))
            },
            null,
        )
        close.scaleType = ImageView.ScaleType.CENTER
        close.setOnClickListener { finish() }
        chip = TextView(this)
        chip.id = R.id.live_chip
        chip.text = "LIVE"
        Type.overline(chip, Color.WHITE)
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        chip.gravity = Gravity.CENTER
        chip.setPadding(dpi(10f), 0, dpi(12f), 0)
        dot.shape = GradientDrawable.OVAL
        dot.setColor(Color.WHITE)
        dot.setSize(dpi(8f), dpi(8f))
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        chip.compoundDrawablePadding = dpi(6f)
        readout = TextView(this)
        readout.id = R.id.live_readout
        Type.osd(readout, onVideo = true)
        readout.gravity = Gravity.CENTER
        readout.background = Glass.videoOverlay(this)
        readout.setPadding(dpi(12f), 0, dpi(12f), 0)
        readout.setSingleLine()

        info = buildInfo()
        card = buildCard()
    }

    private fun buildStamp(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val dash = View(this)
        dash.background = DashLineDrawable.amberLane(dark)
        box.addView(dash, LinearLayout.LayoutParams(dpi(24f), dpi(4f)).apply { bottomMargin = dpi(6f) })
        stamp = TextView(this)
        stamp.id = R.id.live_stamp
        Type.osdL(stamp)
        stamp.text = stampFormat.format(Date())
        box.addView(stamp, LinearLayout.LayoutParams(wrap, wrap))
        return box
    }

    /** Upright only: the card under the picture with the readouts and Close. */
    private fun buildInfo(): LinearLayout {
        val c = LinearLayout(dark)
        c.id = R.id.live_info
        c.orientation = LinearLayout.VERTICAL
        c.background = Glass.surface(dark, 3, Corners.SUB)
        c.setPadding(dpi(20f), dpi(12f), dpi(8f), dpi(20f))
        val head = LinearLayout(dark)
        head.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(dark)
        titles.orientation = LinearLayout.VERTICAL
        val title = TextView(dark)
        title.text = "Live view"
        Type.title(title)
        infoSub = TextView(dark)
        Type.caption(infoSub)
        titles.addView(title)
        titles.addView(infoSub)
        head.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f))
        c.addView(head, LinearLayout.LayoutParams(match, dpi(48f)))
        infoFacts = LinearLayout(dark)
        infoFacts.orientation = LinearLayout.VERTICAL
        infoFacts.setPadding(0, dpi(8f), dpi(12f), 0)
        for (label in listOf("Stream", "Picture", "Clock")) infoFacts.addView(fact(label))
        c.addView(infoFacts, LinearLayout.LayoutParams(match, wrap))
        return c
    }

    private fun fact(label: String): View {
        val row = LinearLayout(dark)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dpi(6f), 0, dpi(6f))
        val l = TextView(dark)
        l.text = label
        Type.overline(l)
        val v = TextView(dark)
        Type.osd(v, color = t.text1)
        v.setSingleLine()
        v.gravity = Gravity.END
        factValues[label] = v
        row.addView(l, LinearLayout.LayoutParams(dpi(88f), wrap))
        row.addView(v, LinearLayout.LayoutParams(0, wrap, 1f))
        return row
    }

    /** The state card: starting, busy, not available, not connected. */
    private fun buildCard(): LinearLayout {
        val c = LinearLayout(dark)
        c.id = R.id.live_card
        c.orientation = LinearLayout.VERTICAL
        c.background = Glass.surface(dark, 3, Corners.SUB)
        c.setPadding(dpi(20f), dpi(20f), dpi(20f), dpi(20f))
        c.visibility = View.GONE
        cardIcon = ImageView(dark)
        cardIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        c.addView(cardIcon, LinearLayout.LayoutParams(dpi(32f), dpi(32f)).apply { bottomMargin = dpi(12f) })
        cardTitle = TextView(dark)
        cardTitle.id = R.id.live_card_title
        Type.title(cardTitle, screen = true)
        c.addView(cardTitle, LinearLayout.LayoutParams(match, wrap))
        cardDetail = TextView(dark)
        cardDetail.id = R.id.live_card_detail
        c.addView(cardDetail, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(4f) })
        val buttons = LinearLayout(dark)
        retry = button("Retry", R.id.live_retry, filled = true)
        retry.setOnClickListener {
            surface.stop()
            begin()
            render()
        }
        cardClose = button("Close", R.id.live_card_close, filled = false)
        cardClose.setOnClickListener { finish() }
        buttons.addView(retry, LinearLayout.LayoutParams(0, dpi(48f), 1f).apply { marginEnd = dpi(12f) })
        buttons.addView(cardClose, LinearLayout.LayoutParams(0, dpi(48f), 1f))
        c.addView(buttons, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(20f) })
        return c
    }

    private fun button(label: String, id: Int, filled: Boolean): Button = MediaChrome.button(
        dark, label, id,
        if (filled) Glass.buttonFilled(dark, 48f) else Glass.buttonTonal(dark),
        if (filled) t.onAccent else t.text1,
    )

    /**
     * Puts the pieces where the screen's shape wants them. The LiveSurface itself never moves: taking it out of
     * its parent would end its surface, and the stream with it.
     */
    private fun arrange() {
        val upright = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (upright == portrait) return
        portrait = upright
        // Only sideways does a tap on the picture do something (show or hide the controls): upright it is just a
        // picture, and a screen reader must not offer it as a button.
        surface.isClickable = !upright
        surface.contentDescription = if (upright) "Live picture from the camera" else "Live picture from the camera. Tap to show or hide the controls."
        for (v in listOf(close, chip, readout, card, info)) (v.parent as? ViewGroup)?.removeView(v)
        root.removeView(bar)
        bar.removeAllViews()
        if (upright) {
            surface.layoutParams = LinearLayout.LayoutParams(match, wrap)
            lower.visibility = View.VISIBLE
            scrim.visibility = View.GONE
            surface.addView(chip, FrameLayout.LayoutParams(wrap, dpi(28f), Gravity.TOP or Gravity.START).apply {
                marginStart = dpi(12f)
                topMargin = dpi(12f)
            })
            val head = info.getChildAt(0) as LinearLayout
            head.addView(close, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
            info.addView(readout, 1, LinearLayout.LayoutParams(wrap, dpi(28f)).apply { topMargin = dpi(8f) })
            lower.addView(info, FrameLayout.LayoutParams(match, wrap, Gravity.TOP).apply {
                setMargins(dpi(16f), dpi(16f), dpi(16f), 0)
            })
            lower.addView(card, FrameLayout.LayoutParams(cardWidth(), wrap, Gravity.CENTER))
        } else {
            surface.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            lower.visibility = View.GONE
            scrim.visibility = View.VISIBLE
            bar.addView(close, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
            bar.addView(chip, LinearLayout.LayoutParams(wrap, dpi(28f)).apply { marginStart = dpi(12f) })
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(readout, LinearLayout.LayoutParams(wrap, dpi(28f)))
            root.addView(bar, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))
            root.addView(card, FrameLayout.LayoutParams(cardWidth(), wrap, Gravity.CENTER))
        }
        // Controls come back in the new shape; the next render starts their countdown again.
        controlsShown = true
        wasPlaying = false
        ui.removeCallbacks(hideControls)
        for (v in listOf(scrim, bar)) {
            v.animate().cancel()
            v.alpha = 1f
        }
        bar.visibility = View.VISIBLE
        if (!upright) scrim.visibility = View.VISIBLE
    }

    private fun cardWidth() = minOf(dpi(280f), resources.displayMetrics.widthPixels - dpi(32f))

    // ---------------------------------------------------------------- state

    private class Look(val icon: Drawable?, val title: String, val detail: String, val mono: Boolean, val retry: Boolean)

    private fun render() {
        if (isDestroyed) return
        val s = surface.status
        val connected = Link.ready
        val playing = connected && s.phase == Live.Phase.PLAYING
        showReadout(s)
        showChip(playing)
        showInfo(s, playing)
        val look = look(s, connected)
        if (look == null) {
            card.visibility = View.GONE
            dim.visibility = View.GONE
        } else {
            if (cardIcon.drawable !== look.icon) cardIcon.setImageDrawable(look.icon)
            set(cardTitle, look.title)
            set(cardDetail, look.detail)
            if (cardMono != look.mono) {
                cardMono = look.mono
                if (look.mono) Type.osd(cardDetail) else Type.body(cardDetail)
            }
            retry.visibility = if (look.retry) View.VISIBLE else View.GONE
            card.visibility = View.VISIBLE
            dim.visibility = if (surface.showing) View.VISIBLE else View.GONE
        }
        // Upright, the state card stands where the readouts would; it has its own Close.
        info.visibility = if (look == null) View.VISIBLE else View.GONE
        // Controls stay while there is something to read or tap; only a playing picture hides them, once it starts.
        if (!playing && !controlsShown) showControls(true)
        if (playing && !wasPlaying) scheduleHide()
        wasPlaying = playing
    }

    private fun look(s: Live.Status, connected: Boolean): Look? {
        val starting = Look(startingIcon, "Starting live view…", s.detail, true, false)
        return when {
            !connected -> Look(lostIcon, "Not connected to the camera", "Live view starts when the camera is connected.", false, false)
            s.phase == Live.Phase.PLAYING -> null
            s.phase == Live.Phase.PAUSED_BUSY && (DownloadService.working || s.detail == LiveSurface.DOWNLOAD_BUSY) ->
                Look(busyIcon, LiveSurface.DOWNLOAD_BUSY, "Live view resumes when the download finishes.", false, false)
            s.phase == Live.Phase.PAUSED_BUSY && SystemClock.uptimeMillis() - startedAt < GRACE_MS -> starting
            s.phase == Live.Phase.PAUSED_BUSY -> Look(busyIcon, "Camera busy", "Live view resumes when the camera is free.", false, false)
            s.phase == Live.Phase.UNAVAILABLE -> Look(offIcon, "Live view not available", sentence(s.detail), false, true)
            else -> starting
        }
    }

    // Made once: a new lens glyph on every status would restart its rotation.
    private val startingIcon by lazy { lens(LensGlyphDrawable.State.SEARCHING, t.accent) }
    private val lostIcon by lazy { lens(LensGlyphDrawable.State.LOST, t.errorText) }
    private val busyIcon by lazy { icon(R.drawable.ic_pause, t.warnText) }
    private val offIcon by lazy { icon(R.drawable.ic_lens_off, t.errorText) }

    /** Setting the same text again would still lay the view out again, twice a second while it plays. */
    private fun set(tv: TextView, text: String) {
        if (tv.text.toString() != text) tv.text = text
    }

    private fun sentence(s: String) = s.replaceFirstChar { it.titlecase(Locale.US) }

    private fun lens(state: LensGlyphDrawable.State, color: Int) = LensGlyphDrawable(dpi(32f), color, color, state)

    private fun icon(res: Int, color: Int): Drawable? = getDrawable(res)?.mutate()?.also { it.setTint(color) }

    /** "24 fps · 850 kb/s", with the lagging part in amber. */
    private fun showReadout(s: Live.Status) {
        val text = s.readout()
        if (readout.text.toString() == text) return
        val styled = SpannableString(text)
        val lag = text.indexOf(" · lagging")
        if (lag >= 0) styled.setSpan(ForegroundColorSpan(Tokens.of(this).laggingAmber), lag, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        readout.text = styled
    }

    /** Red with a blinking dot while the picture is live; a quiet dark pill otherwise. */
    private fun showChip(playing: Boolean) {
        if (chipLive == playing) return
        chipLive = playing
        chip.background = if (playing) {
            GradientDrawable().apply {
                cornerRadius = dp(100f)
                setColor(withAlpha(Tokens.of(this@LiveActivity).liveRed, 0.92f))
            }
        } else {
            Glass.videoOverlay(this)
        }
        blink?.cancel()
        blink = null
        dot.alpha = if (playing) 255 else 150
        if (playing && ValueAnimator.areAnimatorsEnabled()) {
            blink = ValueAnimator.ofInt(255, 40).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { dot.alpha = it.animatedValue as Int }
                start()
            }
        }
    }

    private fun showInfo(s: Live.Status, playing: Boolean) {
        set(infoSub, Link.cameraSsid?.removeSurrounding("\"") ?: "Camera")
        factValues["Stream"]?.let { set(it, if (playing) listOfNotNull(s.stream?.substringAfterLast('/'), s.transport).joinToString(" · ") else "—") }
        factValues["Picture"]?.let { set(it, if (playing) s.detail else "—") }
        factValues["Clock"]?.let { set(it, "phone time") }
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (portrait == false && !touchExploration()) ui.postDelayed(hideControls, HIDE_MS)
    }

    /** With a screen reader on, controls that vanish by themselves would be impossible to reach. */
    private fun touchExploration(): Boolean =
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

    private fun showControls(show: Boolean) {
        controlsShown = show
        ui.removeCallbacks(hideControls)
        for (v in listOf(scrim, bar)) {
            if (v === scrim && portrait != false) continue
            v.animate().cancel()
            if (show) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(FADE_MS).start()
            } else {
                v.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (!controlsShown) v.visibility = View.INVISIBLE }.start()
            }
        }
        if (show && surface.status.phase == Live.Phase.PLAYING) scheduleHide()
    }
}
