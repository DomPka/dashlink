package com.dompka.dashlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // One request to the camera at a time (its own app also queues them); downloads run in the service.
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var wifi: WifiManager

    private lateinit var status: TextView
    private lateinit var ssid: EditText
    private lateinit var pass: EditText
    private lateinit var connectBtn: Button
    private lateinit var folderSpin: Spinner
    private lateinit var folderAdapter: ArrayAdapter<String>
    private lateinit var rearBox: CheckBox
    private lateinit var list: ListView
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var progress: TextView

    private var folders = listOf("Normal", "Event", "Parking", "Photo")
    private var clips: List<Cam.Clip> = emptyList()
    private var info: Map<String, String> = emptyMap()
    private var listNote = ""
    @Volatile private var loadGen = 0
    private var linked = false
    private var ticks = 0
    private var shownSaved = -1

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
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS
            ), 1
        )
        Link.init(this)
        Link.onChange = { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        Link.onChange = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val pad = (12 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.fitsSystemWindows = true

        status = TextView(this)
        status.textSize = 14f
        root.addView(status, LinearLayout.LayoutParams(match, wrap))

        val row1 = LinearLayout(this)
        ssid = EditText(this)
        ssid.hint = "Camera Wi-Fi name"
        ssid.setSingleLine()
        ssid.setText(prefs.getString("ssid", "MiVue"))
        pass = EditText(this)
        pass.hint = "Wi-Fi password"
        pass.setSingleLine()
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pass.setText(prefs.getString("pass", ""))
        connectBtn = Button(this)
        connectBtn.setOnClickListener { toggleLink() }
        row1.addView(ssid, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(pass, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(connectBtn, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(row1, LinearLayout.LayoutParams(match, wrap))

        val row2 = LinearLayout(this)
        folderSpin = Spinner(this)
        folderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ArrayList(folders))
        folderSpin.adapter = folderAdapter
        folderSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadList()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rearBox = CheckBox(this)
        rearBox.text = "Rear cam"
        rearBox.setOnCheckedChangeListener { _, _ -> loadList() }
        val refresh = Button(this)
        refresh.text = "Refresh"
        refresh.setOnClickListener { loadList() }
        row2.addView(folderSpin, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(rearBox, LinearLayout.LayoutParams(wrap, wrap))
        row2.addView(refresh, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(row2, LinearLayout.LayoutParams(match, wrap))

        list = ListView(this)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, ArrayList<String>())
        list.adapter = listAdapter
        root.addView(list, LinearLayout.LayoutParams(match, 0, 1f))

        progress = TextView(this)
        progress.textSize = 13f
        root.addView(progress, LinearLayout.LayoutParams(match, wrap))

        val row3 = LinearLayout(this)
        val dl = Button(this)
        dl.text = "Download selected"
        dl.setOnClickListener { downloadSelected() }
        val stop = Button(this)
        stop.text = "Stop"
        stop.setOnClickListener { DownloadService.stopAll() }
        val diag = Button(this)
        diag.text = "Diagnostics"
        diag.setOnClickListener { diagnostics() }
        row3.addView(dl, LinearLayout.LayoutParams(0, wrap, 1f))
        row3.addView(stop, LinearLayout.LayoutParams(wrap, wrap))
        row3.addView(diag, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(row3, LinearLayout.LayoutParams(match, wrap))

        setContentView(root)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun otherWifi(): List<WifiInfo> =
        Link.wifiNets.filterKeys { it != Link.network }.values.mapNotNull { it.transportInfo as? WifiInfo }

    private fun describe(w: WifiInfo): String = "${w.ssid} (${w.frequency} MHz, ${w.rssi} dBm)"

    private fun render() {
        val st = Link.state
        val sb = StringBuilder()
        sb.append(
            when (st) {
                Link.State.IDLE -> "Not connected to the camera."
                Link.State.SEARCHING -> "Looking for the camera's Wi-Fi… approve the system prompt if one appears."
                Link.State.CONNECTED -> "Camera: ${Link.cameraSsid ?: "connected"} at ${Link.cameraIp ?: "…"}"
                Link.State.LOST -> "Camera Wi-Fi dropped — reconnecting…"
                Link.State.NOT_FOUND -> "Camera Wi-Fi not found. Is Wi-Fi switched on in the camera's menu?"
            }
        )
        Link.lastError?.let { sb.append("\n").append(it) }
        val model = info["Vendor.DeviceInfo.Model"]
        if (st == Link.State.CONNECTED && model != null) {
            sb.append("\n").append(model).append(", firmware ").append(info["Vendor.DeviceInfo.Version.Firmware"] ?: "?")
        }
        val others = otherWifi()
        sb.append("\nOther Wi-Fi on this phone: ")
        sb.append(if (others.isEmpty()) "none" else others.joinToString { describe(it) })
        status.text = sb.toString()
        connectBtn.text = if (st == Link.State.IDLE || st == Link.State.NOT_FOUND) "Connect" else "Disconnect"

        val nowLinked = st == Link.State.CONNECTED && Link.cameraIp != null
        if (nowLinked && !linked) onLinked()
        linked = nowLinked
    }

    // ---------------------------------------------------------------- link

    private fun toggleLink() {
        val st = Link.state
        if (st != Link.State.IDLE && st != Link.State.NOT_FOUND) {
            Link.disconnect()
            return
        }
        val name = ssid.text.toString().trim()
        val key = pass.text.toString()
        if (name.isEmpty() || key.length < 8) {
            toast("Enter the camera's Wi-Fi name (or the start of it) and its password.")
            return
        }
        prefs.edit().putString("ssid", name).putString("pass", key).apply()
        val go = { Link.connect(this, name, key, name == prefs.getString("exactSsid", null)) }
        if (wifi.isStaConcurrencyForLocalOnlyConnectionsSupported) {
            go()
        } else {
            AlertDialog.Builder(this)
                .setMessage(
                    "This phone reports that it cannot hold two Wi-Fi connections at once. " +
                        "Connecting to the camera will drop the current Wi-Fi, including wireless Android Auto."
                )
                .setPositiveButton("Connect anyway") { _, _ -> go() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onLinked() {
        // Remember the camera's full Wi-Fi name: an exact name lets Android skip its prompt next time.
        Link.cameraSsid?.let { full ->
            prefs.edit().putString("exactSsid", full).putString("ssid", full).apply()
            ssid.setText(full)
        }
        io.execute {
            var fresh: Map<String, String> = emptyMap()
            try {
                fresh = Cam.parseProps(Cam.get(Cam.INFO))
            } catch (e: Exception) {
                // The list below reports the problem if the camera is not answering.
            }
            ui.post {
                info = fresh
                val names = listOf(
                    fresh["Vendor.DeviceInfo.Record.VideoFolder"] ?: "Normal",
                    fresh["Vendor.DeviceInfo.Record.EventFolder"] ?: "Event",
                    fresh["Vendor.DeviceInfo.Record.ParkingFolder"] ?: "Parking",
                    fresh["Vendor.DeviceInfo.Record.PhotoFolder"] ?: "Photo"
                ).map { it.substringBefore(',').replace("/", "") }.filter { it.isNotEmpty() }.distinct()
                if (names.isNotEmpty() && names != folders) {
                    folders = names
                    folderAdapter.clear()
                    folderAdapter.addAll(names)
                }
                render()
                loadList()
            }
        }
    }

    // ---------------------------------------------------------------- file list

    private fun loadList() {
        val gen = ++loadGen
        if (Link.state != Link.State.CONNECTED || Link.cameraIp == null) return
        val folder = folders.getOrElse(folderSpin.selectedItemPosition) { folders[0] }
        val rear = rearBox.isChecked
        listNote = "Loading $folder…"
        io.execute {
            val all = ArrayList<Cam.Clip>()
            val seen = HashSet<String>()
            var error: String? = null
            try {
                // The camera's own app asks for 16 at a time. Stop on an empty page or one with nothing new.
                while (gen == loadGen && all.size < 5000) {
                    val page = Cam.list(folder, rear, all.size, 16)
                    val added = page.filter { seen.add(it.path) }
                    if (added.isEmpty()) break
                    all.addAll(added)
                    val n = all.size
                    ui.post { if (gen == loadGen) listNote = "Loading $folder… $n files" }
                }
            } catch (e: Exception) {
                error = e.javaClass.simpleName + ": " + e.message
            }
            all.sortByDescending { it.time }
            val failed = error
            ui.post {
                if (gen == loadGen) {
                    clips = all
                    list.clearChoices()
                    relabel()
                    listNote = if (failed != null) "Could not list $folder ($failed)" else "$folder: ${all.size} files"
                }
            }
        }
    }

    private fun relabel() {
        val saved = DownloadService.savedNames(this)
        listAdapter.clear()
        listAdapter.addAll(clips.map { c ->
            val size = if (c.size >= 0) DownloadService.mb(c.size) + " MB" else "?"
            val length = if (c.secs.isNotEmpty()) "${c.secs} s  " else ""
            val mark = if (c.name in saved) "   ✓ saved" else ""
            "${c.time}   ${c.name}\n${c.res}  $length$size$mark"
        })
        shownSaved = saved.size
    }

    private fun downloadSelected() {
        val checked = list.checkedItemPositions
        val chosen = clips.filterIndexed { i, _ -> checked != null && checked.get(i) }
        if (chosen.isEmpty()) {
            toast("Tick the clips you want first.")
            return
        }
        DownloadService.enqueue(this, chosen)
        list.clearChoices()
        listAdapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- once a second

    private fun tick() {
        ticks++
        val jobs = DownloadService.jobs
        val active = jobs.firstOrNull {
            it.state == DownloadService.DOWNLOADING || it.state == DownloadService.WAITING || it.state == DownloadService.SAVING
        }
        val sb = StringBuilder(listNote)
        if (active != null) {
            val rate = String.format(Locale.US, "%.1f", active.bytesPerSec / 1048576.0)
            sb.append("\n").append(active.clip.name).append("  ")
                .append(DownloadService.mb(active.done)).append("/").append(DownloadService.mb(active.clip.size))
                .append(" MB  ").append(rate).append(" MB/s  — ").append(active.state)
        }
        val queued = jobs.count { it.state == DownloadService.QUEUED && !it.cancel }
        val saved = jobs.count { it.state == DownloadService.SAVED }
        val failed = jobs.count { it.state == DownloadService.FAILED }
        if (jobs.isNotEmpty()) {
            sb.append("\n").append(queued).append(" queued, ").append(saved).append(" saved")
            if (failed > 0) sb.append(", ").append(failed).append(" failed")
        }
        progress.text = sb.toString()

        if (DownloadService.savedNames(this).size != shownSaved && clips.isNotEmpty()) relabel()

        // Same keep-alive the camera's own app sends; skipped while a download is already talking to it.
        if (ticks % 10 == 0 && active == null && Link.state == Link.State.CONNECTED && Link.cameraIp != null) {
            io.execute {
                try {
                    Cam.get(Cam.STATUS, 5000)
                } catch (e: Exception) {
                    // A dropped link is reported through Link's own callbacks.
                }
            }
        }
    }

    // ---------------------------------------------------------------- diagnostics

    private fun diagnostics() {
        val folder = folders.getOrElse(folderSpin.selectedItemPosition) { folders[0] }
        val rear = rearBox.isChecked
        toast("Collecting…")
        io.execute {
            val sb = StringBuilder("DashLink 0.1 diagnostics\n")
            sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            sb.append("Two Wi-Fi links at once (local-only): ${wifi.isStaConcurrencyForLocalOnlyConnectionsSupported}\n")
            sb.append("Wi-Fi + hotspot at once: ${wifi.isStaApConcurrencySupported}\n")
            sb.append("Bands: 2.4=${wifi.is24GHzBandSupported} 5=${wifi.is5GHzBandSupported} 6=${wifi.is6GHzBandSupported}\n")
            sb.append("Link: ${Link.state}, camera ${Link.cameraSsid} at ${Link.cameraIp}\n")
            for ((net, caps) in Link.wifiNets) {
                val w = caps.transportInfo as? WifiInfo
                val role = if (net == Link.network) "camera" else "other"
                sb.append("  Wi-Fi [$role] ${w?.ssid} ${w?.frequency} MHz ${w?.rssi} dBm ${w?.linkSpeed} Mbps\n")
            }
            if (Link.state == Link.State.CONNECTED) {
                for ((title, path) in listOf(
                    "Device info" to Cam.INFO,
                    "Status" to Cam.STATUS,
                    "List $folder" to Cam.listUrl(folder, rear, 0, 16)
                )) {
                    sb.append("\n== $title: $path\n")
                    try {
                        val body = Cam.get(path, 10000)
                        // Never put the camera's Wi-Fi password in a report.
                        val safe = body.lineSequence()
                            .filterNot { it.contains("crypto", true) || it.contains("passw", true) }
                            .joinToString("\n")
                        sb.append(safe.take(3000)).append("\n")
                    } catch (e: Exception) {
                        sb.append("FAILED ${e.javaClass.simpleName}: ${e.message}\n")
                    }
                }
            }
            sb.append("\n== Downloads\n")
            for (j in DownloadService.jobs) {
                sb.append("${j.clip.name}: ${j.state} ${j.done}/${j.clip.size}")
                j.lastError?.let { sb.append("  last error: ").append(it) }
                sb.append("\n")
            }
            val text = sb.toString()
            ui.post {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, "Share diagnostics"))
            }
        }
    }
}
