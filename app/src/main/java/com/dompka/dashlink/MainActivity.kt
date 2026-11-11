package com.dompka.dashlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 */
class MainActivity : Activity() {

    private class Notice(val text: String, val button: String?, val action: (() -> Unit)?)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var wifi: WifiManager

    // The skeleton: two columns that sit under each other on a phone and side by side on a wide screen.
    private lateinit var root: LinearLayout
    private lateinit var colA: LinearLayout
    private lateinit var colB: LinearLayout
    private lateinit var topScroll: CappedScroll
    private lateinit var bottom: LinearLayout
    private var wide: Boolean? = null

    private lateinit var pill: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var status: TextView
    private lateinit var fields: LinearLayout
    private lateinit var noticeRow: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeBtn: Button
    private lateinit var ssid: EditText
    private lateinit var pass: EditText
    private lateinit var connectBtn: Button
    private lateinit var folderBar: LinearLayout
    private lateinit var rearChip: TextView
    private lateinit var list: ListView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var noteView: TextView
    private lateinit var noteRow: LinearLayout
    private lateinit var allBtn: Button
    private lateinit var progress: TextView
    private lateinit var bar: ProgressBar
    private lateinit var downloadBtn: Button
    private val clipAdapter = ClipAdapter()

    private var shownClips: List<Cam.Clip>? = null
    private var shownFolders: List<String>? = null
    private var shownSsid: String? = null
    private var shownGone: Boolean? = null
    private var shownPill: String? = null
    private var saved: Set<String> = emptySet()
    private var dialog: AlertDialog? = null
    private var noticeAction: (() -> Unit)? = null
    private var startProblem: String? = null
    private var resumed = false
    private var askedAt = 0L
    private var asking = false
    private var autoStartAt = 0L
    private var ticks = 0

    /** One object for the whole life of this screen, so it can be taken out of Browser again by identity. */
    private val refresh: () -> Unit = { render() }

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

    override fun onDestroy() {
        // Only if it is still ours: a newer screen may already have taken over.
        if (Browser.onChange === refresh) Browser.onChange = null
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

    // ---------------------------------------------------------------- UI

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val medium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tint(color: Int): ColorStateList = ColorStateList.valueOf(getColor(color))

    /**
     * Two columns. A: heading, the camera link, whatever the user should know. B: folders and the file list.
     * On a phone they sit under each other with the download panel pinned below; on a wide screen (an
     * unfolded phone, landscape) they sit side by side and the download panel moves under column A.
     * Column A scrolls rather than push the list or the Download and Stop buttons off a short screen.
     */
    private fun buildUi() {
        // fitsSystemWindows replaces a view's own padding, so the padding lives one level further in.
        val frame = FrameLayout(this)
        frame.fitsSystemWindows = true
        root = LinearLayout(this)
        root.setPadding(dp(10), dp(6), dp(10), dp(10))
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
        top.setPadding(dp(2), 0, dp(2), dp(4))          // room for the cards' shadows inside the scrolling area
        topScroll.addView(top, ViewGroup.LayoutParams(match, wrap))
        colA.addView(topScroll)
        top.addView(buildHeading(), LinearLayout.LayoutParams(match, wrap))
        top.addView(buildLinkCard(), LinearLayout.LayoutParams(match, wrap))
        // Below the link card, so that a long notice on a small screen scrolls instead of pushing Connect out of sight.
        top.addView(buildNotice(), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        colB = LinearLayout(this)
        colB.orientation = LinearLayout.VERTICAL
        colB.addView(buildFolderBar(), LinearLayout.LayoutParams(match, wrap))
        // What the list is showing, and a way to tick all of it at once.
        noteRow = LinearLayout(this)
        noteRow.gravity = Gravity.CENTER_VERTICAL
        noteRow.visibility = View.GONE
        noteView = TextView(this)
        noteView.id = R.id.note
        noteView.textSize = 12f
        noteView.setTextColor(getColor(R.color.text_dim))
        noteView.setPadding(dp(8), 0, dp(8), 0)
        allBtn = Button(this)
        allBtn.id = R.id.select_all
        style(allBtn, FLAT)
        allBtn.textSize = 13f
        allBtn.setOnClickListener { tickAll() }
        noteRow.addView(noteView, LinearLayout.LayoutParams(0, wrap, 1f))
        noteRow.addView(allBtn, LinearLayout.LayoutParams(wrap, dp(32)))
        colB.addView(noteRow, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(4) })
        colB.addView(buildList(), LinearLayout.LayoutParams(match, 0, 1f))

        bottom = buildDownloadPanel()

        setContentView(frame)
        arrange()
    }

    /** Puts the columns where the width of the screen wants them. Cheap when nothing changed. */
    private fun arrange() {
        val nowWide = resources.configuration.screenWidthDp >= 600
        if (nowWide == wide) return
        wide = nowWide
        (bottom.parent as? ViewGroup)?.removeView(bottom)
        root.removeAllViews()
        if (nowWide) {
            root.orientation = LinearLayout.HORIZONTAL
            topScroll.share = 1f
            topScroll.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            colA.addView(bottom, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
            root.addView(colA, LinearLayout.LayoutParams(0, match, 5f))
            root.addView(colB, LinearLayout.LayoutParams(0, match, 8f).apply { marginStart = dp(12) })
        } else {
            root.orientation = LinearLayout.VERTICAL
            topScroll.share = 0.5f
            topScroll.layoutParams = LinearLayout.LayoutParams(match, wrap)
            root.addView(colA, LinearLayout.LayoutParams(match, wrap))
            root.addView(colB, LinearLayout.LayoutParams(match, 0, 1f))
            root.addView(bottom, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation, folding and unfolding, resizing: the same views are put where the new shape wants them.
        arrange()
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = getDrawable(R.drawable.bg_card)
        c.elevation = dp(1).toFloat()
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        return c
    }

    private fun style(b: Button, kind: Int) {
        b.isAllCaps = false
        b.stateListAnimator = null
        b.elevation = 0f
        b.typeface = medium
        b.textSize = if (kind == FLAT) 14f else 15f
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(if (kind == FLAT) 12 else 20), 0, dp(if (kind == FLAT) 12 else 20), 0)
        when (kind) {
            FILLED -> {
                b.background = getDrawable(R.drawable.btn_filled)
                b.setTextColor(getColorStateList(R.color.filled_text))
            }
            TONAL -> {
                b.background = getDrawable(R.drawable.btn_tonal)
                b.setTextColor(getColor(R.color.on_accent_soft))
            }
            else -> {
                b.background = getDrawable(R.drawable.btn_flat)
                b.setTextColor(getColor(R.color.accent))
            }
        }
    }

    private fun buildHeading(): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(4), 0, dp(8))
        val title = TextView(this)
        title.text = "DashLink"
        title.textSize = 24f
        title.typeface = medium
        title.setTextColor(getColor(R.color.text))
        // Always within reach, whatever state the rest is in: this is what gets tapped when something is wrong.
        val diag = Button(this)
        diag.id = R.id.diag
        diag.text = "Diagnostics"
        style(diag, FLAT)
        diag.setOnClickListener { diagnostics() }
        row.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(diag, LinearLayout.LayoutParams(wrap, dp(40)))
        return row
    }

    private fun field(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setSingleLine()
        e.textSize = 15f
        e.background = getDrawable(R.drawable.bg_field)
        e.setPadding(dp(14), 0, dp(14), 0)
        e.setTextColor(getColor(R.color.text))
        e.setHintTextColor(getColor(R.color.text_dim))
        return e
    }

    private fun buildLinkCard(): View {
        val card = card()
        // The state of the link in a word, and a turning wheel while it is being looked for.
        val state = LinearLayout(this)
        state.gravity = Gravity.CENTER_VERTICAL
        pill = TextView(this)
        pill.textSize = 12f
        pill.typeface = medium
        pill.setPadding(dp(10), dp(3), dp(10), dp(3))
        pill.background = getDrawable(R.drawable.bg_pill)
        spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
        spinner.indeterminateTintList = tint(R.color.accent)
        spinner.visibility = View.GONE
        state.addView(pill, LinearLayout.LayoutParams(wrap, wrap))
        state.addView(spinner, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) })
        card.addView(state, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })

        status = TextView(this)
        status.id = R.id.status
        status.textSize = 13f
        status.setTextColor(getColor(R.color.text_dim))
        status.setLineSpacing(dp(2).toFloat(), 1f)
        card.addView(status, LinearLayout.LayoutParams(match, wrap))

        // Only there while there is something to type: once the camera is connected the name is known.
        fields = LinearLayout(this)
        ssid = field("Camera Wi-Fi name")
        ssid.id = R.id.ssid
        ssid.setText(prefs.getString("ssid", "MiVue"))
        pass = field("Wi-Fi password")
        pass.id = R.id.pass
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pass.typeface = Typeface.DEFAULT
        pass.setText(prefs.getString("pass", ""))
        fields.addView(ssid, LinearLayout.LayoutParams(0, dp(46), 1f))
        fields.addView(pass, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        card.addView(fields, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })

        connectBtn = Button(this)
        connectBtn.id = R.id.connect
        connectBtn.setOnClickListener { toggleLink() }
        style(connectBtn, FILLED)
        card.addView(connectBtn, LinearLayout.LayoutParams(match, dp(46)).apply { topMargin = dp(10) })
        return card
    }

    private fun buildNotice(): View {
        noticeRow = LinearLayout(this)
        noticeRow.gravity = Gravity.CENTER_VERTICAL
        noticeRow.background = getDrawable(R.drawable.bg_notice)
        noticeRow.setPadding(dp(14), dp(10), dp(6), dp(10))
        noticeRow.visibility = View.GONE
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_info)
        icon.imageTintList = tint(R.color.warn)
        noticeText = TextView(this)
        noticeText.id = R.id.notice
        noticeText.textSize = 13f
        noticeText.setTextColor(getColor(R.color.warn))
        noticeBtn = Button(this)
        noticeBtn.id = R.id.notice_action
        style(noticeBtn, FLAT)
        noticeBtn.setTextColor(getColor(R.color.warn))
        noticeBtn.setOnClickListener { noticeAction?.invoke() }
        noticeRow.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
        noticeRow.addView(noticeText, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(10) })
        noticeRow.addView(noticeBtn, LinearLayout.LayoutParams(wrap, dp(40)).apply { marginStart = dp(4) })
        return noticeRow
    }

    private fun chip(label: String): TextView {
        val c = TextView(this)
        c.text = label
        c.textSize = 14f
        c.typeface = medium
        c.gravity = Gravity.CENTER
        c.setPadding(dp(12), 0, dp(12), 0)
        c.background = getDrawable(R.drawable.chip)
        c.setTextColor(getColorStateList(R.color.chip_text))
        c.isClickable = true
        c.isFocusable = true
        return c
    }

    private fun buildFolderBar(): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(2), dp(10), 0, dp(8))
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isHorizontalFadingEdgeEnabled = true     // a folder that does not fit fades out instead of being cut
        scroll.setFadingEdgeLength(dp(24))
        folderBar = LinearLayout(this)
        scroll.addView(folderBar, ViewGroup.LayoutParams(wrap, wrap))
        rearChip = chip("Rear cam")
        rearChip.id = R.id.rear
        rearChip.isSelected = Browser.rear
        rearChip.setOnClickListener {
            Browser.rear = !Browser.rear
            rearChip.isSelected = Browser.rear
            Browser.load()
        }
        val refresh = ImageButton(this)
        refresh.id = R.id.refresh
        refresh.setImageResource(R.drawable.ic_refresh)
        refresh.imageTintList = tint(R.color.text_dim)
        refresh.background = getDrawable(R.drawable.btn_flat)
        refresh.contentDescription = "Refresh"
        refresh.setOnClickListener { Browser.load() }
        row.addView(scroll, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(rearChip, LinearLayout.LayoutParams(wrap, dp(36)).apply { marginStart = dp(8) })
        row.addView(refresh, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) })
        return row
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
                folderBar.addView(c, LinearLayout.LayoutParams(wrap, dp(36)).apply { if (i > 0) marginStart = dp(6) })
            }
        }
        for (i in 0 until folderBar.childCount) {
            val on = i == Browser.folder
            val c = folderBar.getChildAt(i)
            if (c.isSelected != on) c.isSelected = on
        }
        if (rearChip.isSelected != Browser.rear) rearChip.isSelected = Browser.rear
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
        list.divider = ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(6)
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.clipToPadding = false
        list.setPadding(dp(2), 0, dp(2), dp(4))
        list.isVerticalScrollBarEnabled = false
        list.isFastScrollEnabled = true                 // a handle to drag once there are screens full of clips
        list.adapter = clipAdapter
        list.setOnItemClickListener { _, _, position, _ ->
            val clip = clipAdapter.items.getOrNull(position) ?: return@setOnItemClickListener
            if (list.isItemChecked(position)) Browser.ticked.add(clip.path) else Browser.ticked.remove(clip.path)
            showSelection()
        }

        val empty = LinearLayout(this)
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(24), dp(12), dp(24), dp(12))
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_video)
        icon.imageTintList = tint(R.color.outline)
        emptyTitle = TextView(this)
        emptyTitle.textSize = 16f
        emptyTitle.typeface = medium
        emptyTitle.gravity = Gravity.CENTER
        emptyTitle.setTextColor(getColor(R.color.text))
        emptyText = TextView(this)
        emptyText.textSize = 13f
        emptyText.gravity = Gravity.CENTER
        emptyText.setTextColor(getColor(R.color.text_dim))
        empty.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
        empty.addView(emptyTitle, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(8) })
        empty.addView(emptyText, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(2) })

        box.addView(list, FrameLayout.LayoutParams(match, match))
        box.addView(empty, FrameLayout.LayoutParams(match, match))
        list.emptyView = empty
        return box
    }

    private fun buildDownloadPanel(): LinearLayout {
        val card = card()
        card.elevation = dp(3).toFloat()

        // Only there while there is something to say about downloads.
        progress = TextView(this)
        progress.id = R.id.progress
        progress.textSize = 13f
        progress.setTextColor(getColor(R.color.text_dim))
        progress.setLineSpacing(dp(2).toFloat(), 1f)
        progress.visibility = View.GONE
        card.addView(progress, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(10) })

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.progressDrawable = getDrawable(R.drawable.progress)
        bar.max = 1000
        bar.minHeight = dp(6)
        bar.maxHeight = dp(6)
        bar.visibility = View.GONE
        card.addView(bar, LinearLayout.LayoutParams(match, dp(6)).apply { bottomMargin = dp(12) })

        val buttons = LinearLayout(this)
        downloadBtn = Button(this)
        downloadBtn.id = R.id.download
        downloadBtn.text = "Download selected"
        downloadBtn.maxLines = 1
        downloadBtn.ellipsize = TextUtils.TruncateAt.END
        style(downloadBtn, FILLED)
        downloadBtn.setOnClickListener { downloadSelected() }
        val stop = Button(this)
        stop.id = R.id.stop
        stop.text = "Stop"
        style(stop, TONAL)
        stop.setOnClickListener {
            DownloadService.stopAll(this)
            startProblem = null
            render()
        }
        buttons.addView(downloadBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(wrap, dp(46)).apply { marginStart = dp(8) })
        card.addView(buttons, LinearLayout.LayoutParams(match, wrap))
        return card
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

    /** A ScrollView that takes at most [share] of the height on offer and scrolls the rest; all of it at 1. */
    private class CappedScroll(context: Context) : ScrollView(context) {
        var share = 0.5f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = if (share >= 1f || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                heightMeasureSpec
            } else {
                MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) * share).toInt(), MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    /** One clip in the list: what it is, when it was taken, whether it is on the phone already, and its tick. */
    private inner class ClipRow(context: Context) : LinearLayout(context), Checkable {
        val icon = ImageView(context)
        val title = TextView(context)
        val sub = TextView(context)
        val savedMark = TextView(context)
        private val box = CheckBox(context)
        private var on = false

        init {
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.row)
            setPadding(dp(12), dp(10), dp(6), dp(10))
            icon.background = context.getDrawable(R.drawable.bg_round)
            icon.backgroundTintList = tint(R.color.surface_alt)
            icon.imageTintList = tint(R.color.accent)
            icon.setPadding(dp(9), dp(9), dp(9), dp(9))
            title.textSize = 15f
            title.typeface = medium
            title.setTextColor(getColor(R.color.text))
            title.setSingleLine()
            title.ellipsize = TextUtils.TruncateAt.END
            sub.textSize = 12f
            sub.setTextColor(getColor(R.color.text_dim))
            sub.setSingleLine()
            sub.ellipsize = TextUtils.TruncateAt.END
            savedMark.text = "Saved"
            savedMark.textSize = 11f
            savedMark.typeface = medium
            savedMark.setTextColor(getColor(R.color.ok))
            savedMark.background = context.getDrawable(R.drawable.bg_pill)
            savedMark.backgroundTintList = tint(R.color.ok_soft)
            savedMark.setPadding(dp(8), dp(2), dp(8), dp(2))
            // The row is what gets tapped; the box only shows the state.
            box.isClickable = false
            box.isFocusable = false
            val texts = LinearLayout(context)
            texts.orientation = VERTICAL
            texts.addView(title, LayoutParams(match, wrap))
            texts.addView(sub, LayoutParams(match, wrap).apply { topMargin = dp(2) })
            addView(icon, LayoutParams(dp(40), dp(40)))
            addView(texts, LayoutParams(0, wrap, 1f).apply { marginStart = dp(12) })
            addView(savedMark, LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
            addView(box, LayoutParams(wrap, wrap).apply { marginStart = dp(2) })
        }

        override fun isChecked(): Boolean = on

        override fun setChecked(checked: Boolean) {
            on = checked
            box.isChecked = checked
            isActivated = checked
        }

        override fun toggle() = setChecked(!on)
    }

    private inner class ClipAdapter : BaseAdapter() {
        var items: List<Cam.Clip> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? ClipRow ?: ClipRow(this@MainActivity)
            val c = items[position]
            val photo = c.name.endsWith(".jpg", true) || c.name.endsWith(".jpeg", true)
            row.icon.setImageResource(if (photo) R.drawable.ic_photo else R.drawable.ic_video)
            row.title.text = c.time.ifEmpty { c.name }
            val facts = ArrayList<String>()
            facts.add(if (c.size >= 0) DownloadService.mb(c.size) + " MB" else "size unknown")
            if (c.secs.isNotEmpty()) facts.add("${c.secs} s")
            if (c.res.isNotEmpty()) facts.add(c.res)
            facts.add(c.name)
            row.sub.text = facts.joinToString("  ·  ")
            row.savedMark.visibility = if (c.name in saved) View.VISIBLE else View.GONE
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

    /** The first line is the headline; what follows explains it. Same words as ever, only dressed. */
    private fun showStatus(text: String) {
        if (status.text.toString() == text) return
        val styled = SpannableString(text)
        val end = text.indexOf('\n').let { if (it < 0) text.length else it }
        styled.setSpan(ForegroundColorSpan(getColor(R.color.text)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(AbsoluteSizeSpan(16, true), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(TypefaceSpan("sans-serif-medium"), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        status.text = styled
    }

    /** The state of the link in one word, next to the app's name. */
    private fun showPill(st: LinkState) {
        val busy = st == LinkState.SEARCHING || st == LinkState.LOST || (st == LinkState.CONNECTED && !Link.ready)
        val label: String
        val fg: Int
        val bg: Int
        when {
            Link.ready -> { label = "Connected"; fg = R.color.ok; bg = R.color.ok_soft }
            st == LinkState.CONNECTED -> { label = "Connecting…"; fg = R.color.on_accent_soft; bg = R.color.accent_soft }
            st == LinkState.SEARCHING -> { label = if (Link.reconnecting) "Reconnecting…" else "Searching…"; fg = R.color.on_accent_soft; bg = R.color.accent_soft }
            st == LinkState.LOST -> { label = "Reconnecting…"; fg = R.color.on_accent_soft; bg = R.color.accent_soft }
            st == LinkState.NOT_FOUND -> { label = "Not connected"; fg = R.color.warn; bg = R.color.warn_soft }
            else -> { label = "Offline"; fg = R.color.idle; bg = R.color.idle_soft }
        }
        if (label != shownPill) {
            shownPill = label
            pill.text = label
            pill.setTextColor(getColor(fg))
            pill.backgroundTintList = tint(bg)
        }
        val want = if (busy) View.VISIBLE else View.GONE
        if (spinner.visibility != want) spinner.visibility = want
    }

    private fun render() {
        if (isDestroyed) return
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

        // The name and password are only on the screen while they are needed.
        val gone = Link.gone
        show(connectBtn, if (gone) "Connect" else "Disconnect")
        if (gone != shownGone) {
            shownGone = gone
            fields.visibility = if (gone) View.VISIBLE else View.GONE
            style(connectBtn, if (gone) FILLED else TONAL)
        }

        // The camera's full name, once Android has told us, replaces whatever start of it was typed.
        val learned = Link.cameraSsid
        if (st == LinkState.CONNECTED && learned != null && learned != shownSsid) {
            shownSsid = learned
            if (ssid.text.toString() != learned) ssid.setText(learned)
        }

        showFolders()

        if (Browser.clips !== shownClips) {
            shownClips = Browser.clips
            saved = DownloadService.savedNames(this)
            clipAdapter.items = Browser.clips
            clipAdapter.notifyDataSetChanged()
            list.clearChoices()
            for ((i, c) in Browser.clips.withIndex()) {
                if (c.path in Browser.ticked) list.setItemChecked(i, true)
            }
        }
        // What the list is showing - or, while it is empty, why.
        if (Browser.clips.isEmpty()) {
            show(emptyTitle, if (Link.ready) "No clips here" else "No camera connected")
            show(emptyText, if (Link.ready) Browser.note else "Connect to the camera to see its clips.")
            if (noteRow.visibility != View.GONE) noteRow.visibility = View.GONE
        } else {
            show(noteView, Browser.note)
            if (noteRow.visibility != View.VISIBLE) noteRow.visibility = View.VISIBLE
        }
        showSelection()
        renderNotice()
        renderProgress()
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
            if (noticeRow.visibility != View.GONE) noticeRow.visibility = View.GONE
            return
        }
        show(noticeText, n.text)
        if (n.button != null) show(noticeBtn, n.button)
        val wantBtn = if (n.button != null) View.VISIBLE else View.GONE
        if (noticeBtn.visibility != wantBtn) noticeBtn.visibility = wantBtn
        if (noticeRow.visibility != View.VISIBLE) noticeRow.visibility = View.VISIBLE
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
        val twoLinks = try {
            wifi.isStaConcurrencyForLocalOnlyConnectionsSupported
        } catch (e: Exception) {
            true
        }
        if (twoLinks) {
            go()
        } else {
            show(
                AlertDialog.Builder(this)
                    .setMessage(
                        "This phone reports that it cannot hold two Wi-Fi connections at once. " +
                            "Connecting to the camera will drop the current Wi-Fi, including wireless Android Auto."
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

    private fun renderProgress() {
        val jobs = Jobs.all
        val active = jobs.firstOrNull {
            it.state == Jobs.DOWNLOADING || it.state == Jobs.WAITING || it.state == Jobs.SAVING
        }
        val sb = StringBuilder()
        var nameEnd = 0
        if (active != null) {
            val rate = String.format(Locale.US, "%.1f", active.bytesPerSec / 1048576.0)
            sb.append(active.clip.name)
            nameEnd = sb.length
            sb.append("\n").append(DownloadService.mb(active.done)).append("/").append(DownloadService.mb(active.total))
                .append(" MB  ·  ").append(rate).append(" MB/s  ·  ").append(active.state).append("\n")
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
        if (progress.text.toString() != text) {
            val styled = SpannableString(text)
            if (nameEnd > 0) {
                styled.setSpan(ForegroundColorSpan(getColor(R.color.text)), 0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(TypefaceSpan("sans-serif-medium"), 0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(AbsoluteSizeSpan(14, true), 0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            progress.text = styled
        }
        val wantText = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (progress.visibility != wantText) progress.visibility = wantText

        // How far the current clip is, as a bar. Hidden when nothing is on its way or its size is not known.
        val total = active?.total ?: 0L
        if (active != null && total > 0) {
            val permille = (active.done.coerceIn(0L, total) * 1000 / total).toInt()
            if (bar.progress != permille) bar.progress = permille
            if (bar.visibility != View.VISIBLE) bar.visibility = View.VISIBLE
        } else if (bar.visibility != View.GONE) {
            bar.visibility = View.GONE
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
        val t = Thread({
            val text = try {
                Report.build(app, folder, rear, secret)
            } catch (e: Throwable) {
                "DashLink diagnostics could not be collected: ${Cam.describe(e)}"
            }
            ui.post { share(text) }
        }, "dashlink-report")
        t.isDaemon = true
        t.start()
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

        // Kinds of button.
        const val FILLED = 0
        const val TONAL = 1
        const val FLAT = 2
    }
}
