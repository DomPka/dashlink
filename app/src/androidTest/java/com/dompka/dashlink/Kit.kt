package com.dompka.dashlink

import android.app.Instrumentation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/** Shared plumbing for the emulator tests: waiting, tapping, system dialogs, and looking into shared storage. */
internal object Kit {
    const val PKG = "com.dompka.dashlink"

    val instr: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = instr.targetContext
    val device: UiDevice by lazy { UiDevice.getInstance(instr) }
    val wifi: WifiManager get() = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun shell(cmd: String): String = device.executeShellCommand(cmd)

    /** Every test starts from a known place: screen on, unlocked, no leftovers from the test before. */
    fun prepare(batteryExempt: Boolean = true) {
        Configurator.getInstance().waitForIdleTimeout = 1000
        device.wakeUp()
        shell("wm dismiss-keyguard")
        shell("settings put secure show_ime_with_hard_keyboard 0")
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
        waitFor("the main screen or a permission question", 30_000) {
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
        val twoLinks = wifi.isStaConcurrencyForLocalOnlyConnectionsSupported
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
