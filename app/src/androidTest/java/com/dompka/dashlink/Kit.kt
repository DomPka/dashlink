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

    fun launch(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the main screen", 30_000) { device.findObject(By.res(PKG, "status")) }
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

    /** The positive / negative button of whatever AlertDialog is showing. */
    fun dialogButton(positive: Boolean) {
        val id = if (positive) "android:id/button1" else "android:id/button2"
        waitFor("dialog button $id") {
            val v = device.findObject(By.res(id)) ?: return@waitFor null
            v.click()
            true
        }
    }

    // ---------------------------------------------------------------- system dialogs

    /** Answers Android's permission questions one after the other. Returns how many there were. */
    fun answerPermissionDialogs(allow: Boolean): Int {
        val ids = if (allow) {
            "permission_allow_button|permission_allow_foreground_only_button"
        } else {
            "permission_deny_button|permission_deny_and_dont_ask_again_button"
        }
        val button = By.res(Pattern.compile(".*:id/($ids)"))
        var answered = 0
        while (answered < 8) {
            val b = device.wait(Until.findObject(button), if (answered == 0) 20_000L else 4_000L) ?: break
            try {
                b.click()
            } catch (e: StaleObjectException) {
                continue
            }
            answered++
            SystemClock.sleep(700)
        }
        return answered
    }

    fun permissionDialogShowing(): Boolean =
        device.findObject(By.res(Pattern.compile(".*:id/permission_(allow|deny).*button"))) != null

    /** Fills in the two fields and taps Connect, confirming the "one Wi-Fi radio" warning where the phone shows it. */
    fun connectThroughUi(ssid: String = "MiVue", pass: String = "12345678") {
        type("ssid", ssid)
        type("pass", pass)
        tap("connect")
        val twoLinks = wifi.isStaConcurrencyForLocalOnlyConnectionsSupported
        if (!twoLinks) {
            // The emulator cannot hold two Wi-Fi links: the app has to say so before it drops the current one.
            see(".*cannot hold two Wi-Fi connections.*")
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
        val start = lines.indexOfFirst { it.trim().startsWith("Locks held:") }
        if (start < 0) return null
        return lines.drop(start + 1).takeWhile { it.contains("WifiLock{") }.filter { it.contains("dashlink:downloads") }
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
