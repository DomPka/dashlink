package com.dompka.dashlink

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Insets
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ScaleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.LayoutAnimationController
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.animation.TranslateAnimation
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Checkable
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * The single screen. It only draws what Link (the camera link), Browser (file list, selection) and
 * Jobs (download queue) hold, so it can be rebuilt at any moment - rotation, fold/unfold, language
 * change, the system reclaiming it - without losing anything or asking the camera again.
 *
 * It is drawn in the "Lens Glass" look (Tokens, Glass, Type and Drawables.kt): frosted cards on a soft colour field,
 * edge to edge. While the camera is connected a live tile shows what it sees (LiveSurface), and every clip row has a
 * thumbnail of its clip (Thumbs). Neither of them touches the camera while a download has it.
 */
class MainActivity : Activity() {

    private class Notice(val text: String, val button: String?, val action: (() -> Unit)?)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var wifi: WifiManager

    /** The colours of the light or dark mode this screen was built in. */
    private lateinit var t: Tokens

    // The skeleton: two columns that sit under each other on a phone and side by side on a wide screen.
    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var colA: LinearLayout
    private lateinit var colB: LinearLayout
    private lateinit var topScroll: CappedScroll

    /** When a tap last opened the viewer or the player (see [leaving]). */
    private var openedAt = 0L

    /** When the tile last started streaming (see showLive). */
    private var liveStartedAt = 0L
    private lateinit var lane: View
    private lateinit var bottom: LinearLayout
    private var wide: Boolean? = null
    private var builtDensity = 0

    /** What edge to edge has to keep clear: system bars and display cutout, and the keyboard while it is up. */
    private var bars: Insets = Insets.NONE
    private var keyboard = 0

    // The link card.
    private lateinit var pill: TextView
    private lateinit var lens: LensGlyphDrawable
    private lateinit var status: TextView
    private lateinit var fields: LinearLayout
    private lateinit var ssid: EditText
    private lateinit var pass: EditText
    private lateinit var connectBtn: Button

    // The notice card.
    private lateinit var noticeCard: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeBtn: Button

    // The live tile: the picture with its layers, and the title, readout and buttons next to (or under) it.
    private lateinit var tile: LinearLayout
    private lateinit var video: VideoFrame
    private lateinit var live: LiveSurface
    private lateinit var frozen: ImageView
    private lateinit var poster: View
    private lateinit var blank: View
    private lateinit var scrim: View
    private lateinit var chrome: View
    private lateinit var glyph: ImageView
    private lateinit var liveState: TextView
    private lateinit var liveReason: TextView
    private lateinit var liveChip: TextView
    private lateinit var liveDot: GradientDrawable
    private lateinit var spinner: LensGlyphDrawable
    private lateinit var liveInfo: LinearLayout
    private lateinit var liveTexts: LinearLayout
    private lateinit var liveSpacer: View
    private lateinit var liveReadout: TextView
    private lateinit var liveRetry: Button
    private lateinit var liveOpen: Button
    private var blink: ValueAnimator? = null
    private var tileShown = false
    private var liveOn = false
    private var livePhase: Live.Phase? = null

    /** Try again was tapped: the next "starting" is shown, not only a retry Live makes by itself. */
    private var retryTapped = false

    // Folders, the caption above the list, the list.
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroll: EndFadeScroll
    private lateinit var folderBar: LinearLayout
    private lateinit var rearChip: TextView
    private lateinit var refreshBtn: ImageButton
    private lateinit var sheen: Sheen
    private var spin: ObjectAnimator? = null
    private lateinit var noteRow: LinearLayout
    private lateinit var noteView: TextView
    private lateinit var allBtn: Button
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView

    // The download panel.
    private lateinit var nameRow: LinearLayout
    private lateinit var current: TextView
    private lateinit var counts: TextView
    private lateinit var progress: TextView
    private lateinit var bar: ProgressBar
    private lateinit var downloadBtn: Button
    private val clipAdapter = ClipAdapter()

    private var shownClips: List<Cam.Clip>? = null
    private var shownFolders: List<String>? = null
    private var shownSsid: String? = null
    private var shownGone: Boolean? = null
    private var shownPill: String? = null
    private var shownNote: String? = null

    /** The folder the clips in the list come from (Browser may already be loading another one). */
    private var listFolder = ""
    private var saved: Set<String> = emptySet()
    private var dialog: AlertDialog? = null
    private var noticeAction: (() -> Unit)? = null
    private var startProblem: String? = null
    private var resumed = false
    private var started = false
    private var askedAt = 0L
    private var asking = false
    private var autoStartAt = 0L
    private var ticks = 0

    /** The link as it was the last time it changed, to tell a connection that came up from one that failed. */
    private var wasReady = false
    private var lastState = LinkState.IDLE

    /** The list is being dragged or flung: nothing decorative moves meanwhile (6). */
    private var scrolling = false

    /** Height animations under way, so that a newer one for the same view replaces the older one. */
    private val reveals = HashMap<View, ValueAnimator>()

    private val motion: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    /** One object for the whole life of this screen, so it can be taken out of Browser again by identity. */
    private val refresh: () -> Unit = { render() }

    /** The same for Link's listeners. */
    private val linkListener: () -> Unit = { linkChanged() }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dashlink", Context.MODE_PRIVATE)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Edge to edge: the colour field runs under the system bars and the screen keeps its content clear of them.
        window.setDecorFitsSystemWindows(false)
        buildUi()
        Link.init(this)
        Browser.attach(this)
        Browser.onChange = refresh
        // Asked by itself once, on the very first start. After that the notice line explains what is
        // missing and offers it again; a system dialog on every start would only teach people to dismiss it.
        val missing = Report.missingPermissions(this)
        if (savedInstanceState == null && missing.isNotEmpty() && !prefs.getBoolean("askedPermissions", false)) {
            prefs.edit().putBoolean("askedPermissions", true).apply()
            asking = true
            requestPermissions(toRequest(missing), ASK_FIRST)
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
        openedAt = 0L
        wasReady = Link.ready
        lastState = Link.state
        Thumbs.paused = false
        Link.addListener(linkListener)
        updateTile()
        decorations()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ui.removeCallbacks(ticker)
        ui.post(ticker)
        render()            // permissions and battery settings may have been changed while we were away
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ui.removeCallbacks(ticker)
    }

    override fun onStop() {
        started = false
        Link.removeListener(linkListener)
        // Nobody sees this screen now: no thumbnails are fetched for it and the camera is free for others.
        Thumbs.paused = true
        stopLive()
        decorations()
        setSpinning(false)
        super.onStop()
    }

    override fun onDestroy() {
        // Only if it is still ours: a newer screen may already have taken over.
        if (Browser.onChange === refresh) Browser.onChange = null
        // The rows on screen stop waiting for thumbnails: nobody will see them.
        for (i in 0 until list.childCount) (list.getChildAt(i) as? ClipRow)?.let { Thumbs.unbind(it.thumb) }
        live.onStatus = null
        stopLive()
        ui.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        asking = false
        // Both arrays are empty when the request was interrupted; nothing is read from them. What is
        // granted is read back from the system.
        if (requestCode == ASK_AGAIN && permissions.isNotEmpty() && Report.missingPermissions(this).isNotEmpty() &&
            SystemClock.elapsedRealtime() - askedAt < 500
        ) {
            // Android answered without showing its dialog ("don't ask again"): the switch is in Settings now.
            Power.openAppSettings(this)
        }
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.densityDpi != builtDensity) {
            // Every size on the screen was worked out in pixels for the old density: build it again for the new one.
            rebuild()
        } else {
            // Rotation, folding and unfolding, resizing: the same views are put where the new shape wants them.
            arrange()
        }
    }

    // ---------------------------------------------------------------- UI

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val medium: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, 500, false) }

    /**
     * Two columns. A: heading, the camera link, whatever the user should know, the live tile. B: folders and the
     * file list. On a phone they sit under each other and the download panel is pinned over the bottom of the list;
     * on a wide screen (an unfolded phone, landscape) they sit side by side, split by a dashed lane line, and the
     * download panel floats under column A. Column A scrolls rather than push the list or the Download and Stop
     * buttons off a short screen.
     */
    private fun buildUi() {
        t = Tokens.of(this)
        builtDensity = resources.configuration.densityDpi
        window.setBackgroundDrawable(FieldBackground(this))
        frame = FrameLayout(this)
        root = LinearLayout(this)
        root.clipToPadding = false
        root.clipChildren = false
        frame.addView(root, FrameLayout.LayoutParams(match, match))

        colA = LinearLayout(this)
        colA.orientation = LinearLayout.VERTICAL
        colA.clipChildren = false
        topScroll = CappedScroll(this)
        topScroll.isVerticalScrollBarEnabled = false
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.clipChildren = false
        top.setPadding(0, 0, 0, dpi(6f))               // room for the cards' small shadows inside the scrolling area
        topScroll.addView(top, ViewGroup.LayoutParams(match, wrap))
        colA.addView(topScroll)
        top.addView(buildHeading(), LinearLayout.LayoutParams(match, dpi(64f)))
        top.addView(buildLinkCard(), LinearLayout.LayoutParams(match, wrap))
        // Below the link card, so that a long notice on a small screen scrolls instead of pushing Connect out of sight.
        top.addView(buildNotice(), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(12f) })
        top.addView(buildTile(), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(12f) })

        colB = LinearLayout(this)
        colB.orientation = LinearLayout.VERTICAL
        colB.clipChildren = false
        colB.addView(buildChipRow(), LinearLayout.LayoutParams(match, dpi(48f)).apply { topMargin = dpi(8f) })
        colB.addView(buildCaption(), LinearLayout.LayoutParams(match, wrap))
        colB.addView(buildList(), LinearLayout.LayoutParams(match, 0, 1f))

        lane = View(this)
        lane.background = DashLineDrawable.columnDivider(this)
        bottom = buildDownloadPanel()

        frame.setOnApplyWindowInsetsListener { _, insets ->
            bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
            applyInsets()
            WindowInsets.CONSUMED
        }
        setContentView(frame)
        frame.requestApplyInsets()
        wide = null
        arrange()
    }

    /** The whole screen again, for a new density. Nothing is lost: the screen only shows what lives elsewhere. */
    private fun rebuild() {
        val typedName = ssid.text.toString()
        val typedPass = pass.text.toString()
        live.onStatus = null
        stopLive()
        blink?.cancel()
        blink = null
        spin?.cancel()
        spin = null
        for (a in reveals.values.toList()) a.cancel()
        reveals.clear()
        shownClips = null
        shownFolders = null
        shownSsid = null
        shownGone = null
        shownPill = null
        shownNote = null
        tileShown = false
        livePhase = null
        buildUi()
        ssid.setText(typedName)
        pass.setText(typedPass)
        render()
        decorations()
    }

    /**
     * Puts the columns where the width of the screen wants them. Cheap when nothing changed. The two columns stay
     * where they are and only change shape: taking column A out would take the live picture's surface away and
     * restart the stream, and taking the list out would lose its place.
     */
    private fun arrange() {
        val nowWide = resources.configuration.screenWidthDp >= 600
        if (nowWide == wide) return
        wide = nowWide
        (bottom.parent as? ViewGroup)?.removeView(bottom)
        root.removeView(lane)
        if (colA.parent == null) {
            root.addView(colA)
            root.addView(colB)
        }
        if (nowWide) {
            root.orientation = LinearLayout.HORIZONTAL
            colA.layoutParams = LinearLayout.LayoutParams(0, match, 2f)
            topScroll.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            bottom.background = Glass.panel(this, floating = true)
            colA.addView(bottom, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(12f) })
            root.addView(lane, 1, LinearLayout.LayoutParams(dpi(16f), match))
            colB.layoutParams = LinearLayout.LayoutParams(0, match, 3f)
            // The list column starts a little lower, level with the link card rather than with the heading.
            colB.setPadding(0, dpi(16f), 0, 0)
        } else {
            root.orientation = LinearLayout.VERTICAL
            colA.layoutParams = LinearLayout.LayoutParams(match, wrap)
            topScroll.layoutParams = LinearLayout.LayoutParams(match, wrap)
            bottom.background = Glass.panel(this, floating = false)
            colB.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            colB.setPadding(0, 0, 0, 0)
            frame.addView(bottom, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))
        }
        Glass.elevate(bottom, 6f)
        arrangeTile(nowWide)
        applyInsets()
    }

    private fun gutter() = dpi(if (wide == true) 20f else 16f)

    /**
     * How much of a phone's height column A may take before it scrolls: most of it while there is no list to show
     * (a notice about permissions must be readable whole, with its button), a little over half once there is.
     */
    private fun fitColumnA() {
        val share = when {
            wide == true -> 1f
            Link.ready -> 0.55f
            else -> 0.85f
        }
        if (topScroll.share != share) {
            topScroll.share = share
            topScroll.requestLayout()
        }
    }

    /** Keeps the content clear of the system bars, the cutout and the keyboard; the field and the list run on under the bars. */
    private fun applyInsets() {
        val gutter = gutter()
        val nav = navInset()
        // Column A's scroll box reaches into the gutters, so the cards' side shadows stay inside its clip.
        (topScroll.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.leftMargin = -gutter
            it.rightMargin = -gutter
        }
        topScroll.setPadding(gutter, 0, gutter, 0)
        topScroll.clipToPadding = false
        fitColumnA()
        frame.setPadding(0, 0, 0, keyboard)            // what adjustResize did before edge to edge
        root.setPadding(bars.left + gutter, bars.top, bars.right + gutter, 0)
        if (wide == true) {
            bottom.setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(16f))
            (bottom.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = nav + dpi(16f)
            (lane.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dpi(16f)
                it.bottomMargin = nav + dpi(16f)
            }
        } else {
            // Pinned to the bottom edge: the glass runs under the navigation bar, its buttons stay above it.
            bottom.setPadding(bars.left + gutter, dpi(8f), bars.right + gutter, nav + dpi(16f))
        }
        // The folder chips scroll out to the edge of the screen instead of stopping at the gutter.
        (chipRow.layoutParams as? LinearLayout.LayoutParams)?.marginStart = -gutter
        chipScroll.setPaddingRelative(gutter, 0, 0, 0)
        updateListPadding()
        root.requestLayout()
    }

    /** The navigation bar's height, or nothing while the keyboard covers it anyway. */
    private fun navInset() = if (keyboard > 0) 0 else bars.bottom

    /** The list scrolls on under the download panel on a phone, and under the navigation bar on a wide screen. */
    private fun updateListPadding() {
        val pad = if (wide == true) navInset() + dpi(16f) else bottom.height + dpi(16f)
        if (list.paddingBottom != pad) list.setPadding(0, dpi(4f), 0, pad)
        if (empty.paddingBottom != pad) empty.setPadding(dpi(24f), dpi(12f), dpi(24f), pad)
    }

    /** A card (link card, live tile): level 2 glass with the tight bottom-start corner, a small tinted shadow. */
    private fun card(paddingDp: Float): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = Glass.card(this)
        Glass.elevate(c, 2f)
        val p = dpi(paddingDp)
        c.setPadding(p, p, p, p)
        return c
    }

    /** A platform Button without its theme's own look: no capitals, no minimum size, no raised press. */
    private fun button(id: Int, text: String): Button {
        val b = Button(this)
        b.id = id
        b.text = text
        b.transformationMethod = null            // the theme writes button labels in capitals; the text stays as written
        b.stateListAnimator = null
        b.elevation = 0f
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.gravity = Gravity.CENTER
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        return b
    }

    private fun filled(b: Button, heightDp: Float) {
        b.background = Glass.buttonFilled(this, heightDp)
        Type.button(b, t.onAccent)
        b.setPadding(dpi(20f), 0, dpi(20f), 0)
    }

    private fun tonal(b: Button, outlined: Boolean = false) {
        b.background = Glass.buttonTonal(this, outlined)
        Type.button(b, t.text1)
        b.setPadding(dpi(20f), 0, dpi(20f), 0)
    }

    /** A text button (Select all, Try again): accent text, a ripple and nothing else, 48dp tall. */
    private fun textButton(b: Button) {
        b.background = RippleDrawable(ColorStateList.valueOf(rippleColor()), null, pillMask())
        Type.label(b, t.accentText)
        b.setPadding(dpi(12f), 0, dpi(12f), 0)
    }

    /** The ripple colour of the design system (3.6), for the text buttons Glass has no builder for. */
    private fun rippleColor() = if (t.dark) withAlpha(Color.WHITE, 0.18f) else withAlpha(t.onAccentContainer, 0.14f)

    private fun pillMask() = GradientDrawable().apply {
        cornerRadius = dp(1000f)
        setColor(Color.BLACK)
    }

    /** A 1dp line along [corners] in [color], with no fill: hairlines over video and thumbnails. */
    private fun outline(corners: Corners, color: Int) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        if (corners.isUniform) {
            cornerRadius = dp(corners.tl)
        } else {
            cornerRadii = corners.radii(resources.displayMetrics.density, resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        }
        setStroke(dpi(1f), color)
    }

    private fun visible(v: View, on: Boolean) {
        val want = if (on) View.VISIBLE else View.GONE
        if (v.visibility != want) v.visibility = want
    }

    /**
     * Shows or hides [view] by growing or shrinking its height while it fades (6), or at once when animations are
     * off or the screen is not on show yet.
     */
    private fun reveal(view: View, show: Boolean, durationMs: Long) {
        reveals.remove(view)?.cancel()
        val lp = view.layoutParams
        val parent = view.parent as? View
        if (!motion || !started || parent == null || !parent.isLaidOut || (!show && view.visibility != View.VISIBLE)) {
            lp.height = wrap
            view.alpha = 1f
            visible(view, show)
            view.requestLayout()
            return
        }
        val from = if (view.visibility == View.VISIBLE) view.height else 0
        val to = if (show) {
            val width = parent.width - parent.paddingLeft - parent.paddingRight
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.measuredHeight
        } else {
            0
        }
        view.visibility = View.VISIBLE
        lp.height = from
        view.requestLayout()
        val a = ValueAnimator.ofInt(from, to)
        a.duration = durationMs
        a.interpolator = if (show) ENTER else EXIT
        a.addUpdateListener {
            lp.height = it.animatedValue as Int
            view.alpha = if (show) it.animatedFraction else 1f - it.animatedFraction
            view.requestLayout()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (reveals[view] !== animation) return
                reveals.remove(view)
                lp.height = wrap
                view.alpha = 1f
                if (!show) view.visibility = View.GONE
                view.requestLayout()
            }
        })
        reveals[view] = a
        a.start()
    }

    // ---------------------------------------------------------------- heading and link card

    private fun buildHeading(): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(dpi(4f), 0, 0, 0)
        val title = TextView(this)
        Type.wordmark(title)
        // Always within reach, whatever state the rest is in: this is what gets tapped when something is wrong.
        val diag = button(R.id.diag, "Diagnostics")
        // A 36dp glass pill inside a 48dp touch target (5.1).
        diag.background = InsetDrawable(Glass.chip(this, false), 0, dpi(6f), 0, dpi(6f))
        Type.label(diag, t.accentText)
        diag.setPadding(dpi(14f), 0, dpi(14f), 0)
        diag.setOnClickListener { diagnostics() }
        Glass.pressable(diag)
        row.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(diag, LinearLayout.LayoutParams(wrap, dpi(48f)))
        return row
    }

    private fun field(hint: String): EditText {
        val e = EditText(this)
        // The hint is a size smaller than what gets typed (5.2).
        val h = SpannableString(hint)
        h.setSpan(RelativeSizeSpan(14f / 16f), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        e.hint = h
        e.setSingleLine()
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        e.background = Glass.field(this)
        e.setPadding(dpi(16f), 0, dpi(16f), 0)
        e.setTextColor(t.text1)
        e.setHintTextColor(t.text3)
        e.highlightColor = withAlpha(t.accent, 0.30f)
        e.textCursorDrawable = GradientDrawable().apply {
            setColor(t.accent)
            setSize(dpi(2f), 0)
        }
        return e
    }

    private fun buildLinkCard(): View {
        val card = card(20f)
        // The state of the link in a word, after a lens that is hollow, turning, or lit (5.2).
        pill = TextView(this)
        pill.id = R.id.pill
        lens = LensGlyphDrawable(dpi(14f), t.text3)
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(lens, null, null, null)
        pill.compoundDrawablePadding = dpi(6f)
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPaddingRelative(dpi(10f), 0, dpi(10f), 0)
        card.addView(pill, LinearLayout.LayoutParams(wrap, dpi(28f)))

        status = TextView(this)
        status.id = R.id.status
        Type.body(status)
        status.setLineSpacing(dp(4f), 1f)              // the headline's spans need more room than the body's fixed line height
        card.addView(status, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(12f) })

        // Only there while there is something to type: once the camera is connected the name is known.
        fields = LinearLayout(this)
        ssid = field("Camera Wi-Fi name")
        ssid.id = R.id.ssid
        // A network name is not a word: no spell check (its suggestion box covered the screen) and no autocorrect.
        ssid.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ssid.setText(prefs.getString("ssid", "MiVue"))
        pass = field("Wi-Fi password")
        pass.id = R.id.pass
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pass.typeface = Typeface.DEFAULT
        pass.setText(prefs.getString("pass", ""))
        fields.addView(ssid, LinearLayout.LayoutParams(0, dpi(52f), 1f))
        fields.addView(pass, LinearLayout.LayoutParams(0, dpi(52f), 1f).apply { marginStart = dpi(12f) })
        card.addView(fields, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(16f) })

        connectBtn = button(R.id.connect, "Connect")
        connectBtn.setOnClickListener { toggleLink() }
        Glass.pressable(connectBtn)
        card.addView(connectBtn, LinearLayout.LayoutParams(match, dpi(56f)).apply { topMargin = dpi(16f) })
        return card
    }

    /** Connect is the one filled button of the card; Disconnect is tonal with a thin accent edge, never red (5.2). */
    private fun styleConnect(gone: Boolean) {
        if (gone) filled(connectBtn, 56f) else tonal(connectBtn, outlined = true)
    }

    private fun buildNotice(): View {
        noticeCard = LinearLayout(this)
        noticeCard.orientation = LinearLayout.VERTICAL
        noticeCard.background = Glass.notice(this)
        Glass.elevate(noticeCard, 2f)
        val p = dpi(16f)
        noticeCard.setPadding(p, p, p, dpi(12f))
        noticeCard.visibility = View.GONE
        val row = LinearLayout(this)
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_warning)
        icon.imageTintList = ColorStateList.valueOf(t.warnText)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        noticeText = TextView(this)
        noticeText.id = R.id.notice
        Type.body(noticeText, t.warnText)
        row.addView(icon, LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
        row.addView(noticeText, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dpi(12f) })
        noticeBtn = button(R.id.notice_action, "")
        // A 40dp outline pill in a 48dp touch target, at the end under the message (5.3).
        noticeBtn.background = InsetDrawable(Glass.buttonOutline(this, t.warnStroke, 1.5f), 0, dpi(4f), 0, dpi(4f))
        Type.label(noticeBtn, t.warnText)
        noticeBtn.setPadding(dpi(18f), 0, dpi(18f), 0)
        noticeBtn.setOnClickListener { noticeAction?.invoke() }
        noticeCard.addView(row, LinearLayout.LayoutParams(match, wrap))
        noticeCard.addView(noticeBtn, LinearLayout.LayoutParams(wrap, dpi(48f)).apply {
            gravity = Gravity.END
            topMargin = dpi(8f)
        })
        return noticeCard
    }

    // ---------------------------------------------------------------- the live tile

    private fun buildTile(): View {
        tile = card(12f)
        tile.id = R.id.live_tile
        tile.visibility = View.GONE
        tile.setOnClickListener { openViewer() }
        Glass.pressable(tile)

        video = VideoFrame(this)
        video.setBackgroundColor(Color.BLACK)
        Glass.clipTo(video, Corners.VIDEO)
        live = LiveSurface(this)
        live.onStatus = { showLive(it) }
        // The last picture, blurred, while the camera is busy (spec 0: the effect goes on this view, never on the video).
        frozen = ImageView(this)
        frozen.scaleType = ImageView.ScaleType.CENTER_CROP
        frozen.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
        frozen.visibility = View.GONE
        poster = View(this)
        poster.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.accentContainer, t.tonal))
        // "Not available": the frame becomes a dashed recess in the card. It is opaque, so no old picture shows through.
        blank = View(this)
        blank.background = LayerDrawable(
            arrayOf(ColorDrawable(blend(t.fieldBase, t.glassBase, t.glassAlpha(2))), Glass.inset(this, Corners.VIDEO, dashed = true)),
        )
        blank.visibility = View.GONE
        scrim = View(this)
        scrim.setBackgroundColor(withAlpha(Color.BLACK, 0.50f))
        scrim.visibility = View.GONE

        val middle = LinearLayout(this)
        middle.orientation = LinearLayout.VERTICAL
        middle.gravity = Gravity.CENTER
        middle.setPadding(dpi(8f), dpi(6f), dpi(8f), dpi(6f))
        spinner = LensGlyphDrawable(dpi(28f), t.onAccentContainer, state = LensGlyphDrawable.State.SEARCHING)
        glyph = ImageView(this)
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        liveState = TextView(this)
        liveState.id = R.id.live_state
        liveState.gravity = Gravity.CENTER
        liveState.maxLines = 2
        liveState.ellipsize = TextUtils.TruncateAt.END
        liveReason = TextView(this)
        liveReason.id = R.id.live_reason
        Type.caption(liveReason, t.text2)
        liveReason.gravity = Gravity.CENTER
        liveReason.ellipsize = TextUtils.TruncateAt.END
        liveReason.visibility = View.GONE
        middle.addView(glyph, LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
        middle.addView(liveState, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(4f) })
        middle.addView(liveReason, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(2f) })

        // A thin light edge and the viewfinder corners over the picture (5.8, 7.2).
        chrome = View(this)
        chrome.background = LayerDrawable(arrayOf(outline(Corners.VIDEO, withAlpha(Color.WHITE, 0.30f)), BracketsDrawable.video(this)))

        liveDot = GradientDrawable()
        liveDot.shape = GradientDrawable.OVAL
        liveDot.setColor(VIDEO_RED)
        liveDot.setSize(dpi(8f), dpi(8f))
        liveChip = TextView(this)
        liveChip.id = R.id.live_chip
        Type.overline(liveChip, Color.WHITE)
        liveChip.background = Glass.videoOverlay(this)
        liveChip.gravity = Gravity.CENTER_VERTICAL
        liveChip.setPaddingRelative(dpi(6f), 0, dpi(7f), 0)
        liveChip.setCompoundDrawablesRelativeWithIntrinsicBounds(liveDot, null, null, null)
        liveChip.compoundDrawablePadding = dpi(4f)
        liveChip.visibility = View.GONE

        video.addView(live, FrameLayout.LayoutParams(match, match))
        video.addView(frozen, FrameLayout.LayoutParams(match, match))
        video.addView(poster, FrameLayout.LayoutParams(match, match))
        video.addView(blank, FrameLayout.LayoutParams(match, match))
        video.addView(scrim, FrameLayout.LayoutParams(match, match))
        video.addView(middle, FrameLayout.LayoutParams(match, match))
        video.addView(chrome, FrameLayout.LayoutParams(match, match))
        video.addView(liveChip, FrameLayout.LayoutParams(wrap, dpi(20f), Gravity.TOP or Gravity.START).apply {
            val m = dpi(6f)
            setMargins(m, m, m, m)
        })

        val title = TextView(this)
        title.id = R.id.live_title
        title.text = "Live view"
        Type.title(title, color = t.text1)
        liveReadout = TextView(this)
        liveReadout.id = R.id.live_readout
        liveReadout.text = "—"
        Type.osd(liveReadout)
        liveReadout.maxLines = 2
        liveTexts = LinearLayout(this)
        liveTexts.orientation = LinearLayout.VERTICAL
        liveTexts.addView(title, LinearLayout.LayoutParams(wrap, wrap))
        liveTexts.addView(liveReadout, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(2f) })
        liveRetry = button(R.id.live_retry, "Try again")
        textButton(liveRetry)
        liveRetry.visibility = View.GONE
        liveRetry.setOnClickListener {
            retryTapped = true
            stopLive()
            startLive()
        }
        liveOpen = button(R.id.live_open, "Open")
        liveOpen.background = InsetDrawable(Glass.buttonTonal(this), 0, dpi(4f), 0, dpi(4f))
        Type.label(liveOpen, t.text1)
        liveOpen.setPadding(dpi(20f), 0, dpi(20f), 0)
        liveOpen.setOnClickListener { openViewer() }
        Glass.pressable(liveOpen)
        liveSpacer = View(this)
        liveInfo = LinearLayout(this)
        return tile
    }

    /**
     * The tile's two shapes (5.8). Phone: the picture at most 176dp wide on the left, title, readout and button in a
     * column on the right. Wide: the picture across the whole card, the rest in a row under it. The picture is first
     * either way and is never taken out, so its surface, and the stream on it, live through the change.
     */
    private fun arrangeTile(wide: Boolean) {
        if (video.parent == null) {
            tile.addView(video)
            tile.addView(liveInfo)
        }
        liveInfo.removeAllViews()
        if (wide) {
            tile.orientation = LinearLayout.VERTICAL
            liveReason.maxLines = 2
            video.maxWidthPx = 0
            video.reservePx = 0
            video.layoutParams = LinearLayout.LayoutParams(match, wrap)
            liveInfo.orientation = LinearLayout.HORIZONTAL
            liveInfo.gravity = Gravity.CENTER_VERTICAL
            liveInfo.addView(liveTexts, LinearLayout.LayoutParams(0, wrap, 1f))
            liveInfo.addView(liveRetry, LinearLayout.LayoutParams(wrap, dpi(48f)).apply { marginStart = dpi(8f) })
            liveInfo.addView(liveOpen, LinearLayout.LayoutParams(wrap, dpi(48f)).apply { marginStart = dpi(8f) })
            liveInfo.layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(8f) }
        } else {
            tile.orientation = LinearLayout.HORIZONTAL
            liveReason.maxLines = 1               // the picture is only 99dp high here
            video.maxWidthPx = dpi(176f)
            video.reservePx = dpi(128f)              // the 12dp gap and at least 116dp for the column beside it
            video.layoutParams = LinearLayout.LayoutParams(wrap, wrap)
            liveInfo.orientation = LinearLayout.VERTICAL
            liveInfo.gravity = Gravity.START
            liveInfo.addView(liveTexts, LinearLayout.LayoutParams(match, wrap))
            liveInfo.addView(liveSpacer, LinearLayout.LayoutParams(0, 0, 1f))
            liveInfo.addView(liveRetry, LinearLayout.LayoutParams(wrap, dpi(48f)))
            liveInfo.addView(liveOpen, LinearLayout.LayoutParams(wrap, dpi(48f)))
            liveInfo.layoutParams = LinearLayout.LayoutParams(0, match, 1f).apply { marginStart = dpi(12f) }
        }
    }

    /** The live tile is there only while the camera is connected, and plays only while this screen is on show. */
    private fun updateTile() {
        val want = Link.ready
        if (want != tileShown) {
            tileShown = want
            if (want) {
                // Whatever the tile showed when it went away is stale: draw it afresh.
                livePhase = null
                showLive(live.status)
            }
            reveal(tile, want, 300L)
        }
        if (want && started) startLive() else stopLive()
    }

    private fun startLive() {
        if (liveOn) return
        liveOn = true
        liveStartedAt = SystemClock.uptimeMillis()
        live.start()
        // After the hand-over grace, show what the tile really has (see showLive).
        ui.postDelayed({ if (liveOn && !isDestroyed) showLive(live.status) }, HANDOVER_MS)
    }

    private fun stopLive() {
        if (!liveOn) return
        liveOn = false
        live.stop()
    }

    /** The full-screen viewer. The tile lets go of the camera first, so the viewer gets it at once. */
    private fun openViewer() {
        if (leaving()) return
        stopLive()
        LiveActivity.open(this)
    }

    private fun openPlayer(clip: Cam.Clip) {
        if (leaving()) return
        stopLive()
        PlayerActivity.open(this, clip, listFolder)
    }

    /** True for a second tap while the first one's screen is still opening: a double tap opens one screen, not two. */
    private fun leaving(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - openedAt < OPEN_GUARD_MS) return true
        openedAt = now
        return false
    }

    /** What LiveSurface reports, drawn over and around its picture (5.8). Main thread. */
    private fun showLive(reported: Live.Status) {
        // Back from the viewer or the player, that screen may hold the camera for a moment longer: "busy" then is
        // only the hand-over, shown as starting. A download is said at once.
        val handOver = reported.phase == Live.Phase.PAUSED_BUSY && reported.detail != LiveSurface.DOWNLOAD_BUSY &&
            SystemClock.uptimeMillis() - liveStartedAt < HANDOVER_MS
        val s = if (handOver) Live.Status(Live.Phase.STARTING, "starting") else reported
        val phase = s.phase
        // Live keeps trying by itself after "not available", briefly "starting" each time; the tile stays as it is meanwhile.
        if (phase == Live.Phase.STARTING && livePhase == Live.Phase.UNAVAILABLE && !retryTapped) return
        if (phase != Live.Phase.STARTING) retryTapped = false
        show(liveReadout, s.readout())
        val entering = phase != livePhase
        livePhase = phase
        when (phase) {
            Live.Phase.STARTING -> {
                if (entering) {
                    liveLayers(phase, frozenFrame = false)
                    glyph.setImageDrawable(spinner)
                    spinner.state = LensGlyphDrawable.State.SEARCHING
                    Type.caption(liveState, t.text1)
                    liveState.setShadowLayer(0f, 0f, 0f, 0)
                }
                show(liveState, "Starting live view…")
                liveChip(null, 0)
            }
            Live.Phase.PLAYING -> {
                if (entering) liveLayers(phase, frozenFrame = false)
                liveChip("LIVE", VIDEO_RED)
            }
            Live.Phase.PAUSED_BUSY -> {
                if (entering) {
                    // Frozen on the last picture, blurred; without one, the poster.
                    val last = live.snapshot()
                    frozen.setImageBitmap(last)
                    liveLayers(phase, frozenFrame = last != null)
                    glyph.setImageDrawable(icon(R.drawable.ic_pause, withAlpha(Color.WHITE, 0.92f)))
                    Type.osd(liveState, onVideo = true)
                }
                show(liveState, capital(s.detail))
                liveChip("PAUSED", t.laggingAmber)
            }
            Live.Phase.UNAVAILABLE -> {
                if (entering) {
                    liveLayers(phase, frozenFrame = false)
                    glyph.setImageDrawable(icon(R.drawable.ic_lens_off, t.text2))
                    Type.meta(liveState, t.text1)
                    liveState.setShadowLayer(0f, 0f, 0f, 0)
                }
                show(liveState, "Live view not available")
                show(liveReason, capital(s.detail))
                liveChip(null, 0)
            }
            Live.Phase.STOPPED -> {
                if (entering) {
                    liveLayers(phase, frozenFrame = false)
                    glyph.setImageDrawable(spinner)
                    spinner.state = LensGlyphDrawable.State.OFFLINE
                }
                liveChip(null, 0)
            }
        }
        val unavailable = phase == Live.Phase.UNAVAILABLE
        visible(liveRetry, unavailable)
        visible(liveOpen, !unavailable)
        if (entering) decorations()
    }

    /** Which of the tile's layers show in [phase]: the picture alone, a frozen frame, the poster or the dashed recess. */
    private fun liveLayers(phase: Live.Phase, frozenFrame: Boolean) {
        val playing = phase == Live.Phase.PLAYING
        val paused = phase == Live.Phase.PAUSED_BUSY
        val unavailable = phase == Live.Phase.UNAVAILABLE
        visible(frozen, paused && frozenFrame)
        visible(poster, !playing && !unavailable && !(paused && frozenFrame))
        visible(blank, unavailable)
        visible(scrim, paused)
        visible(chrome, !unavailable)
        visible(glyph, !playing)
        visible(liveState, !playing && phase != Live.Phase.STOPPED)
        visible(liveReason, unavailable)
        if (!paused) frozen.setImageDrawable(null)
    }

    /** The LIVE / PAUSED chip at the picture's top-start corner, with its dot in [dot]; [text] null hides it. */
    private fun liveChip(text: String?, dot: Int) {
        if (text == null) {
            visible(liveChip, false)
            return
        }
        show(liveChip, text)
        liveDot.setColor(dot)
        visible(liveChip, true)
    }

    private fun icon(res: Int, tint: Int): Drawable = checkNotNull(getDrawable(res)).mutate().also { it.setTint(tint) }

    /** "camera busy with another request" as a sentence on its own. */
    private fun capital(s: String) = s.replaceFirstChar { it.titlecase(Locale.US) }

    /**
     * Starts or holds the decorative loops - the pill's lens, the poster's turning lens and the blinking LIVE dot -
     * so that they only move while the screen is on show and the list is not scrolling (6).
     */
    private fun decorations() {
        val run = started && !scrolling
        if (run) lens.start() else lens.stop()
        if (run && glyph.drawable === spinner) spinner.start() else spinner.stop()
        setBlinking(run && livePhase == Live.Phase.PLAYING)
    }

    private fun setBlinking(on: Boolean) {
        val want = on && motion
        if (want == (blink != null)) return
        blink?.cancel()
        blink = null
        liveDot.alpha = 255
        if (!want) return
        // 500ms each way, by alpha alone: no halo (7, AVOID).
        blink = ValueAnimator.ofInt(255, 64).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { liveDot.alpha = it.animatedValue as Int }
            start()
        }
    }

    // ---------------------------------------------------------------- folders, caption, list

    private fun chipText(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(t.onAccentContainer, t.text1),
    )

    /** A folder chip: 40dp of glass (accent when chosen) inside a 48dp touch target (5.4). */
    private fun chip(label: String): TextView {
        val c = TextView(this)
        c.text = label
        Type.label(c)
        c.setTextColor(chipText())
        c.gravity = Gravity.CENTER
        val inset = dpi(4f)
        c.background = StateListDrawable().apply {
            setEnterFadeDuration(140)
            setExitFadeDuration(140)
            addState(intArrayOf(android.R.attr.state_selected), InsetDrawable(Glass.chip(this@MainActivity, true), 0, inset, 0, inset))
            addState(intArrayOf(), InsetDrawable(Glass.chip(this@MainActivity, false), 0, inset, 0, inset))
        }
        // After the background: an inset background replaces the view's padding with its own insets.
        c.setPadding(dpi(16f), 0, dpi(16f), 0)
        c.isClickable = true
        c.isFocusable = true
        Glass.pressable(c)
        return c
    }

    /** Chooses [chip] or not. The chosen one gets the amber lamp in front of its label, which grows in (5.4). */
    private fun mark(chip: TextView, on: Boolean, iconRes: Int) {
        if (chip.tag == on) return
        val grow = chip.tag != null && on && motion
        chip.tag = on
        chip.isSelected = on
        val lamp = if (on) ScaleDrawable(Lamp.drawable(this, 6f), Gravity.CENTER, 1f, 1f).also { it.setLevel(if (grow) 0 else 10_000) } else null
        val icon = if (iconRes != 0) icon(iconRes, t.text1).also { it.setTintList(chipText()) } else null
        chip.setCompoundDrawablesRelative(leading(lamp, icon), null, null, null)
        chip.compoundDrawablePadding = dpi(6f)
        if (grow && lamp != null) {
            ValueAnimator.ofInt(0, 10_000).apply {
                duration = 160
                interpolator = ENTER
                addUpdateListener { lamp.setLevel(it.animatedValue as Int) }
                start()
            }
        }
    }

    /** The lamp and a 16dp icon side by side as one leading drawable, with its bounds set. */
    private fun leading(lamp: Drawable?, icon: Drawable?): Drawable? {
        val lampPx = dpi(6f)
        val iconPx = dpi(16f)
        return when {
            lamp != null && icon != null -> LayerDrawable(arrayOf(lamp, icon)).apply {
                setLayerSize(0, lampPx, lampPx)
                setLayerGravity(0, Gravity.START or Gravity.CENTER_VERTICAL)
                setLayerSize(1, iconPx, iconPx)
                setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
                setBounds(0, 0, lampPx + dpi(6f) + iconPx, iconPx)
            }
            lamp != null -> lamp.apply { setBounds(0, 0, lampPx, lampPx) }
            icon != null -> icon.apply { setBounds(0, 0, iconPx, iconPx) }
            else -> null
        }
    }

    private fun buildChipRow(): View {
        chipRow = LinearLayout(this)
        chipRow.gravity = Gravity.CENTER_VERTICAL
        chipRow.clipChildren = false
        chipScroll = EndFadeScroll(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.isHorizontalFadingEdgeEnabled = true     // a folder that does not fit fades out instead of being cut
        chipScroll.setFadingEdgeLength(dpi(24f))
        chipScroll.clipToPadding = false
        folderBar = LinearLayout(this)
        folderBar.id = R.id.folder
        chipScroll.addView(folderBar, ViewGroup.LayoutParams(wrap, wrap))
        // A short dashed lane mark between the folders and the camera switch (5.4).
        val separator = View(this)
        separator.background = DashLineDrawable.chipSeparator(this)
        rearChip = chip("Rear cam")
        rearChip.id = R.id.rear
        mark(rearChip, Browser.rear, R.drawable.ic_rear_camera)
        rearChip.setOnClickListener {
            Browser.rear = !Browser.rear
            mark(rearChip, Browser.rear, R.drawable.ic_rear_camera)
            Browser.load()
        }
        refreshBtn = ImageButton(this)
        refreshBtn.id = R.id.refresh
        refreshBtn.setImageResource(R.drawable.ic_refresh)
        refreshBtn.imageTintList = ColorStateList.valueOf(t.text2)
        refreshBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        refreshBtn.background = InsetDrawable(Glass.iconButton(this), dpi(4f))
        val p = dpi(13f)                                    // a 22dp icon in the middle of the 48dp target (after the background, see chip())
        refreshBtn.setPadding(p, p, p, p)
        refreshBtn.contentDescription = "Refresh"
        refreshBtn.setOnClickListener {
            sweep()
            Browser.load()
        }
        chipRow.addView(chipScroll, LinearLayout.LayoutParams(0, dpi(48f), 1f))
        chipRow.addView(separator, LinearLayout.LayoutParams(maxOf(1, dpi(1.5f)), dpi(20f)).apply {
            marginStart = dpi(12f)
            marginEnd = dpi(12f)
        })
        chipRow.addView(rearChip, LinearLayout.LayoutParams(wrap, dpi(48f)))
        chipRow.addView(refreshBtn, LinearLayout.LayoutParams(dpi(48f), dpi(48f)).apply { marginStart = dpi(4f) })
        sheen = Sheen(dp(40f))
        chipRow.overlay.add(sheen)
        return chipRow
    }

    /** One chip per folder the camera has; the chosen one is filled in. */
    private fun showFolders() {
        if (Browser.folders != shownFolders) {
            shownFolders = Browser.folders
            folderBar.removeAllViews()
            for ((i, name) in Browser.folders.withIndex()) {
                val c = chip(name)
                c.setOnClickListener {
                    if (i != Browser.folder && i in Browser.folders.indices) {
                        Browser.folder = i
                        Browser.load()
                    }
                }
                folderBar.addView(c, LinearLayout.LayoutParams(wrap, dpi(48f)).apply { if (i > 0) marginStart = dpi(8f) })
            }
        }
        for (i in 0 until folderBar.childCount) mark(folderBar.getChildAt(i) as TextView, i == Browser.folder, 0)
        mark(rearChip, Browser.rear, R.drawable.ic_rear_camera)
    }

    /** Refresh turns while the camera's list is being read (5.4). */
    private fun setSpinning(on: Boolean) {
        val want = on && started && motion
        if (want == (spin != null)) return
        spin?.cancel()
        spin = null
        refreshBtn.rotation = 0f
        if (!want) return
        spin = ObjectAnimator.ofFloat(refreshBtn, View.ROTATION, 0f, 360f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** A single sheen across the folder chips after a tap on Refresh (5.4). */
    private fun sweep() {
        if (!motion || chipRow.width == 0) return
        sheen.setBounds(0, 0, chipRow.width, chipRow.height)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener { sheen.phase = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sheen.phase = -1f
                }
            })
            start()
        }
    }

    private fun buildCaption(): View {
        // What the list is showing, and a way to tick all of it at once.
        noteRow = LinearLayout(this)
        noteRow.gravity = Gravity.CENTER_VERTICAL
        noteRow.minimumHeight = dpi(40f)
        noteRow.clipChildren = false
        noteRow.setPadding(dpi(4f), 0, 0, 0)
        noteRow.visibility = View.GONE
        noteView = TextView(this)
        noteView.id = R.id.note
        Type.meta(noteView)
        noteView.maxLines = 2
        noteView.ellipsize = TextUtils.TruncateAt.END
        allBtn = button(R.id.select_all, "Select all")
        textButton(allBtn)
        allBtn.setOnClickListener { tickAll() }
        noteRow.addView(noteView, LinearLayout.LayoutParams(0, wrap, 1f))
        // 48dp to touch, in a 40dp row (5.5).
        noteRow.addView(allBtn, LinearLayout.LayoutParams(wrap, dpi(48f)).apply {
            topMargin = -dpi(4f)
            bottomMargin = -dpi(4f)
        })
        return noteRow
    }

    private fun buildList(): View {
        val box = FrameLayout(this)
        list = ListView(this)
        list.id = R.id.list
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        // Which rows are ticked lives in Browser. Android must not restore its own, older picture of them over
        // that when the screen is rebuilt: the list may have changed in the meantime, and the ticks would then
        // sit on the wrong clips.
        list.isSaveEnabled = false
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.clipToPadding = false
        list.isVerticalScrollBarEnabled = false
        list.isFastScrollEnabled = true                 // a handle to drag once there are screens full of clips
        list.adapter = clipAdapter
        list.setOnItemClickListener { _, view, position, _ ->
            val clip = clipAdapter.items.getOrNull(position) ?: return@setOnItemClickListener
            if (list.isItemChecked(position)) Browser.ticked.add(clip.path) else Browser.ticked.remove(clip.path)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            showSelection()
        }
        // A row that scrolled away no longer wants its thumbnail.
        list.setRecyclerListener { v -> (v as? ClipRow)?.let { Thumbs.unbind(it.thumb) } }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                val now = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (now == scrolling) return
                scrolling = now
                decorations()
            }

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {}
        })

        // An empty list: three road marks running into a dot, and why there is nothing (5.12).
        empty = LinearLayout(this)
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        val marks = View(this)
        marks.background = RoadMarks(withAlpha(t.text3, 0.5f), resources.displayMetrics.density)
        emptyTitle = TextView(this)
        Type.body(emptyTitle)
        emptyTitle.gravity = Gravity.CENTER
        emptyText = TextView(this)
        Type.caption(emptyText, t.text3)
        emptyText.gravity = Gravity.CENTER
        empty.addView(marks, LinearLayout.LayoutParams(dpi(12f), dpi(52f)))
        empty.addView(emptyTitle, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(12f) })
        empty.addView(emptyText, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(4f) })

        box.addView(list, FrameLayout.LayoutParams(match, match))
        box.addView(empty, FrameLayout.LayoutParams(match, match))
        list.emptyView = empty
        return box
    }

    /** Rows fade and rise into place the first time a folder's clips arrive: 220ms, 20ms apart (6). */
    private fun rowsIn(): LayoutAnimationController {
        val rise = AnimationSet(true)
        rise.addAnimation(AlphaAnimation(0f, 1f))
        rise.addAnimation(TranslateAnimation(0f, 0f, dp(8f), 0f))
        rise.duration = 220
        rise.interpolator = ENTER
        return LayoutAnimationController(rise, 20f / 220f)
    }

    private fun buildDownloadPanel(): LinearLayout {
        val panel = LinearLayout(this)
        panel.id = R.id.panel
        panel.orientation = LinearLayout.VERTICAL
        // The amber lane dash on top; it only looks like something to pull (5.7).
        val grabber = View(this)
        grabber.background = DashLineDrawable.amberLane(this)
        panel.addView(grabber, LinearLayout.LayoutParams(dpi(32f), dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Only there while a clip is on its way: which one, and how far through the queue.
        nameRow = LinearLayout(this)
        nameRow.gravity = Gravity.CENTER_VERTICAL
        nameRow.visibility = View.GONE
        current = TextView(this)
        current.id = R.id.panel_clip
        Type.osd(current, color = t.text1)
        current.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        current.setSingleLine()
        current.ellipsize = TextUtils.TruncateAt.MIDDLE
        counts = TextView(this)
        counts.id = R.id.panel_count
        Type.count(counts)
        nameRow.addView(current, LinearLayout.LayoutParams(0, wrap, 1f))
        nameRow.addView(counts, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dpi(12f) })
        panel.addView(nameRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(10f) })

        // Only there while there is something to say about downloads.
        progress = TextView(this)
        progress.id = R.id.progress
        Type.osd(progress)
        progress.setLineSpacing(dp(2f), 1f)
        progress.visibility = View.GONE
        panel.addView(progress, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(6f) })

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.progressDrawable = RoadProgressDrawable.create(this)
        bar.max = 1000
        bar.minHeight = dpi(8f)
        bar.maxHeight = dpi(8f)
        bar.visibility = View.GONE
        panel.addView(bar, LinearLayout.LayoutParams(match, dpi(8f)).apply { topMargin = dpi(10f) })

        val buttons = LinearLayout(this)
        downloadBtn = button(R.id.download, "Download selected")
        filled(downloadBtn, 52f)
        // In a wide screen's narrow left column "Download selected" would be cut: the label shrinks a little first.
        downloadBtn.setPadding(dpi(12f), 0, dpi(12f), 0)
        downloadBtn.setAutoSizeTextTypeUniformWithConfiguration(12, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        downloadBtn.setOnClickListener { downloadSelected() }
        Glass.pressable(downloadBtn)
        val stop = button(R.id.stop, "Stop")
        tonal(stop)
        stop.setOnClickListener {
            DownloadService.stopAll(this)
            startProblem = null
            render()
        }
        Glass.pressable(stop)
        buttons.addView(downloadBtn, LinearLayout.LayoutParams(0, dpi(52f), 2f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, dpi(52f), 1f).apply { marginStart = dpi(12f) })
        panel.addView(buttons, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(12f) })

        // On a phone the list keeps its last row clear of the panel, whatever the panel's height is just now.
        panel.addOnLayoutChangeListener { _, _, top, _, bottomEdge, _, oldTop, _, oldBottom ->
            if (bottomEdge - top != oldBottom - oldTop) ui.post { if (!isDestroyed) updateListPadding() }
        }
        return panel
    }

    /** Ticks every clip in the list, or none if all of them are ticked already. */
    private fun tickAll() {
        val clips = Browser.clips
        val all = clips.isNotEmpty() && Browser.ticked.size >= clips.size
        Browser.ticked.clear()
        if (!all) for (c in clips) Browser.ticked.add(c.path)
        list.clearChoices()
        if (!all) for (i in clips.indices) list.setItemChecked(i, true)
        clipAdapter.notifyDataSetChanged()
        showSelection()
    }

    /** How many clips are ticked, on the button that acts on them. */
    private fun showSelection() {
        val n = Browser.ticked.size
        show(allBtn, if (n > 0 && n >= Browser.clips.size) "Select none" else "Select all")
        show(downloadBtn, if (n == 0) "Download selected" else if (n == 1) "Download 1 clip" else "Download $n clips")
    }

    /**
     * A ScrollView that takes at most [share] of the height on offer and scrolls the rest; all of it at 1. It clips
     * to its own bounds: its parent lets children draw outside (for the cards' shadows), and a ScrollView without
     * padding relies on its parent for that clip, so a long notice would otherwise spill over the folder chips.
     */
    private class CappedScroll(context: Context) : ScrollView(context) {
        var share = 0.5f

        init {
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = if (share >= 1f || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                heightMeasureSpec
            } else {
                MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) * share).toInt(), MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    /** The folder chips' row: it scrolls sideways and fades only at its end, where more chips run on out of sight (5.4). */
    private class EndFadeScroll(context: Context) : HorizontalScrollView(context) {
        private val rtl: Boolean get() = layoutDirection == View.LAYOUT_DIRECTION_RTL

        init {
            // Clips to its own bounds (see CappedScroll): otherwise the folders that scroll out of it are drawn on
            // under the separator and the Rear cam chip.
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
        }

        override fun getLeftFadingEdgeStrength(): Float = if (rtl) super.getLeftFadingEdgeStrength() else 0f

        override fun getRightFadingEdgeStrength(): Float = if (rtl) 0f else super.getRightFadingEdgeStrength()
    }

    /**
     * The live picture's box, always 16:9: as wide as it is offered, less [reservePx] for what sits beside it, and
     * at most [maxWidthPx] (0: no limit).
     */
    private class VideoFrame(context: Context) : FrameLayout(context) {
        var maxWidthPx = 0
        var reservePx = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var width = MeasureSpec.getSize(widthMeasureSpec) - reservePx
            if (maxWidthPx > 0) width = minOf(width, maxWidthPx)
            width = width.coerceAtLeast(0)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY),
            )
        }
    }

    /** A soft band of white slanting across its bounds; [phase] runs from 0 (start) to 1 (end), below 0 nothing (5.4). */
    private class Sheen(private val bandPx: Float) : Drawable() {
        private val gradient = LinearGradient(
            0f, 0f, bandPx, 0f,
            intArrayOf(withAlpha(Color.WHITE, 0f), withAlpha(Color.WHITE, 0.20f), withAlpha(Color.WHITE, 0f)), null,
            Shader.TileMode.CLAMP,
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
        private val path = Path()
        private val shift = Matrix()

        var phase = -1f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            if (phase < 0f || bounds.isEmpty) return
            val lean = bounds.height() * 0.4f
            val x = bounds.left - bandPx - lean + (bounds.width() + bandPx + lean) * phase
            path.rewind()
            path.moveTo(x + lean, bounds.top.toFloat())
            path.lineTo(x + lean + bandPx, bounds.top.toFloat())
            path.lineTo(x + bandPx, bounds.bottom.toFloat())
            path.lineTo(x, bounds.bottom.toFloat())
            path.close()
            shift.setTranslate(x + lean / 2f, 0f)
            gradient.setLocalMatrix(shift)
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun getAlpha(): Int = paint.alpha
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** The empty list's picture (5.12): three short road marks, nearest first, running into a dot. */
    private class RoadMarks(private val color: Int, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private var alphaValue = 255
        private val lengths = floatArrayOf(14f, 10f, 7f)
        private val widths = floatArrayOf(3f, 2.5f, 2f)

        override fun getIntrinsicWidth(): Int = (12 * density).toInt()
        override fun getIntrinsicHeight(): Int = (52 * density).toInt()

        override fun draw(canvas: Canvas) {
            paint.color = withAlpha(color, alphaValue / 255f)
            val cx = bounds.exactCenterX()
            var y = bounds.bottom.toFloat()
            for (i in lengths.indices) {
                val half = widths[i] * density / 2f
                val top = y - lengths[i] * density
                canvas.drawRoundRect(cx - half, top, cx + half, y, half, half, paint)
                y = top - 5f * density
            }
            canvas.drawCircle(cx, y - 2f * density, 2f * density, paint)
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
     * One clip in the list (5.6): its thumbnail, when it was taken, what it is, whether it is on the phone already,
     * and its tick. Tapping the row ticks it; tapping the thumbnail opens the clip.
     */
    private inner class ClipRow(context: Context) : LinearLayout(context), Checkable {
        val thumb = ImageView(context)
        private val title = TextView(context)
        private val sub = TextView(context)
        private val savedMark = TextView(context)
        private val box = CheckBox(context)
        private val play = PlayGlyphDrawable.thumb(context)
        private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var rail = 0
        private var on = false

        /** The next setChecked comes from the list binding this row to another clip: no pop for that one. */
        private var rebound = false
        private var fade: ValueAnimator? = null
        var clip: Cam.Clip? = null
            private set
        private var photo = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setWillNotDraw(false)
            // The glass is inset 4dp at top and bottom: the gap between rows, as the list has no dividers.
            val inset = dpi(4f)
            background = InsetDrawable(
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_activated), Glass.row(context, selected = true))
                    addState(intArrayOf(), Glass.row(context, selected = false))
                },
                0, inset, 0, inset,
            )
            minimumHeight = dpi(76f) + 2 * inset
            setPaddingRelative(dpi(10f), dpi(10f) + inset, dpi(4f), dpi(10f) + inset)

            thumb.id = R.id.thumb
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            // Not loaded yet: a soft gradient with a faint lens and the viewfinder corners. The picture fades in over it.
            val lensMark = icon(R.drawable.ic_lens, withAlpha(t.onAccentContainer, 0.30f))
            thumb.background = LayerDrawable(arrayOf(Glass.thumbPlaceholder(context), lensMark, BracketsDrawable.placeholder(context))).apply {
                setLayerGravity(1, Gravity.CENTER)
                setLayerSize(1, dpi(24f), dpi(24f))
            }
            play.alpha = 0
            thumb.foreground = LayerDrawable(arrayOf(outline(Corners.THUMB, withAlpha(Color.WHITE, 0.30f)), play)).apply {
                setLayerGravity(1, Gravity.CENTER)
                setLayerSize(1, dpi(22f), dpi(22f))
            }
            Glass.clipTo(thumb, Corners.THUMB)
            thumb.setOnClickListener { clip?.let { openPlayer(it) } }

            Type.title(title)
            title.setSingleLine()
            title.ellipsize = TextUtils.TruncateAt.END
            savedMark.text = "Saved"
            Stamp.apply(savedMark)
            Type.caption(sub)
            sub.maxLines = 2
            sub.ellipsize = TextUtils.TruncateAt.END

            // The row is what gets tapped; the box only shows the state.
            box.isClickable = false
            box.isFocusable = false
            box.background = null
            box.setPadding(0, 0, 0, 0)
            box.buttonDrawable = tickBox()
            box.buttonTintList = null                     // the theme would tint the whole box in its own colour
            box.gravity = Gravity.CENTER

            val line = LinearLayout(context)
            line.gravity = Gravity.CENTER_VERTICAL
            line.clipChildren = false                      // the stamp leans out of its box a little
            line.addView(title, LayoutParams(0, wrap, 1f))
            line.addView(savedMark, LayoutParams(wrap, wrap).apply { marginStart = dpi(8f) })
            val texts = LinearLayout(context)
            texts.orientation = VERTICAL
            texts.clipChildren = false
            texts.addView(line, LayoutParams(match, wrap))
            texts.addView(sub, LayoutParams(match, wrap).apply { topMargin = dpi(2f) })
            addView(thumb, LayoutParams(dpi(88f), dpi(50f)))
            addView(texts, LayoutParams(0, wrap, 1f).apply { marginStart = dpi(12f) })
            addView(box, LayoutParams(dpi(48f), dpi(48f)))
        }

        /** Shows [c]. [railColor] marks Event (amber) and Parking (accent) clips at the start edge, 0 for none. */
        fun bind(c: Cam.Clip, isPhoto: Boolean, isSaved: Boolean, railColor: Int) {
            if (clip?.path != c.path) {
                rebound = true
                fade?.cancel()
                fade = null
                thumb.setImageDrawable(null)
                thumb.imageAlpha = 255
                play.alpha = 0
            }
            clip = c
            photo = isPhoto
            val time = c.time.ifEmpty { c.name }
            title.text = time
            val facts = ArrayList<String>()
            facts.add(if (c.size >= 0) DownloadService.mb(c.size) + " MB" else "size unknown")
            if (c.secs.isNotEmpty()) facts.add("${c.secs} s")
            if (c.res.isNotEmpty()) facts.add(c.res)
            facts.add(c.name)
            val line = facts.joinToString(" · ")
            if (sub.text.toString() != line) sub.text = Type.monoSpans(line, c.name)
            savedMark.visibility = if (isSaved) View.VISIBLE else View.GONE
            thumb.contentDescription = (if (isPhoto) "Show photo " else "Play clip ") + time
            if (rail != railColor) {
                rail = railColor
                invalidate()
            }
        }

        /** Thumbs has put a picture in (or will not): it fades in over the placeholder, with the play glyph for a video. */
        fun pictureReady(ok: Boolean, now: Boolean) {
            if (!ok) return
            val glyphAlpha = if (photo) 0 else 255
            fade?.cancel()
            fade = null
            if (now || !motion) {
                thumb.imageAlpha = 255
                play.alpha = glyphAlpha
                return
            }
            fade = ValueAnimator.ofInt(0, 255).apply {
                duration = 150
                addUpdateListener {
                    val v = it.animatedValue as Int
                    thumb.imageAlpha = v
                    play.alpha = v * glyphAlpha / 255
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (rail == 0) return
            // A 3 x 28dp bar just inside the glass at the start edge: a second cue, never the only one (5.6).
            railPaint.color = rail
            val w = dp(3f)
            val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - dp(3f) - w else dp(3f)
            val top = height / 2f - dp(14f)
            canvas.drawRoundRect(x, top, x + w, top + dp(28f), w / 2f, w / 2f, railPaint)
        }

        override fun isChecked(): Boolean = on

        override fun setChecked(checked: Boolean) {
            val pop = checked != on && !rebound && motion
            rebound = false
            on = checked
            box.isChecked = checked
            isActivated = checked
            if (pop) {
                // The tick pops in: the one place with an overshoot (6).
                box.scaleX = 0.85f
                box.scaleY = 0.85f
                box.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator()).start()
            }
        }

        override fun toggle() = setChecked(!on)
    }

    /** The tick box (5.6): a 24dp rounded square in the middle of its 48dp, outlined when off, accent with a tick when on. */
    private fun tickBox(): Drawable {
        val size = dpi(24f)
        val off = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setStroke(dpi(1.5f), t.text3)
            setSize(size, size)
        }
        val fill = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(t.accent)
            setSize(size, size)
        }
        val ticked = LayerDrawable(arrayOf(fill, icon(R.drawable.ic_check, t.onAccent))).apply {
            setLayerGravity(1, Gravity.CENTER)
            setLayerSize(1, dpi(16f), dpi(16f))
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), ticked)
            addState(intArrayOf(), off)
        }
        val inset = dpi(12f)
        return InsetDrawable(states, inset, inset, inset, inset)
    }

    /** The rail colour of the folder the list shows: amber for Event, accent for Parking, none for the others (5.6). */
    private fun railFor(folder: String): Int {
        fun named(key: String, fallback: String) = (Browser.info[key] ?: fallback).substringBefore(',').replace("/", "")
        return when (folder) {
            named("Vendor.DeviceInfo.Record.EventFolder", "Event") -> t.lamp
            named("Vendor.DeviceInfo.Record.ParkingFolder", "Parking") -> t.accent
            else -> 0
        }
    }

    private inner class ClipAdapter : BaseAdapter() {
        var items: List<Cam.Clip> = emptyList()
        var rail = 0

        /** True while Thumbs.bind runs: a picture it hands over at once was already there and needs no fade. */
        private var binding = false

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? ClipRow ?: ClipRow(this@MainActivity)
            val c = items[position]
            val photo = c.name.endsWith(".jpg", true) || c.name.endsWith(".jpeg", true)
            row.bind(c, photo, c.name in saved, rail)
            binding = true
            try {
                // A recycled row bound to another clip cancels what it was waiting for (Thumbs).
                Thumbs.bind(row.thumb, c) { ok -> if (row.clip?.path == c.path) row.pictureReady(ok, now = binding) }
            } finally {
                binding = false
            }
            return row
        }
    }

    /** Setting the same text again would still wake up layout and accessibility once a second. */
    private fun show(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Dialogs are only ever shown on a living screen, and never left behind when it goes away. */
    private fun show(builder: AlertDialog.Builder) {
        if (isFinishing || isDestroyed) return
        dialog?.dismiss()
        dialog = builder.show()
    }

    private fun otherWifi(): List<WifiInfo> =
        Link.wifiNets.filterKeys { it != Link.network }.values.mapNotNull { it.transportInfo as? WifiInfo }

    private fun describe(w: WifiInfo): String {
        val name = if (w.ssid == null || w.ssid == LinkCore.UNKNOWN_SSID) "(name hidden)" else w.ssid
        return "$name (${w.frequency} MHz, ${w.rssi} dBm)"
    }

    /** The first line is the headline (20sp, medium); what follows explains it. Same words as ever, only dressed. */
    private fun showStatus(text: String) {
        if (status.text.toString() == text) return
        val styled = SpannableString(text)
        val end = text.indexOf('\n').let { if (it < 0) text.length else it }
        styled.setSpan(RelativeSizeSpan(20f / 14f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(TypefaceSpan(medium), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(ForegroundColorSpan(t.text1), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        status.text = styled
    }

    /** The state of the link in one word, after the lens glyph that shows it at a glance (5.2, 7.1). */
    private fun showPill(st: LinkState) {
        val reconnecting = !Link.ready && (st == LinkState.LOST || (st == LinkState.SEARCHING && Link.reconnecting))
        val label: String
        val state: LensGlyphDrawable.State
        when {
            Link.ready -> { label = "Connected"; state = LensGlyphDrawable.State.CONNECTED }
            st == LinkState.CONNECTED -> { label = "Connecting…"; state = LensGlyphDrawable.State.SEARCHING }
            st == LinkState.SEARCHING -> {
                label = if (Link.reconnecting) "Reconnecting…" else "Searching…"
                state = LensGlyphDrawable.State.SEARCHING
            }
            st == LinkState.LOST -> { label = "Reconnecting…"; state = LensGlyphDrawable.State.SEARCHING }
            st == LinkState.NOT_FOUND -> { label = "Not connected"; state = LensGlyphDrawable.State.LOST }
            else -> { label = "Offline"; state = LensGlyphDrawable.State.OFFLINE }
        }
        if (label == shownPill) return
        val first = shownPill == null
        shownPill = label
        val look = t.pillLook(state, reconnecting)
        pill.text = label
        Type.overline(pill, look.text)
        pill.background = Glass.statePill(this, state, reconnecting)
        lens.setColors(look.glyph)
        lens.state = state
        if (!first && motion) {
            pill.alpha = 0.4f
            pill.animate().alpha(1f).setDuration(150).start()
        }
        decorations()
    }

    private fun render() {
        if (isDestroyed) return
        fitColumnA()
        val st = Link.state
        val sb = StringBuilder()
        sb.append(
            when (st) {
                LinkState.IDLE -> "Not connected to the camera."
                LinkState.SEARCHING ->
                    if (Link.reconnecting) "Camera Wi-Fi dropped — looking for it again…"
                    else "Looking for the camera's Wi-Fi… approve the system prompt if one appears."
                LinkState.CONNECTED ->
                    "Camera: ${Link.cameraSsid ?: "connected"} at ${Link.cameraIp ?: "… (waiting for its address)"}"
                LinkState.LOST -> "Camera Wi-Fi dropped — reconnecting…"
                LinkState.NOT_FOUND -> "Camera Wi-Fi not connected."
            }
        )
        Link.lastError?.let { sb.append("\n").append(it) }
        val model = Browser.info["Vendor.DeviceInfo.Model"]
        if (st == LinkState.CONNECTED && model != null) {
            sb.append("\n").append(model).append(", firmware ")
                .append(Browser.info["Vendor.DeviceInfo.Version.Firmware"] ?: "?")
        }
        val others = otherWifi()
        sb.append("\nOther Wi-Fi on this phone: ")
        sb.append(if (others.isEmpty()) "none" else others.joinToString { describe(it) })
        showStatus(sb.toString())
        showPill(st)

        // The name and password are only on the screen while they are needed; they fold away once connected.
        val gone = Link.gone
        show(connectBtn, if (gone) "Connect" else "Disconnect")
        if (gone != shownGone) {
            val first = shownGone == null
            shownGone = gone
            styleConnect(gone)
            if (first) visible(fields, gone) else reveal(fields, gone, 220L)
        }

        // The camera's full name, once Android has told us, replaces whatever start of it was typed.
        val learned = Link.cameraSsid
        if (st == LinkState.CONNECTED && learned != null && learned != shownSsid) {
            shownSsid = learned
            if (ssid.text.toString() != learned) ssid.setText(learned)
        }

        showFolders()

        if (Browser.clips !== shownClips) {
            val folder = Browser.folderName
            // Rows rise in when a folder's clips first arrive, not when the same list is shown again.
            val arriving = Browser.clips.isNotEmpty() && (clipAdapter.items.isEmpty() || folder != listFolder)
            shownClips = Browser.clips
            listFolder = folder
            saved = DownloadService.savedNames(this)
            clipAdapter.items = Browser.clips
            clipAdapter.rail = railFor(folder)
            clipAdapter.notifyDataSetChanged()
            list.clearChoices()
            for ((i, c) in Browser.clips.withIndex()) {
                if (c.path in Browser.ticked) list.setItemChecked(i, true)
            }
            if (arriving && motion) list.layoutAnimation = rowsIn()
        }
        // What the list is showing - or, while it is empty, why.
        if (Browser.clips.isEmpty()) {
            show(emptyTitle, if (Link.ready) "No clips here yet" else "No camera connected")
            show(emptyText, if (Link.ready) Browser.note else "Connect to the camera to see its clips.")
            visible(noteRow, false)
        } else {
            if (Browser.note != shownNote) {
                shownNote = Browser.note
                // The counts in monospace, like a camera's own display; the words stay as they are.
                noteView.text = Type.monoDigits(Browser.note)
            }
            visible(noteRow, true)
        }
        setSpinning(Link.ready && Browser.note.startsWith("Loading "))
        showSelection()
        renderNotice()
        renderProgress()
        updateTile()
    }

    /** Link changed (main thread): a short buzz when the camera comes up or cannot be found, and the live tile follows. */
    private fun linkChanged() {
        val ready = Link.ready
        val st = Link.state
        if (ready && !wasReady) {
            root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else if (st == LinkState.NOT_FOUND && lastState != LinkState.NOT_FOUND) {
            root.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
        wasReady = ready
        lastState = st
        updateTile()
    }

    // ---------------------------------------------------------------- things the user should know about

    private fun explain(missing: List<Pair<String, String>>): String {
        val parts = ArrayList<String>()
        for ((permission, _) in missing) {
            when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION -> parts.add(
                    "Without precise Location, Android hides Wi-Fi names: the camera must be picked from the " +
                        "system prompt every time and cannot reconnect by itself."
                )
                Manifest.permission.NEARBY_WIFI_DEVICES -> parts.add("Without Nearby devices, Android may refuse the link to the camera.")
                Manifest.permission.POST_NOTIFICATIONS -> parts.add("Without Notifications, downloads still run but show no progress notification.")
            }
        }
        return parts.joinToString(" ")
    }

    /**
     * The permissions to put in one request. Precise location is never asked for on its own: Android 12
     * and later want the approximate one in the same request, and some releases ignore the request otherwise.
     */
    private fun toRequest(missing: List<Pair<String, String>>): Array<String> {
        val out = ArrayList<String>()
        for ((permission, _) in missing) {
            out.add(permission)
            if (permission == Manifest.permission.ACCESS_FINE_LOCATION) out.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return out.toTypedArray()
    }

    /** The one thing most worth telling the user right now, or null. */
    private fun notice(): Notice? {
        startProblem?.let {
            return Notice("Android did not start the download service ($it). Bring DashLink to the front and tap Download again.", null, null)
        }
        val fg = DownloadService.foregroundError
        if (fg != null && DownloadService.working) {
            return Notice("Android did not let the downloads run as a foreground service ($fg). Keep DashLink on screen until they finish.", null, null)
        }
        if (Power.restricted(this)) {
            return Notice(
                "Battery use for DashLink is set to Restricted: Android stops downloads as soon as the app leaves the screen.",
                "Settings"
            ) { Power.openAppSettings(this) }
        }
        val paused = Jobs.paused
        if (paused != null) {
            return if (Link.ready) Notice(paused, "Resume") { resumeDownloads() } else Notice(paused, null, null)
        }
        val waiting = Jobs.all.count { Jobs.isWanted(it) }
        if (Jobs.interrupted && waiting > 0) {
            val what = "$waiting download(s) were cut off when Android stopped DashLink. They continue when the camera is connected."
            return if (Power.exempt(this)) Notice(what, null, null)
            else Notice("$what To prevent this, let DashLink run in the background.", "Allow…") { Power.askForExemption(this) }
        }
        val missing = Report.missingPermissions(this)
        if (missing.isNotEmpty()) {
            return Notice(explain(missing), "Allow") {
                // One question at a time: a second request while Android's dialog is up is answered
                // at once with nothing, which would look like "don't ask again".
                if (!asking) {
                    asking = true
                    askedAt = SystemClock.elapsedRealtime()
                    requestPermissions(toRequest(missing), ASK_AGAIN)
                }
            }
        }
        if (waiting > 0 && !Power.exempt(this)) {
            return Notice(
                "Battery optimisation is on for DashLink: Android (Samsung in particular) may stop long downloads while the screen is off.",
                "Allow…"
            ) { Power.askForExemption(this) }
        }
        if (waiting > 0 && !Link.ready && !DownloadService.working) {
            return Notice("$waiting download(s) are waiting. They continue when the camera is connected.", null, null)
        }
        return null
    }

    private fun renderNotice() {
        val n = notice()
        noticeAction = n?.action
        if (n == null) {
            visible(noticeCard, false)
            return
        }
        show(noticeText, n.text)
        if (n.button != null) show(noticeBtn, n.button)
        visible(noticeBtn, n.button != null)
        visible(noticeCard, true)
    }

    // ---------------------------------------------------------------- link

    private fun toggleLink() {
        if (!Link.gone) {
            Link.disconnect()
            return
        }
        val name = ssid.text.toString().trim()
        val key = pass.text.toString()
        if (name.isEmpty() || key.length < 8) {
            toast("Enter the camera's Wi-Fi name (or the start of it) and its password.")
            return
        }
        // Checked here so the user gets a sentence instead of Android's exception text (Link survives either way).
        if (key.length > 63 || key.any { it.code < 32 || it.code > 126 } || name.toByteArray(Charsets.UTF_8).size > 32) {
            toast("That cannot be a Wi-Fi name and password: the name has at most 32 characters, the password 8 to 63 plain ones.")
            return
        }
        prefs.edit().putString("ssid", name).putString("pass", key).apply()
        val wifiOn = try {
            wifi.isWifiEnabled
        } catch (e: Exception) {
            true
        }
        if (!wifiOn) {
            show(
                AlertDialog.Builder(this)
                    .setMessage("Wi-Fi is switched off on this phone. Switch it on, then tap Connect again.")
                    .setPositiveButton("Wi-Fi settings") { _, _ -> openWifiSettings() }
                    .setNegativeButton("Cancel", null)
            )
            return
        }
        val go = { Link.connect(this, name, key, name == prefs.getString("exactSsid", null)) }
        // The same check Link uses to decide whether a dropped camera link is taken back by itself.
        if (!Link.oneWifiOnly(this)) {
            go()
        } else {
            show(
                AlertDialog.Builder(this)
                    .setMessage(
                        "This phone reports that it cannot hold two Wi-Fi connections at once. " +
                            "Connecting to the camera will drop the current Wi-Fi, including wireless Android Auto.\n\n" +
                            "If Android gives the Wi-Fi back to Android Auto or another network, DashLink does not take " +
                            "it again by itself: tap Connect when you want the camera, and downloads go on from where they stopped."
                    )
                    .setPositiveButton("Connect anyway") { _, _ -> go() }
                    .setNegativeButton("Cancel", null)
            )
        }
    }

    private fun openWifiSettings() {
        for (action in listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: Exception) {
                // try the next screen
            }
        }
    }

    // ---------------------------------------------------------------- downloads

    private fun downloadSelected() {
        val chosen = Browser.clips.filter { it.path in Browser.ticked }
        if (chosen.isEmpty()) {
            toast("Tick the clips you want first.")
            return
        }
        startProblem = DownloadService.enqueue(this, chosen)
        Browser.ticked.clear()
        list.clearChoices()
        clipAdapter.notifyDataSetChanged()
        showSelection()
        if (startProblem == null) offerBatteryExemption()
        render()
    }

    private fun resumeDownloads() {
        startProblem = DownloadService.start(this)
        render()
    }

    /** Asked once, the first time a download is started: after that the notice line keeps offering it. */
    private fun offerBatteryExemption() {
        if (Power.exempt(this) || prefs.getBoolean("batteryAsked", false)) return
        prefs.edit().putBoolean("batteryAsked", true).apply()
        show(
            AlertDialog.Builder(this)
                .setTitle("Keep downloading with the screen off?")
                .setMessage(
                    "To save battery, Android - Samsung phones in particular - may stop DashLink while the screen " +
                        "is off, and the downloads with it. You can allow DashLink to keep running; Android will ask you to confirm."
                )
                .setPositiveButton("Allow…") { _, _ -> Power.askForExemption(this) }
                .setNegativeButton("Not now", null)
        )
    }

    /** The download panel (5.7): the clip on its way and its counts, a progress line, the road bar. */
    private fun renderProgress() {
        val jobs = Jobs.all
        val active = jobs.firstOrNull {
            it.state == Jobs.DOWNLOADING || it.state == Jobs.WAITING || it.state == Jobs.SAVING
        }
        val sb = StringBuilder()
        if (active != null) {
            val rate = String.format(Locale.US, "%.1f", active.bytesPerSec / 1048576.0)
            sb.append(DownloadService.mb(active.done)).append("/").append(DownloadService.mb(active.total))
                .append(" MB · ").append(rate).append(" MB/s · ").append(active.state).append("\n")
        }
        val queued = jobs.count { it.state == Jobs.QUEUED && !it.cancel }
        val done = jobs.count { it.state == Jobs.SAVED }
        val failed = jobs.count { it.state == Jobs.FAILED }
        if (jobs.isNotEmpty()) {
            sb.append(queued).append(" queued, ").append(done).append(" saved")
            if (failed > 0) {
                sb.append(", ").append(failed).append(" failed")
                jobs.lastOrNull { it.state == Jobs.FAILED }?.lastError?.let { sb.append(" (").append(it).append(")") }
            }
        }
        val text = sb.toString()
        show(progress, text)
        visible(progress, text.isNotEmpty())

        if (active != null) {
            show(current, active.clip.name)
            show(counts, "${done + failed + 1} / ${jobs.count { it.state != Jobs.CANCELLED }}")
            if (nameRow.visibility != View.VISIBLE && motion) {
                nameRow.alpha = 0f
                nameRow.animate().alpha(1f).setDuration(200).start()
            }
        }
        visible(nameRow, active != null)

        // How far the current clip is, as a bar. Hidden when nothing is on its way or its size is not known.
        val total = active?.total ?: 0L
        if (active != null && total > 0) {
            val permille = (active.done.coerceIn(0L, total) * 1000 / total).toInt()
            if (bar.progress != permille) bar.setProgress(permille, motion)
            visible(bar, true)
        } else {
            visible(bar, false)
        }
    }

    // ---------------------------------------------------------------- once a second

    private fun tick() {
        ticks++
        renderProgress()
        renderNotice()

        val names = DownloadService.savedNames(this)
        if (names.size != saved.size) {
            saved = names
            clipAdapter.notifyDataSetChanged()
        }

        val active = Jobs.all.any { it.state == Jobs.DOWNLOADING || it.state == Jobs.WAITING || it.state == Jobs.SAVING }

        // Clips left in the queue - by a previous run that Android stopped, or by a camera that went out of
        // reach - go on by themselves once the camera is there again. Only while this screen is in front
        // (Android does not allow starting the service from the background), and not in a tight loop.
        val now = SystemClock.elapsedRealtime()
        if (resumed && Link.ready && !DownloadService.working && Jobs.hasWork() &&
            (Jobs.paused == null || Jobs.pausedByLink) && now - autoStartAt > 15_000
        ) {
            autoStartAt = now
            startProblem = DownloadService.start(this)
        }

        // Same keep-alive the camera's own app sends; skipped while a download is already talking to it.
        if (ticks % 10 == 0 && !active && Link.ready) Browser.keepAlive()
    }

    // ---------------------------------------------------------------- diagnostics

    private fun diagnostics() {
        val folder = Browser.folderName
        val rear = Browser.rear
        val secret = pass.text.toString()
        val app = applicationContext
        toast("Collecting…")
        // Its own thread: the report must not wait behind a long file listing.
        val worker = Thread({
            val text = try {
                Report.build(app, folder, rear, secret)
            } catch (e: Throwable) {
                "DashLink diagnostics could not be collected: ${Cam.describe(e)}"
            }
            ui.post { share(text) }
        }, "dashlink-report")
        worker.isDaemon = true
        worker.start()
    }

    private fun share(text: String) {
        if (isFinishing || isDestroyed) return
        try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, "Share diagnostics"))
        } catch (e: Exception) {
            toast("Could not open the share sheet (${e.javaClass.simpleName}).")
        }
    }

    private companion object {
        const val ASK_FIRST = 1
        const val ASK_AGAIN = 2

        /** A second tap on a thumbnail or the tile within this long opens nothing more (see leaving()). */
        const val OPEN_GUARD_MS = 800L

        /** How long "camera busy" right after the tile starts counts as the hand-over from a closing screen. */
        const val HANDOVER_MS = 2_500L

        /** The red of a dot on video (1b); Tokens.liveDot follows light and dark, a picture is always dark. */
        val VIDEO_RED = 0xFFFF5A52.toInt()

        /** Motion (6): things come in on a long ease-out and leave on a short ease-in. */
        val ENTER = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EXIT = PathInterpolator(0.3f, 0f, 1f, 1f)
    }
}
