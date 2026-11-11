package com.dompka.dashlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
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

    private lateinit var status: TextView
    private lateinit var noticeRow: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeBtn: Button
    private lateinit var ssid: EditText
    private lateinit var pass: EditText
    private lateinit var connectBtn: Button
    private lateinit var folderSpin: Spinner
    private lateinit var folderAdapter: ArrayAdapter<String>
    private lateinit var rearBox: CheckBox
    private lateinit var list: ListView
    private lateinit var progress: TextView
    private val clipAdapter = ClipAdapter()

    private var shownClips: List<Cam.Clip>? = null
    private var shownFolders: List<String>? = null
    private var shownSsid: String? = null
    private var saved: Set<String> = emptySet()
    private var dialog: AlertDialog? = null
    private var noticeAction: (() -> Unit)? = null
    private var startProblem: String? = null
    private var resumed = false
    private var askedAt = 0L
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
        // Both arrays are empty when the request was interrupted, so they are not looked at:
        // what is granted is read back from the system.
        if (requestCode == ASK_AGAIN && Report.missingPermissions(this).isNotEmpty() &&
            SystemClock.elapsedRealtime() - askedAt < 500
        ) {
            // Android answered without showing its dialog ("don't ask again"): the switch is in Settings now.
            Power.openAppSettings(this)
        }
        render()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val pad = (12 * resources.displayMetrics.density).toInt()

        // fitsSystemWindows replaces a view's own padding, so the padding lives one level further in.
        val frame = FrameLayout(this)
        frame.fitsSystemWindows = true
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        frame.addView(root, FrameLayout.LayoutParams(match, match))

        // Top to bottom: [status, the two rows of controls, notice] [file list] [progress] [buttons].
        // The progress line and the buttons are pinned to the bottom. What is above shares the rest: the
        // controls scroll rather than take more than half of it, so on a short screen (landscape, a folded
        // phone's cover screen, split screen) neither the list nor the Download and Stop buttons disappear.
        val middle = LinearLayout(this)
        middle.orientation = LinearLayout.VERTICAL
        root.addView(middle, LinearLayout.LayoutParams(match, 0, 1f))
        val topScroll = CappedScroll(this, 0.5f)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        topScroll.addView(top, ViewGroup.LayoutParams(match, wrap))
        middle.addView(topScroll, LinearLayout.LayoutParams(match, wrap))

        status = TextView(this)
        status.id = R.id.status
        status.textSize = 14f
        top.addView(status, LinearLayout.LayoutParams(match, wrap))

        noticeRow = LinearLayout(this)
        noticeRow.visibility = View.GONE
        noticeText = TextView(this)
        noticeText.id = R.id.notice
        noticeText.textSize = 13f
        noticeBtn = Button(this)
        noticeBtn.id = R.id.notice_action
        noticeBtn.setOnClickListener { noticeAction?.invoke() }
        noticeRow.addView(noticeText, LinearLayout.LayoutParams(0, wrap, 1f))
        noticeRow.addView(noticeBtn, LinearLayout.LayoutParams(wrap, wrap))

        val row1 = LinearLayout(this)
        ssid = EditText(this)
        ssid.id = R.id.ssid
        ssid.hint = "Camera Wi-Fi name"
        ssid.setSingleLine()
        ssid.setText(prefs.getString("ssid", "MiVue"))
        pass = EditText(this)
        pass.id = R.id.pass
        pass.hint = "Wi-Fi password"
        pass.setSingleLine()
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pass.setText(prefs.getString("pass", ""))
        connectBtn = Button(this)
        connectBtn.id = R.id.connect
        connectBtn.setOnClickListener { toggleLink() }
        row1.addView(ssid, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(pass, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(connectBtn, LinearLayout.LayoutParams(wrap, wrap))
        top.addView(row1, LinearLayout.LayoutParams(match, wrap))

        val row2 = LinearLayout(this)
        folderSpin = Spinner(this)
        folderSpin.id = R.id.folder
        folderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ArrayList<String>())
        folderSpin.adapter = folderAdapter
        folderSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Also called when the screen is (re)built; only a real change loads the list again.
                if (position != Browser.folder && position in Browser.folders.indices) {
                    Browser.folder = position
                    Browser.load()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rearBox = CheckBox(this)
        rearBox.id = R.id.rear
        rearBox.text = "Rear cam"
        rearBox.isChecked = Browser.rear
        rearBox.setOnCheckedChangeListener { _, checked ->
            if (checked != Browser.rear) {
                Browser.rear = checked
                Browser.load()
            }
        }
        val refreshBtn = Button(this)
        refreshBtn.id = R.id.refresh
        refreshBtn.text = "Refresh"
        refreshBtn.setOnClickListener { Browser.load() }
        row2.addView(folderSpin, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(rearBox, LinearLayout.LayoutParams(wrap, wrap))
        row2.addView(refreshBtn, LinearLayout.LayoutParams(wrap, wrap))
        top.addView(row2, LinearLayout.LayoutParams(match, wrap))
        // Below the controls, so that a long notice on a small screen scrolls instead of pushing Connect out of sight.
        top.addView(noticeRow, LinearLayout.LayoutParams(match, wrap))

        list = ListView(this)
        list.id = R.id.list
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = clipAdapter
        list.setOnItemClickListener { _, _, position, _ ->
            val clip = clipAdapter.items.getOrNull(position) ?: return@setOnItemClickListener
            if (list.isItemChecked(position)) Browser.ticked.add(clip.path) else Browser.ticked.remove(clip.path)
        }
        middle.addView(list, LinearLayout.LayoutParams(match, 0, 1f))

        progress = TextView(this)
        progress.id = R.id.progress
        progress.textSize = 13f
        root.addView(progress, LinearLayout.LayoutParams(match, wrap))

        val row3 = LinearLayout(this)
        val dl = Button(this)
        dl.id = R.id.download
        dl.text = "Download selected"
        dl.setOnClickListener { downloadSelected() }
        val stop = Button(this)
        stop.id = R.id.stop
        stop.text = "Stop"
        stop.setOnClickListener {
            DownloadService.stopAll(this)
            startProblem = null
            render()
        }
        val diag = Button(this)
        diag.id = R.id.diag
        diag.text = "Diagnostics"
        diag.setOnClickListener { diagnostics() }
        row3.addView(dl, LinearLayout.LayoutParams(0, wrap, 1f))
        row3.addView(stop, LinearLayout.LayoutParams(wrap, wrap))
        row3.addView(diag, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(row3, LinearLayout.LayoutParams(match, wrap))

        setContentView(frame)
        fitTitleBar()
    }

    /** The title bar is a luxury on a short screen: it costs as much height as one row of the file list. */
    private fun fitTitleBar() {
        val bar = actionBar ?: return
        if (resources.configuration.screenHeightDp < 480) bar.hide() else bar.show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation, folding and unfolding, resizing: the same views simply lay themselves out again.
        fitTitleBar()
    }

    /** A ScrollView that takes at most [share] of the height on offer and scrolls the rest. */
    private class CappedScroll(context: Context, private val share: Float) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                heightMeasureSpec
            } else {
                MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) * share).toInt(), MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    private inner class ClipAdapter : BaseAdapter() {
        var items: List<Cam.Clip> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView
                ?: layoutInflater.inflate(android.R.layout.simple_list_item_multiple_choice, parent, false)) as TextView
            val c = items[position]
            val size = if (c.size >= 0) DownloadService.mb(c.size) + " MB" else "?"
            val length = if (c.secs.isNotEmpty()) "${c.secs} s  " else ""
            val mark = if (c.name in saved) "   ✓ saved" else ""
            row.text = "${c.time}   ${c.name}\n${c.res}  $length$size$mark"
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
        show(status, sb.toString())
        show(connectBtn, if (Link.gone) "Connect" else "Disconnect")

        // The camera's full name, once Android has told us, replaces whatever start of it was typed.
        val learned = Link.cameraSsid
        if (st == LinkState.CONNECTED && learned != null && learned != shownSsid) {
            shownSsid = learned
            if (ssid.text.toString() != learned) ssid.setText(learned)
        }

        if (Browser.folders != shownFolders) {
            shownFolders = Browser.folders
            folderAdapter.clear()
            folderAdapter.addAll(Browser.folders)
        }
        if (folderSpin.selectedItemPosition != Browser.folder && Browser.folder in Browser.folders.indices) {
            folderSpin.setSelection(Browser.folder, false)
        }
        if (rearBox.isChecked != Browser.rear) rearBox.isChecked = Browser.rear

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
                askedAt = SystemClock.elapsedRealtime()
                requestPermissions(toRequest(missing), ASK_AGAIN)
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
        val sb = StringBuilder(Browser.note)
        if (active != null) {
            val rate = String.format(Locale.US, "%.1f", active.bytesPerSec / 1048576.0)
            sb.append("\n").append(active.clip.name).append("  ")
                .append(DownloadService.mb(active.done)).append("/").append(DownloadService.mb(active.total))
                .append(" MB  ").append(rate).append(" MB/s  — ").append(active.state)
        }
        val queued = jobs.count { it.state == Jobs.QUEUED && !it.cancel }
        val done = jobs.count { it.state == Jobs.SAVED }
        val failed = jobs.count { it.state == Jobs.FAILED }
        if (jobs.isNotEmpty()) {
            sb.append("\n").append(queued).append(" queued, ").append(done).append(" saved")
            if (failed > 0) {
                sb.append(", ").append(failed).append(" failed")
                jobs.lastOrNull { it.state == Jobs.FAILED }?.lastError?.let { sb.append(" (").append(it).append(")") }
            }
        }
        show(progress, sb.toString())
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
    }
}
