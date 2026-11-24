package com.dompka.dashlink

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.sin

/** Shared plumbing for the emulator tests: waiting, tapping, system dialogs, and looking into shared storage. */
internal object Kit {
    const val PKG = "com.dompka.dashlink"

    val instr: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = instr.targetContext
    val device: UiDevice by lazy { UiDevice.getInstance(instr) }
    val wifi: WifiManager get() = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun shell(cmd: String): String = device.executeShellCommand(cmd)

    /**
     * The app's globals that tests replace - the camera's HTTP opener and download time-out, the live view's sockets
     * and host, the Wi-Fi radio and the link's time limits - as they were when this was made. [restore] puts them back.
     */
    class Globals {
        private val opener = Cam.opener
        private val downloadTimeout = Cam.downloadTimeoutMs
        private val sockets = Live.sockets
        private val host = Live.host
        private var radio: LinkCore.Radio? = null
        private var searchTimeout = 0
        private var promptTimeout = 0

        init {
            onMain {
                radio = Link.radio
                searchTimeout = Link.core.timeoutMs
                promptTimeout = Link.core.promptTimeoutMs
            }
        }

        fun restore() {
            Cam.opener = opener
            Cam.downloadTimeoutMs = downloadTimeout
            Live.sockets = sockets
            Live.host = host
            onMain {
                radio?.let { Link.radio = it }
                Link.core.timeoutMs = searchTimeout
                Link.core.promptTimeoutMs = promptTimeout
            }
        }
    }

    /**
     * Live-view sockets that never get anywhere: what a test uses unless it serves a stream itself (FakeRtsp). The
     * main screen runs its live tile whenever the camera is connected, and over the pretend Wi-Fi link there is no
     * camera to stream from; this makes that answer quick and the same every time.
     */
    val noLiveStream: LiveSockets = object : LiveSockets {
        override fun tcp(host: String, port: Int, timeoutMs: Int): Socket = throw IOException("no live stream in this test")
        override fun udp(port: Int): DatagramSocket = throw IOException("no live stream in this test")
    }

    /** Link's own check, kept before tests replace it (see prepare). */
    val shippedOneWifiOnly: (Context) -> Boolean = Link.oneWifiOnly

    /** Every test starts from a known place: screen on, unlocked, no leftovers from the test before. */
    fun prepare(batteryExempt: Boolean = true) {
        Configurator.getInstance().waitForIdleTimeout = 1000
        device.wakeUp()
        shell("wm dismiss-keyguard")
        shell("settings put secure show_ime_with_hard_keyboard 0")
        // Android explains swipe-to-exit the first time an app goes full screen (the live viewer); that box would
        // sit over the viewer and take the first Back. A user sees it once; tests start on a fresh emulator.
        shell("settings put secure immersive_mode_confirmations confirmed")
        // The emulators report one Wi-Fi link only. Tests run as on a phone with two (a dropped link is taken back
        // by itself) unless they say otherwise; the one-Wi-Fi behaviour has tests of its own.
        Link.oneWifiOnly = { false }
        // A test whose process died before its tearDown could leave the display resized, dark or turned:
        // every test starts from the plain phone screen (these are no-ops when nothing was changed).
        shell("wm size reset")
        shell("wm density reset")
        shell("cmd uimode night no")
        shell("settings put system font_scale 1.0")
        device.setOrientationNatural()
        device.unfreezeRotation()
        // Android's Wi-Fi prompt lives in the Settings app, and a box it left open in an earlier test
        // would sit in front of the next one.
        shell("am force-stop com.android.settings")
        shell("svc wifi enable")
        until("Wi-Fi to be on", 30_000) { wifi.isWifiEnabled }
        shell("dumpsys deviceidle whitelist ${if (batteryExempt) "+" else "-"}$PKG")
        until("battery exemption to be ${if (batteryExempt) "on" else "off"}") { Power.exempt(ctx) == batteryExempt }
    }

    fun grantAll() {
        for ((permission, _) in Report.wantedPermissions()) instr.uiAutomation.grantRuntimePermission(PKG, permission)
    }

    fun onMain(block: () -> Unit) = instr.runOnMainSync(block)

    // ---------------------------------------------------------------- waiting

    /** What the app thinks and what is on the screen, for the message of a failed wait. */
    fun state(): String {
        val sb = StringBuilder()
        sb.append("link=${Link.state} ip=${Link.cameraIp} asked=${Link.core.requests} error=${Link.lastError}\n")
        sb.append("service alive=${DownloadService.alive} working=${DownloadService.working} locks=${DownloadService.locksHeld} ")
        sb.append("fgError=${DownloadService.foregroundError} paused=${Jobs.paused}\n")
        for (j in Jobs.all) sb.append("  job ${j.clip.name}: ${j.state} ${j.done}/${j.total} cancel=${j.cancel} dest=${j.dest} error=${j.lastError}\n")
        val live = Live.log().takeLast(8)
        if (live.isNotEmpty()) sb.append("live view, last lines:\n").append(live.joinToString("") { "  $it\n" })
        sb.append("screen:\n").append(screen())
        return sb.toString()
    }

    /** Every piece of text on the screen, with the app it belongs to. */
    fun screen(): String {
        val xml = try {
            val out = ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            out.toString("UTF-8")
        } catch (e: Exception) {
            return "  (screen could not be read: $e)\n"
        }
        val sb = StringBuilder()
        for (node in Regex("<node [^>]*>").findAll(xml)) {
            val text = Regex(" text=\"([^\"]*)\"").find(node.value)?.groupValues?.get(1) ?: ""
            if (text.isEmpty()) continue
            val pkg = Regex(" package=\"([^\"]*)\"").find(node.value)?.groupValues?.get(1) ?: ""
            sb.append("  [").append(pkg).append("] ").append(text.take(200)).append("\n")
        }
        return sb.toString()
    }

    fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val v = try {
                probe()
            } catch (e: StaleObjectException) {
                null
            }
            if (v != null) return v
            if (SystemClock.uptimeMillis() > end) {
                throw AssertionError("Timed out after ${timeoutMs / 1000} s waiting for: $what\n${state()}")
            }
            SystemClock.sleep(150)
        }
    }

    fun until(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        waitFor(what, timeoutMs) { if (condition()) true else null }
    }

    /** Like [until], for something worth waiting for but not worth failing over: false if it did not happen in [ms]. */
    fun waitUpTo(ms: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (true) {
            val met = try {
                condition()
            } catch (e: StaleObjectException) {
                false
            }
            if (met) return true
            if (SystemClock.uptimeMillis() > end) return false
            SystemClock.sleep(150)
        }
    }

    /** Lets [ms] pass while checking that [condition] stays true the whole time. */
    fun holds(what: String, ms: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (!condition()) throw AssertionError("No longer true: $what\n${state()}")
            SystemClock.sleep(200)
        }
    }

    // ---------------------------------------------------------------- the app's own screen

    private val permissionButtons = By.res(Pattern.compile(".*:id/permission_(allow|deny).*button"))

    /** Starts the app. On a first start Android's permission question covers the screen at once; that counts as started. */
    fun launch(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the main screen or a permission question", 60_000) {
            // A freshly started (or just re-themed) emulator sometimes puts "System UI isn't responding" in
            // front of everything. That is the emulator being slow, not the app: tap "Wait" and carry on.
            device.findObject(By.res("android", "aerr_wait"))?.let {
                it.click()
                return@waitFor null
            }
            device.findObject(By.res(PKG, "status")) ?: device.findObject(permissionButtons)
        }
        return scenario
    }

    fun view(id: String, timeoutMs: Long = 15_000): UiObject2 =
        waitFor("view \"$id\"", timeoutMs) { device.findObject(By.res(PKG, id)) }

    fun tap(id: String) {
        waitFor("tap on \"$id\"") {
            val v = device.findObject(By.res(PKG, id)) ?: return@waitFor null
            v.click()
            true
        }
    }

    fun text(id: String): String = waitFor("text of \"$id\"") { device.findObject(By.res(PKG, id))?.text }

    fun type(id: String, text: String) {
        waitFor("typing into \"$id\"") {
            val v = device.findObject(By.res(PKG, id)) ?: return@waitFor null
            v.text = text
            true
        }
    }

    private fun words(regex: String) = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

    fun find(regex: String, pkg: String? = null): UiObject2? {
        var sel: BySelector = By.text(words(regex))
        if (pkg != null) sel = sel.pkg(pkg)
        return device.findObject(sel)
    }

    fun see(regex: String, pkg: String? = null, timeoutMs: Long = 15_000): UiObject2 =
        waitFor("\"$regex\" on the screen" + (pkg?.let { " of $it" } ?: ""), timeoutMs) { find(regex, pkg) }

    fun tapText(regex: String, pkg: String? = null, timeoutMs: Long = 15_000) {
        waitFor("tap on \"$regex\"", timeoutMs) {
            val v = find(regex, pkg) ?: return@waitFor null
            v.click()
            true
        }
    }

    /**
     * Taps what [find] returns until it is gone - for buttons that close the window they are on.
     *
     * One tap is not always enough: a dialog can be read a second or two before Android lets it be
     * touched (it drops such a tap as "untrusted touch due to occlusion"; seen with the permission
     * question on Android 16). A person would simply tap again, and so does this.
     */
    fun tapAway(what: String, timeoutMs: Long = 15_000, find: () -> UiObject2?) {
        var target = waitFor("tap on $what", timeoutMs) { find() }
        val giveUp = SystemClock.uptimeMillis() + 30_000
        while (true) {
            try {
                target.click()
            } catch (e: StaleObjectException) {
                // it went away by itself
            }
            target = stillThere(2_500, find) ?: return
            if (SystemClock.uptimeMillis() > giveUp) throw AssertionError("Still on the screen after repeated taps: $what\n${state()}")
        }
    }

    /** Null as soon as [find] finds nothing twice in a row; what it found last if that never happens within [ms]. */
    private fun stillThere(ms: Long, find: () -> UiObject2?): UiObject2? {
        val end = SystemClock.uptimeMillis() + ms
        var misses = 0
        var last: UiObject2? = null
        while (true) {
            val v = try {
                find()
            } catch (e: StaleObjectException) {
                null
            }
            if (v == null) {
                if (++misses >= 2) return null
            } else {
                misses = 0
                last = v
            }
            if (SystemClock.uptimeMillis() > end) return last ?: v
            SystemClock.sleep(150)
        }
    }

    /** [tapAway] for a button named by its text, e.g. "Cancel" on one of Android's own dialogs. */
    fun tapAwayText(regex: String, pkg: String?, timeoutMs: Long = 15_000) =
        tapAway("\"$regex\"" + (pkg?.let { " of $it" } ?: ""), timeoutMs) { find(regex, pkg) }

    /**
     * Taps the app's own view [id] until [effect] is seen - for a tap that opens another window. While
     * that window is on its way in, the view is already covered and cannot be tapped twice.
     */
    fun tapUntil(id: String, what: String, effect: () -> Boolean) {
        val giveUp = SystemClock.uptimeMillis() + 30_000
        while (!effect()) {
            if (SystemClock.uptimeMillis() > giveUp) throw AssertionError("Tapping \"$id\" did not lead to: $what\n${state()}")
            try {
                device.findObject(By.res(PKG, id))?.click()
            } catch (e: StaleObjectException) {
                // look again
            }
            val by = SystemClock.uptimeMillis() + 3_000
            while (!effect() && SystemClock.uptimeMillis() < by) SystemClock.sleep(150)
        }
    }

    /** The positive / negative button of the app's own AlertDialog that is showing. */
    fun dialogButton(positive: Boolean) {
        val id = if (positive) "android:id/button1" else "android:id/button2"
        // Only the app's own: one of Android's dialogs may follow at once, and its buttons have the same ids.
        tapAway("dialog button $id") { device.findObject(By.pkg(PKG).res(id)) }
    }

    /** The positive button of a dialog of Android's Settings app (battery optimisation, "request cancelled"). */
    val settingsOk: BySelector get() = By.pkg("com.android.settings").res("android:id/button1")

    // ---------------------------------------------------------------- system dialogs

    /**
     * Answers Android's permission questions one after the other and returns them (the text of each).
     *
     * They are counted by question, not by tap. Android takes its time putting an answered question
     * away, and ignores a tap that lands while the dialog is still settling; a question that is still
     * there after three seconds is simply tapped again.
     */
    fun answerPermissionDialogs(allow: Boolean): List<String> {
        val ids = if (allow) {
            "permission_allow_button|permission_allow_foreground_only_button"
        } else {
            "permission_deny_button|permission_deny_and_dont_ask_again_button"
        }
        val button = By.res(Pattern.compile(".*:id/($ids)"))
        val questions = ArrayList<String>()
        var last: String? = null
        var tappedAt = 0L
        var patience = 20_000L
        val giveUp = SystemClock.uptimeMillis() + 90_000
        while (questions.size < 8 && SystemClock.uptimeMillis() < giveUp) {
            val b = device.wait(Until.findObject(button), patience) ?: break
            patience = 4_000L
            try {
                val question = question(b)
                if (question == last && SystemClock.uptimeMillis() - tappedAt < 3_000) {
                    SystemClock.sleep(200)          // answered already: give Android time to move on
                    continue
                }
                b.click()
                if (question != last) questions.add(question)
                last = question
                tappedAt = SystemClock.uptimeMillis()
            } catch (e: StaleObjectException) {
                // the dialog changed under our hands: look again
            }
            SystemClock.sleep(300)
        }
        return questions
    }

    /** What the permission dialog that [button] belongs to is asking. */
    private fun question(button: UiObject2): String {
        device.findObject(By.res(Pattern.compile(".*:id/permission_message")))?.text?.let { if (it.isNotEmpty()) return it }
        // A dialog built differently: everything it says, which still tells one question from the next.
        return device.findObjects(By.pkg(button.applicationPackage)).mapNotNull { it.text }.filter { it.isNotEmpty() }.joinToString(" | ")
    }

    fun permissionDialogShowing(): Boolean = device.findObject(permissionButtons) != null

    /**
     * The list row that shows [namePart], scrolled into plain view (waited for): the smallest thing around that text
     * which also holds a tick box.
     */
    fun clipRow(namePart: String): UiObject2? {
        var node = clip(namePart)
        repeat(4) {
            node = node.parent ?: return null
            if (node.findObject(By.checkable(true)) != null) return node
        }
        return null
    }

    /**
     * The text of the clip row that shows [namePart], once that row is in plain view: inside the list, and not behind
     * the download panel, which on a phone floats over the bottom of the list (a tap there would land on its buttons).
     * A row that is not in plain view is scrolled to the top of the list, and this returns null: look again.
     */
    private fun clipText(namePart: String): UiObject2? {
        var clear = false
        onMain {
            val list = front()?.findViewById<ListView>(R.id.list) ?: return@onMain
            val i = (0 until list.count).firstOrNull { (list.getItemAtPosition(it) as? Cam.Clip)?.name?.contains(namePart) == true }
                ?: return@onMain
            val row = list.getChildAt(i - list.firstVisiblePosition)
            if (row != null && inPlainView(row, list)) clear = true else list.setSelectionFromTop(i, 0)
        }
        if (!clear) return null
        return device.findObject(By.text(Pattern.compile(".*" + Pattern.quote(namePart) + ".*", Pattern.DOTALL)))
    }

    /** [clipText], waited for. */
    fun clip(namePart: String): UiObject2 = waitFor("the row of $namePart in plain view") { clipText(namePart) }

    /** How many clip rows on the screen show a thumbnail rather than the placeholder. */
    fun thumbnailsShown(): Int {
        var n = 0
        onMain {
            val list = front()?.findViewById<ListView>(R.id.list) ?: return@onMain
            for (i in 0 until list.childCount) {
                if (list.getChildAt(i).findViewById<ImageView>(R.id.thumb)?.drawable != null) n++
            }
        }
        return n
    }

    /** The text of the app's view [id] if it is on the screen right now, else null. Does not wait. */
    fun textOf(id: String): String? = try {
        device.findObject(By.res(PKG, id))?.text
    } catch (e: StaleObjectException) {
        null
    }

    /** The app's screen that is in front right now, if any. */
    fun inFront(): Activity? {
        var a: Activity? = null
        onMain { a = resumed() }
        return a
    }

    private fun resumed(): Activity? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()

    /** The main screen in front, if there is one. Main thread only. */
    private fun front(): MainActivity? = resumed() as? MainActivity

    private fun inPlainView(row: View, list: ListView): Boolean {
        val r = onScreen(row)
        val l = onScreen(list)
        if (r.top < l.top || r.bottom > l.bottom) return false
        val panel = list.rootView.findViewById<View>(R.id.panel)
        return panel == null || !panel.isShown || !Rect.intersects(r, onScreen(panel))
    }

    private fun onScreen(v: View): Rect {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    /**
     * When the app ends a search while Android's Wi-Fi prompt is up (a time-out, or disconnecting),
     * Android replaces the prompt with a box saying the application has cancelled the request, and
     * leaves it there until someone taps OK. Taps it if it shows up.
     */
    fun dismissCancelledBox(waitMs: Long = 10_000): Boolean {
        val box = { device.findObject(settingsOk) ?: device.findObject(By.pkg("com.android.settings").text(Pattern.compile("ok", Pattern.CASE_INSENSITIVE))) }
        device.wait(Until.findObject(settingsOk), waitMs)
        if (box() == null) return false
        tapAway("Android's \"request cancelled\" box", 2_000, box)
        return true
    }

    /** Fills in the two fields and taps Connect, confirming the "one Wi-Fi radio" warning where the phone shows it. */
    fun connectThroughUi(ssid: String = "MiVue", pass: String = "12345678") {
        type("ssid", ssid)
        type("pass", pass)
        val twoLinks = !Link.oneWifiOnly(ctx)
        val warning = ".*cannot hold two Wi-Fi connections.*"
        if (Link.core.requests == 0) {
            // First connection of this process: either Android has been asked, or the warning below is up.
            tapUntil("connect", "a search for the camera") { Link.core.requests > 0 || (!twoLinks && find(warning) != null) }
        } else {
            tap("connect")
        }
        if (!twoLinks) {
            // The emulator cannot hold two Wi-Fi links: the app has to say so before it drops the current one.
            see(warning)
            dialogButton(positive = true)
        }
    }

    // ---------------------------------------------------------------- shared storage

    class Stored(val uri: Uri, val name: String, val pending: Boolean)

    /** This app's files in Movies/DashLink whose name contains [part], unfinished ones included. */
    fun stored(part: String): List<Stored> {
        val out = ArrayList<Stored>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle()
        args.putString(
            ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        )
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Movies/DashLink%", "%$part%"))
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING)
        ctx.contentResolver.query(collection, columns, args, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(Stored(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1) ?: "", c.getInt(2) == 1))
            }
        }
        return out
    }

    fun bytes(uri: Uri): ByteArray = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    fun deleteStored(part: String) {
        for (s in stored(part)) {
            try {
                ctx.contentResolver.delete(s.uri, null, null)
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    // ---------------------------------------------------------------- what the system itself says

    /** Android's own list of wake locks currently held, limited to this app's. */
    fun wakeLocksHeld(): List<String> =
        shell("dumpsys power").lines().filter { it.contains("dashlink:downloads") && it.contains("WAKE_LOCK") }

    /** Android's own list of Wi-Fi locks currently held by this app, or null if this Android does not print one. */
    fun wifiLocksHeld(): List<String>? {
        val lines = shell("dumpsys wifi").lines()
        // The heading is printed twice - by the Wi-Fi service, and again by its lock manager after two
        // lines of totals - and the locks follow the second one. Whichever is followed by locks counts.
        val headings = lines.indices.filter { lines[it].trim() == "Locks held:" }
        if (headings.isEmpty()) return null
        return headings
            .flatMap { h -> lines.drop(h + 1).takeWhile { it.contains("WifiLock{") } }
            .filter { it.contains("dashlink:downloads") }
    }

    /** A clip name no earlier test (or earlier run on the same emulator) can have used. */
    fun uniqueName(tag: String): String = "T${tag}_${java.lang.Long.toString(System.nanoTime(), 36)}.MP4"
}

/**
 * Stands in for ConnectivityManager: every request for the camera's Wi-Fi is answered with "connected"
 * (or not at all), so the rest of the app can be exercised on an emulator that has no camera to join.
 */
internal class FakeRadio : LinkCore.Radio {
    private val main = Handler(Looper.getMainLooper())
    val asked = CopyOnWriteArrayList<LinkCore.Request>()

    @Volatile
    var answer = true

    override fun request(request: LinkCore.Request, timeoutMs: Int) {
        asked.add(request)
        if (answer) main.post { up(request) }
    }

    override fun release(request: LinkCore.Request) {}

    private fun up(r: LinkCore.Request) {
        Link.core.onAvailable(r, "pretend network")
        Link.core.onSsid(r, "\"MiVue_Test\"")
        Link.core.onAddresses(
            r,
            InetAddress.getByAddress(byteArrayOf(192.toByte(), 72, 1, 1)),
            emptyList(),
            listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 72, 1, 33)))
        )
    }

    /** The camera's Wi-Fi vanishes. */
    fun drop() = main.post { asked.lastOrNull()?.let { Link.core.onLost(it) } }

    /** The camera's Wi-Fi is found for the request that is waiting right now. */
    fun connectNow() = main.post { asked.lastOrNull()?.let { up(it) } }
}

/**
 * Pictures for the pretend camera's live stream: a scene drawn on the phone, sent the way RFC 2435 sends Motion JPEG,
 * as the bare scan of a baseline JPEG and its quantization tables.
 *
 * Android's encoder (Bitmap.compress) picks Huffman tables to fit each picture, but RFC 2435 has no room for them: a
 * receiver always rebuilds the standard tables of JPEG Annex K. So the scan is decoded with the picture's own tables
 * and written again with the standard ones, code by code. The coefficients, and so the picture, stay the same.
 */
internal object FakePictures {
    /** One picture as RFC 2435 carries it. [type] 1 is 4:2:0, 0 is 4:2:2. */
    class Mjpeg(val scan: ByteArray, val tables: ByteArray, val type: Int, val width: Int, val height: Int) {
        /** The picture as FakeRtsp sends it at [time] (90 kHz), with Q 255: the tables travel with every picture. */
        fun frame(time: Long) = FakeRtsp.JpegFrame(scan, type, 255, width, height, time, tables)
    }

    /** A Motion JPEG stream for FakeRtsp that shows [picture] at [fps], over and over. */
    fun stream(picture: Bitmap, fps: Int = 10): FakeRtsp.Stream {
        val m = mjpeg(picture)
        val s = FakeRtsp.Stream("JPEG") {
            var n = 0L
            FakeRtsp.Source { m.frame(n++ * 90_000 / fps) }
        }
        s.intervalMs = 1000L / fps
        return s
    }

    /** [picture] as RFC 2435 sends it. Fails at once if the result would not show the same picture. */
    fun mjpeg(picture: Bitmap, quality: Int = 85): Mjpeg {
        val out = ByteArrayOutputStream()
        check(picture.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Bitmap.compress gave no JPEG" }
        val jpeg = out.toByteArray()
        val m = recode(jpeg)
        check(looksTheSame(jpeg, m)) { "the test's Motion JPEG picture, rebuilt as the app rebuilds it, is not the picture that was drawn" }
        return m
    }

    /**
     * True when [m], packed into RTP by FakeRtsp and rebuilt by the app's own depacketizer, decodes to (nearly) the
     * pixels of [jpeg]. A picture with wrong Huffman codes decodes to noise, which this tells apart easily.
     */
    private fun looksTheSame(jpeg: ByteArray, m: Mjpeg): Boolean {
        val d = JpegDepacketizer(26, 90_000)
        var rebuilt: ByteArray? = null
        d.out = RtpDepacketizer.Out { data, length, _, _ -> rebuilt = data.copyOf(length) }
        val payloads = FakeRtsp.jpegPayloads(m.frame(0), 1400)
        for ((i, p) in payloads.withIndex()) {
            val packet = FakeRtsp.rtp(26, i, 0, 1, i == payloads.size - 1, p)
            d.push(packet, 0, packet.size)
        }
        val image = rebuilt ?: return false
        val a = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false
        val b = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return false
        if (a.width != b.width || a.height != b.height) return false
        val pa = IntArray(a.width * a.height)
        val pb = IntArray(pa.size)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var off = 0
        for (i in pa.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                if (abs(((pa[i] ushr shift) and 0xff) - ((pb[i] ushr shift) and 0xff)) > 16) {
                    off++
                    break
                }
            }
        }
        return off * 100 < pa.size
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /** A Huffman table (JPEG Annex C), for reading codes and for writing them. */
    private class Huffman(bits: IntArray, private val values: IntArray) {
        private val maxCode = IntArray(17) { -1 }
        private val minCode = IntArray(17)
        private val first = IntArray(17)
        private val codes = IntArray(256)
        private val sizes = IntArray(256)

        init {
            var code = 0
            var k = 0
            for (length in 1..16) {
                first[length] = k
                minCode[length] = code
                repeat(bits[length - 1]) {
                    codes[values[k]] = code
                    sizes[values[k]] = length
                    code++
                    k++
                }
                maxCode[length] = if (bits[length - 1] > 0) code - 1 else -1
                code = code shl 1
            }
        }

        fun read(r: BitReader): Int {
            var code = r.bit()
            for (length in 1..16) {
                if (code <= maxCode[length]) return values[first[length] + code - minCode[length]]
                code = (code shl 1) or r.bit()
            }
            error("not a code of this Huffman table")
        }

        fun write(w: BitWriter, symbol: Int) {
            check(sizes[symbol] > 0) { "symbol $symbol is not in the table" }
            w.write(codes[symbol], sizes[symbol])
        }
    }

    /** Bits of a JPEG scan, most significant first, with the 00 stuffed after every FF taken out again. */
    private class BitReader(private val data: ByteArray, private var at: Int) {
        private var byte = 0
        private var left = 0

        fun bit(): Int {
            if (left == 0) {
                val b = data[at++].toInt() and 0xff
                if (b == 0xff) {
                    check(data[at].toInt() == 0) { "a marker inside the scan" }
                    at++
                }
                byte = b
                left = 8
            }
            left--
            return (byte shr left) and 1
        }

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var byte = 0
        private var count = 0

        fun write(value: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                byte = (byte shl 1) or ((value ushr i) and 1)
                if (++count == 8) {
                    out.write(byte)
                    if (byte == 0xff) out.write(0)              // an FF in a scan is followed by 00
                    byte = 0
                    count = 0
                }
            }
        }

        /** The scan, its last byte filled up with 1 bits as JPEG wants it. */
        fun finish(): ByteArray {
            if (count > 0) write(0xff, 8 - count)
            return out.toByteArray()
        }
    }

    private val dcLuma = Huffman(JpegDepacketizer.DC_LUMA_BITS, JpegDepacketizer.DC_VALUES)
    private val dcChroma = Huffman(JpegDepacketizer.DC_CHROMA_BITS, JpegDepacketizer.DC_VALUES)
    private val acLuma = Huffman(JpegDepacketizer.AC_LUMA_BITS, JpegDepacketizer.AC_LUMA_VALUES)
    private val acChroma = Huffman(JpegDepacketizer.AC_CHROMA_BITS, JpegDepacketizer.AC_CHROMA_VALUES)

    /**
     * Takes a baseline JPEG apart into what RFC 2435 carries: a three-component picture, luminance sampled 2x2 or 2x1,
     * one quantization table for luminance and one for both chrominance components, no restart markers, sides a
     * multiple of 8. Anything else is refused: the app could not show it either.
     */
    fun recode(jpeg: ByteArray): Mjpeg {
        val qt = arrayOfNulls<ByteArray>(4)
        val dc = arrayOfNulls<Huffman>(4)
        val ac = arrayOfNulls<Huffman>(4)
        var width = 0
        var height = 0
        val ids = IntArray(3)
        val sampling = IntArray(3)
        val quant = IntArray(3)
        var i = 2
        while (true) {
            check(u8(jpeg, i) == 0xff) { "no JPEG marker at byte $i" }
            val marker = u8(jpeg, i + 1)
            if (marker == 0xff) {                               // fill byte before a marker
                i++
                continue
            }
            val start = i + 4
            val end = i + 2 + u16(jpeg, i + 2)
            when (marker) {
                0xdb -> {
                    var p = start
                    while (p < end) {
                        check(u8(jpeg, p) shr 4 == 0) { "16-bit quantization tables" }
                        qt[u8(jpeg, p) and 15] = jpeg.copyOfRange(p + 1, p + 65)
                        p += 65
                    }
                }
                0xc0, 0xc1 -> {
                    check(u8(jpeg, start) == 8 && u8(jpeg, start + 5) == 3) { "not an 8-bit picture in three components" }
                    height = u16(jpeg, start + 1)
                    width = u16(jpeg, start + 3)
                    for (c in 0 until 3) {
                        ids[c] = u8(jpeg, start + 6 + c * 3)
                        sampling[c] = u8(jpeg, start + 7 + c * 3)
                        quant[c] = u8(jpeg, start + 8 + c * 3)
                    }
                }
                0xc4 -> {
                    var p = start
                    while (p < end) {
                        val bits = IntArray(16) { u8(jpeg, p + 1 + it) }
                        val values = IntArray(bits.sum()) { u8(jpeg, p + 17 + it) }
                        (if (u8(jpeg, p) shr 4 == 0) dc else ac)[u8(jpeg, p) and 15] = Huffman(bits, values)
                        p += 17 + values.size
                    }
                }
                0xdd -> check(u16(jpeg, start) == 0) { "restart markers" }
                0xc2, 0xc3, in 0xc5..0xcf -> error("not a baseline JPEG (marker ${Integer.toHexString(marker)})")
                0xda -> {
                    check(u8(jpeg, start) == 3) { "not one scan of all three components" }
                    val tdc = IntArray(3)
                    val tac = IntArray(3)
                    for (k in 0 until 3) {
                        check(u8(jpeg, start + 1 + k * 2) == ids[k]) { "components out of order" }
                        tdc[k] = u8(jpeg, start + 2 + k * 2) shr 4
                        tac[k] = u8(jpeg, start + 2 + k * 2) and 15
                    }
                    check(sampling[0] == 0x22 || sampling[0] == 0x21) { "luminance sampled ${Integer.toHexString(sampling[0])}" }
                    check(sampling[1] == 0x11 && sampling[2] == 0x11 && quant[1] == quant[2]) { "chrominance not as RFC 2435 has it" }
                    check(width % 8 == 0 && height % 8 == 0 && width <= 2040 && height <= 2040) { "size ${width}x$height" }
                    val h = sampling[0] shr 4
                    val v = sampling[0] and 15
                    val mcus = ((width + 8 * h - 1) / (8 * h)) * ((height + 8 * v - 1) / (8 * v))
                    val r = BitReader(jpeg, end)
                    val w = BitWriter()
                    repeat(mcus) {
                        for (c in 0 until 3) {
                            val luma = c == 0
                            repeat(if (luma) h * v else 1) {
                                block(r, w, dc[tdc[c]]!!, ac[tac[c]]!!, if (luma) dcLuma else dcChroma, if (luma) acLuma else acChroma)
                            }
                        }
                    }
                    return Mjpeg(w.finish(), qt[quant[0]]!! + qt[quant[1]]!!, if (v == 2) 1 else 0, width, height)
                }
            }
            i = end
        }
    }

    /** One 8x8 block: the same symbols and extra bits, under the standard codes. */
    private fun block(r: BitReader, w: BitWriter, dcIn: Huffman, acIn: Huffman, dcOut: Huffman, acOut: Huffman) {
        val size = dcIn.read(r)
        dcOut.write(w, size)
        w.write(r.bits(size), size)
        var k = 1
        while (k < 64) {
            val rs = acIn.read(r)
            acOut.write(w, rs)
            val s = rs and 15
            if (s == 0) {
                if (rs shr 4 != 15) return                    // end of block
                k += 16                                        // sixteen zeros
            } else {
                w.write(r.bits(s), s)
                k += (rs shr 4) + 1
            }
        }
    }

    /** The light of a drawn road: the colours of its sky, hills, ground and road, and whether lamps and lights glow. */
    enum class Light(
        val sky: IntArray,
        val glow: Int,
        val stars: Int,
        val hills: Int,
        val trees: Int,
        val ground: IntArray,
        val road: IntArray,
        val lamps: Boolean,
    ) {
        DUSK(
            intArrayOf(0xFF0E1630.toInt(), 0xFF2B3463.toInt(), 0xFF9C5568.toInt(), 0xFFF2A061.toInt()), 0x88FFB066.toInt(), 28,
            0xFF1D2233.toInt(), 0xFF10131C.toInt(), intArrayOf(0xFF22252C.toInt(), 0xFF111216.toInt()),
            intArrayOf(0xFF4A4648.toInt(), 0xFF2C2E35.toInt()), true,
        ),
        DAY(
            intArrayOf(0xFF3D79D2.toInt(), 0xFF7FB0E6.toInt(), 0xFFC9DFF1.toInt(), 0xFFEAF0EE.toInt()), 0x55FFFFFF, 0,
            0xFF7D9480.toInt(), 0xFF425C47.toInt(), intArrayOf(0xFF7A7D70.toInt(), 0xFF55584E.toInt()),
            intArrayOf(0xFF9A9B9D.toInt(), 0xFF66686C.toInt()), false,
        ),
        NIGHT(
            intArrayOf(0xFF04060D.toInt(), 0xFF0A1024.toInt(), 0xFF161D3A.toInt(), 0xFF262B48.toInt()), 0x33FFC46B, 60,
            0xFF0B0E18.toInt(), 0xFF06080D.toInt(), intArrayOf(0xFF15161B.toInt(), 0xFF0A0A0D.toInt()),
            intArrayOf(0xFF2A2A2E.toInt(), 0xFF17181C.toInt()), true,
        ),
    }

    /** [road] at dusk: what the live stream in the tests shows. */
    fun roadAtDusk(w: Int = 640, h: Int = 360): Bitmap = road(w, h, Light.DUSK)

    /**
     * A road as a dashcam sees it, [w] x [h] (sides a multiple of 8): the sky in [light], hills and a ragged treeline,
     * the road running to the vanishing point with its lane dashes, a car ahead and one coming the other way, street
     * lamps, and the date and speed burnt in at the bottom.
     */
    fun road(w: Int, h: Int, light: Light): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val horizon = h * 0.47f
        val cx = w * 0.5f
        val depth = h - horizon

        p.shader = LinearGradient(0f, 0f, 0f, horizon, light.sky, floatArrayOf(0f, 0.5f, 0.82f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon + 1f, p)
        p.shader = RadialGradient(w * 0.66f, horizon, w * 0.42f, light.glow, light.glow and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon + 1f, p)
        p.shader = null
        val stars = Random(7)
        p.color = 0x99FFFFFF.toInt()
        repeat(light.stars) { c.drawCircle(stars.nextFloat() * w, stars.nextFloat() * horizon * 0.45f, 0.6f + stars.nextFloat() * 0.8f, p) }

        // Hills, then a ragged treeline in front of them.
        val hills = Path()
        hills.moveTo(0f, horizon)
        var x = 0f
        while (x <= w) {
            hills.lineTo(x, horizon - 10f - 8f * sin(x / 57f) - 5f * sin(x / 23f))
            x += 8f
        }
        hills.lineTo(w.toFloat(), horizon)
        hills.close()
        p.color = light.hills
        c.drawPath(hills, p)
        val trees = Path()
        trees.moveTo(0f, horizon + 1f)
        x = 0f
        val jag = Random(11)
        while (x <= w) {
            val far = abs(x - cx) < w * 0.12f
            trees.lineTo(x, horizon - (if (far) 1f else 3f + jag.nextFloat() * 9f))
            x += 4f + jag.nextFloat() * 5f
        }
        trees.lineTo(w.toFloat(), horizon + 1f)
        trees.close()
        p.color = light.trees
        c.drawPath(trees, p)

        // The ground, and the road running to the vanishing point. A point at distance z (1 at the bottom edge)
        // is depth / z below the horizon, and sizes across shrink by the same 1 / z.
        p.shader = LinearGradient(0f, horizon, 0f, h.toFloat(), light.ground[0], light.ground[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon, w.toFloat(), h.toFloat(), p)
        val lane = w * 0.42f
        val road = Path()
        road.moveTo(cx - 3.2f * lane, h.toFloat())
        road.lineTo(cx + 1.25f * lane, h.toFloat())
        road.lineTo(cx + 0.6f, horizon)
        road.lineTo(cx - 0.6f, horizon)
        road.close()
        p.shader = LinearGradient(0f, horizon, 0f, h.toFloat(), light.road[0], light.road[1], Shader.TileMode.CLAMP)
        c.drawPath(road, p)
        p.shader = null

        fun stripe(offset: Float, near: Float, far: Float, halfWidth: Float, color: Int) {
            val s = Path()
            s.moveTo(cx + (offset - halfWidth) / near, horizon + depth / near)
            s.lineTo(cx + (offset + halfWidth) / near, horizon + depth / near)
            s.lineTo(cx + (offset + halfWidth) / far, horizon + depth / far)
            s.lineTo(cx + (offset - halfWidth) / far, horizon + depth / far)
            s.close()
            p.color = color
            c.drawPath(s, p)
        }
        stripe(1.12f * lane, 1f, 400f, 7f, 0xCCE9E6DF.toInt())             // the right-hand edge line
        stripe(-2.9f * lane, 1f, 400f, 7f, 0x99E9E6DF.toInt())             // the far edge of the other lane
        var z = 1.05f
        while (depth / z > 1.5f) {                                          // the dashed centre line
            stripe(-lane, z, z * 1.25f, 6f, 0xE6F2EEE4.toInt())
            z *= 1.7f
        }

        // A car ahead in this lane: a dark body with two red tail lights.
        val carZ = 5.2f
        val carW = 1.1f * lane / carZ
        val carBottom = horizon + depth / carZ
        val carX = cx + 0.1f * lane / carZ
        val car = RectF(carX - carW / 2f, carBottom - carW * 0.62f, carX + carW / 2f, carBottom)
        p.color = if (light.lamps) 0xFF0A0B0F.toInt() else 0xFF3B4048.toInt()
        c.drawRoundRect(car, carW * 0.12f, carW * 0.12f, p)
        for (side in intArrayOf(-1, 1)) {
            val lx = car.centerX() + side * carW * 0.36f
            val ly = car.top + car.height() * 0.42f
            if (light.lamps) {
                p.shader = RadialGradient(lx, ly, carW * 0.32f, 0x66FF2A1F, 0x00FF2A1F, Shader.TileMode.CLAMP)
                c.drawCircle(lx, ly, carW * 0.32f, p)
                p.shader = null
            }
            p.color = 0xFFFF4436.toInt()
            c.drawRoundRect(lx - carW * 0.09f, ly - carW * 0.04f, lx + carW * 0.09f, ly + carW * 0.04f, 2f, 2f, p)
        }
        // Headlights coming the other way, far off.
        val oz = 11f
        for (side in intArrayOf(-1, 1)) {
            val lx = cx - 2f * lane / oz + side * 0.32f * lane / oz
            val ly = horizon + depth / oz - 3f
            if (light.lamps) {
                p.shader = RadialGradient(lx, ly, 14f, 0x99FFF2D6.toInt(), 0x00FFF2D6, Shader.TileMode.CLAMP)
                c.drawCircle(lx, ly, 14f, p)
                p.shader = null
            }
            p.color = 0xFFFFF6E5.toInt()
            c.drawCircle(lx, ly, 2.2f, p)
        }
        // Street lamps along the right-hand side.
        for (lz in floatArrayOf(2.2f, 3.6f, 6f, 10f)) {
            val lx = cx + 1.6f * lane / lz
            val ly = horizon - 0.55f * depth / lz
            p.color = 0xFF15171D.toInt()
            c.drawRect(lx - 1.2f, ly, lx + 1.2f, horizon + depth / lz, p)
            if (light.lamps) {
                p.shader = RadialGradient(lx, ly, 46f / lz + 6f, 0x88FFC46B.toInt(), 0x00FFC46B, Shader.TileMode.CLAMP)
                c.drawCircle(lx, ly, 46f / lz + 6f, p)
                p.shader = null
            }
        }

        // What a dashcam burns into its picture: the date and the speed.
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.textSize = h / 22f
        p.color = 0xFFFFFFFF.toInt()
        p.setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        c.drawText("2026/11/16  17:42:08", w * 0.03f, h * 0.95f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("48 km/h", w * 0.97f, h * 0.95f, p)
        return b
    }

    /**
     * A short H.264 clip in MP4 that shows [picture] for [frames] frames at [fps], made with the phone's own encoder
     * and muxer: something real for thumbnails to be made from. Sides a multiple of 16 suit every encoder.
     */
    fun mp4(picture: Bitmap, frames: Int = 10, fps: Int = 10): ByteArray {
        val w = picture.width
        val h = picture.height
        val planes = yuv(picture)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val file = File.createTempFile("clip", ".mp4", Kit.ctx.cacheDir)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var queued = 0
            val giveUp = SystemClock.uptimeMillis() + 30_000
            while (true) {
                check(SystemClock.uptimeMillis() < giveUp) { "the encoder took more than 30 s for $frames frames" }
                if (queued <= frames) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val pts = queued * 1_000_000L / fps
                        if (queued == frames) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            fill(checkNotNull(codec.getInputImage(i)) { "the encoder has no input image" }, planes, w, h)
                            codec.queueInputBuffer(i, 0, w * h * 3 / 2, pts, 0)
                        }
                        queued++
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0     // in the track's format already
                    if (info.size > 0 && track >= 0) muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(o)), info)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop()
            muxer.stop()
        } finally {
            codec.release()
            muxer.release()
        }
        return file.readBytes().also { file.delete() }
    }

    /** [clip] made [size] bytes long by a top-level "free" box after it, which every MP4 reader passes over. */
    fun padded(clip: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        System.arraycopy(clip, 0, out, 0, clip.size)
        System.arraycopy(freeBox(size.toLong() - clip.size), 0, out, clip.size, 8)
        return out
    }

    /** The 8-byte header of a "free" box [length] bytes long, header included. */
    fun freeBox(length: Long): ByteArray {
        check(length in 8..0xFFFFFFFFL) { "a free box of $length bytes" }
        return byteArrayOf(
            (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
            'f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte(),
        )
    }

    /** [picture] in YUV 4:2:0 (BT.601, studio range): the luminance plane, then the two chrominance planes. */
    private fun yuv(picture: Bitmap): Array<ByteArray> {
        val w = picture.width
        val h = picture.height
        val argb = IntArray(w * h)
        picture.getPixels(argb, 0, w, 0, 0, w, h)
        val y = ByteArray(w * h)
        val u = ByteArray(w / 2 * (h / 2))
        val v = ByteArray(u.size)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val c = argb[row * w + col]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                y[row * w + col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val at = row / 2 * (w / 2) + col / 2
                    u[at] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    v[at] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
        }
        return arrayOf(y, u, v)
    }

    /** Copies [planes] into the encoder's input [image], whatever its strides (planar or semi-planar). */
    private fun fill(image: Image, planes: Array<ByteArray>, w: Int, h: Int) {
        for ((k, plane) in image.planes.withIndex()) {
            val pw = if (k == 0) w else w / 2
            val ph = if (k == 0) h else h / 2
            val src = planes[k]
            val buf = plane.buffer
            for (row in 0 until ph) {
                if (plane.pixelStride == 1) {
                    buf.position(row * plane.rowStride)
                    buf.put(src, row * pw, pw)
                } else {
                    for (col in 0 until pw) buf.put(row * plane.rowStride + col * plane.pixelStride, src[row * pw + col])
                }
            }
        }
    }
}
