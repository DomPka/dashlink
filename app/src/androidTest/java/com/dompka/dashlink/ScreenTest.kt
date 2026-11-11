package com.dompka.dashlink

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The screen itself: being rebuilt, rotated and resized (what folding and unfolding does to an app),
 * the battery-optimisation question, and the Diagnostics report.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTest {
    private lateinit var cam: FakeCam
    private lateinit var radio: FakeRadio
    private val tag = System.nanoTime().toString()         // digits only: a clip name must not look like a secret

    @Before
    fun setUp() {
        Kit.prepare()
        Kit.grantAll()
        cam = FakeCam()
        radio = FakeRadio()
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 5000
        Kit.onMain { Link.radio = radio }
    }

    @After
    fun tearDown() {
        try {
            Kit.shell("settings put system font_scale 1.0")
            Kit.shell("wm size reset")
            Kit.shell("wm density reset")
            Kit.device.setOrientationNatural()
            Kit.device.unfreezeRotation()
            DownloadService.stopAll(Kit.ctx)
            Kit.onMain { Link.disconnect() }
            Kit.until("the service to stop", 20_000) { !DownloadService.alive && !DownloadService.working }
        } finally {
            Kit.shell("dumpsys deviceidle whitelist -${Kit.PKG}")
            cam.close()
            Kit.deleteStored(tag)
        }
    }

    private fun name(letter: String) = "T${tag}_$letter.MP4"

    private fun row(name: String) = Kit.waitFor("the row of $name") {
        Kit.device.findObject(By.text(Pattern.compile(".*" + Pattern.quote(name) + ".*", Pattern.DOTALL)))
    }

    /** Whether the row of [name] shows a tick: the box that sits in the same row as its texts. */
    private fun isTicked(name: String): Boolean {
        var node = row(name)
        repeat(4) {
            node = node.parent ?: return false
            node.findObject(By.checkable(true))?.let { return it.isChecked }
        }
        return false
    }

    /** How often the camera was asked for a file list. */
    private fun listings() = cam.requests.count { it.query["action"] == "dir" || it.query["action"] == "reardir" }

    private fun listSize(): Int {
        var n = 0
        Kit.onMain { n = Browser.clips.size }
        return n
    }

    /** The ticked clips as the app holds them, whatever part of the list happens to be on screen. */
    private fun ticked(): Set<String> {
        var out: Set<String> = emptySet()
        Kit.onMain { out = Browser.ticked.map { it.substringAfterLast('/') }.toSet() }
        return out
    }

    /** Connects (pretend link), waits for the list of three clips and ticks the first two. */
    private fun connectedWithTwoTicked(): List<String> {
        val names = listOf(name("A"), name("B"), name("C"))
        for ((i, n) in names.withIndex()) cam.addFile("Normal", n, 2000, "2026-03-01 10:0$i:00")
        Kit.connectThroughUi()
        Kit.until("the list to be loaded") { Kit.text("progress").contains("Normal: 3 files") }
        row(names[0]).click()
        row(names[1]).click()
        Kit.until("two rows to be ticked") { isTicked(names[0]) && isTicked(names[1]) && !isTicked(names[2]) }
        return names
    }

    // ---------------------------------------------------------------- rebuilt, rotated, resized

    @Test
    fun recreated_keepsLinkListAndTicks_withoutAskingTheCameraAgain() {
        Kit.launch().use { scenario ->
            val names = connectedWithTwoTicked()
            val asked = listings()
            var first: MainActivity? = null
            scenario.onActivity { first = it }

            repeat(3) { scenario.recreate() }
            var second: MainActivity? = null
            scenario.onActivity { second = it }
            assertTrue("it really is a new screen", first !== second)

            Kit.until("the status to be back") { Kit.text("status").contains("MiVue_Test at 192.72.1.1") }
            assertTrue(Kit.text("progress").contains("Normal: 3 files"))
            assertTrue("ticks survive", isTicked(names[0]) && isTicked(names[1]) && !isTicked(names[2]))
            assertEquals("DISCONNECT", Kit.text("connect").uppercase())
            SystemClock.sleep(1500)
            assertEquals("the camera was not asked for the list again", asked, listings())

            // The new screen is the one that hears about the link: the old one must not have taken that with it.
            Kit.tap("connect")
            Kit.until("the new screen to show the disconnect") { Kit.text("status").startsWith("Not connected to the camera.") }
            assertEquals(LinkState.IDLE, Link.state)
            assertEquals("CONNECT", Kit.text("connect").uppercase())
            assertEquals("the name field is back, with the camera's full name", "MiVue_Test", Kit.text("ssid"))
        }
    }

    @Test
    fun rebuiltFromAnOlderPicture_theTicksStayOnTheRightClips() {
        Kit.launch().use {
            val names = connectedWithTwoTicked()            // on screen, newest first: C, B*, A*
            // The screen goes away. Android saves what it looked like: rows 2 and 3 ticked.
            Kit.device.pressHome()
            SystemClock.sleep(2000)
            // Meanwhile the camera records a new clip and the list is read again: D, C, B*, A*.
            val newest = name("D")
            cam.addFile("Normal", newest, 2000, "2026-03-01 10:09:00")
            Kit.onMain { Browser.load() }
            Kit.until("the list of four") { ticked().size == 2 && listSize() == 4 }
            // Then something makes Android rebuild the screen from the picture it saved (here: a new font size).
            Kit.shell("settings put system font_scale 1.15")
            Kit.ctx.startActivity(Intent(Kit.ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Kit.until("the rebuilt screen") { Kit.text("progress").contains("Normal: 4 files") }
            SystemClock.sleep(1000)
            // The ticks must be where the user put them, not on the rows that used to be second and third.
            assertTrue("A", isTicked(names[0]))
            assertTrue("B", isTicked(names[1]))
            assertFalse("C was never ticked", isTicked(names[2]))
            assertFalse("the new clip was never ticked", isTicked(newest))
            assertEquals(setOf(names[0], names[1]), ticked())
            // And what gets downloaded is what looks ticked.
            Kit.tap("download")
            Kit.until("the two ticked clips to be saved", 60_000) { Jobs.all.count { it.state == Jobs.SAVED } == 2 }
            assertEquals(setOf(names[0], names[1]), Jobs.all.map { it.clip.name }.toSet())
        }
    }

    @Test
    fun recreatedWhileDownloading_theDownloadGoesOn() {
        val a = name("A")
        cam.addFile("Normal", a, 6_000_000)
        cam.tweak = { req, reply ->
            if ('?' !in req.target) {
                reply.chunk = 20_000
                reply.chunkDelayMs = 80                     // about twenty-four seconds
            }
        }
        Kit.launch().use { scenario ->
            Kit.connectThroughUi()
            Kit.until("the list to be loaded") { Kit.text("progress").contains("Normal: 1 files") }
            row(a).click()
            Kit.tap("download")
            Kit.until("the download to be under way") { (Jobs.all.firstOrNull()?.done ?: 0) > 100_000 }
            repeat(3) {
                scenario.recreate()
                Kit.until("progress on the rebuilt screen") {
                    Kit.text("progress").contains("downloading") || Jobs.all.single().state == Jobs.SAVED
                }
            }
            assertEquals("still the same job, still running", Jobs.DOWNLOADING, Jobs.all.single().state)
            Kit.until("the clip to be saved", 60_000) { Jobs.all.single().state == Jobs.SAVED }
            assertEquals("one job, one file: nothing was started twice", 1, Kit.stored(tag).size)
            assertEquals(1, cam.fileRequests().size)
        }
    }

    @Test
    fun rotatedAndResized_likeFoldingAndUnfolding_staysUsable() {
        Kit.launch().use { scenario ->
            val names = connectedWithTwoTicked()
            val asked = listings()
            val steps = listOf<() -> Unit>(
                { Kit.device.setOrientationLeft() },        // landscape: the shortest the screen gets
                { Kit.device.setOrientationNatural() },
                { Kit.shell("wm size 1812x2176") },         // roughly the inner screen of a fold...
                { Kit.shell("wm density 380") },
                { Kit.device.setOrientationRight() },
                { Kit.shell("wm size 904x2316") },          // ...and its narrow cover screen, still sideways
                { Kit.device.setOrientationNatural() },
                { Kit.shell("wm size reset") },
                { Kit.shell("wm density reset") }
            )
            for ((i, step) in steps.withIndex()) {
                step()
                SystemClock.sleep(1200)
                Kit.until("step $i: the screen to be there") { Kit.text("status").contains("MiVue_Test at 192.72.1.1") }
                assertTrue("step $i: list", Kit.text("progress").contains("Normal: 3 files"))
                assertEquals("step $i: ticks", setOf(names[0], names[1]), ticked())
                // Whatever the shape of the screen, the list and the buttons that act on it are on it.
                for (id in listOf("list", "download", "stop", "diag")) Kit.view(id, 5000)
            }
            assertEquals("no reload along the way", asked, listings())
            assertTrue("back in the normal shape the ticks are on screen too", isTicked(names[0]) && isTicked(names[1]) && !isTicked(names[2]))
            for (id in listOf("connect", "refresh")) Kit.view(id)
            var alive = false
            scenario.onActivity { alive = !it.isFinishing && !it.isDestroyed }
            assertTrue(alive)
            // And it still does what it is there for.
            Kit.tap("download")
            Kit.until("both ticked clips to be saved", 60_000) { Jobs.all.count { it.state == Jobs.SAVED } == 2 }
        }
    }

    // ---------------------------------------------------------------- battery optimisation

    @Test
    fun batteryOptimisation_isDetected_andTheSystemQuestionIsOffered() {
        Kit.prepare(batteryExempt = false)
        assertFalse(Power.exempt(Kit.ctx))
        Kit.launch().use {
            val names = connectedWithTwoTicked()
            // Hold the first clip up so the queue is still busy while the dialogs are on screen.
            cam.tweak = { req, reply -> if ('?' !in req.target) reply.stallMs = 4000 }
            Kit.tap("download")

            // The first download brings the app's own explanation...
            Kit.see(".*Keep downloading with the screen off.*")
            Kit.dialogButton(positive = true)
            // ...and "Allow" leads to Android's own question, which only the user can answer.
            Kit.waitFor("Android's battery-optimisation question", 20_000) { Kit.device.findObject(Kit.settingsOk) }
            assertNotNull(Kit.find(".*background.*", "com.android.settings"))
            Kit.tapAway("Allow on Android's battery-optimisation question") { Kit.device.findObject(Kit.settingsOk) }
            Kit.until("the exemption to be granted") { Power.exempt(Kit.ctx) }

            Kit.until("both clips to be saved", 60_000) { Jobs.all.count { it.state == Jobs.SAVED } == 2 }
            assertEquals(2, Kit.stored(tag).count { !it.pending && (it.name == names[0] || it.name == names[1]) })
        }
    }

    @Test
    fun batteryOptimisation_declinedOnce_staysOnOfferWithoutNagging() {
        Kit.prepare(batteryExempt = false)
        Kit.launch().use { scenario ->
            connectedWithTwoTicked()
            cam.tweak = { req, reply ->
                if ('?' !in req.target) {
                    reply.chunk = 250
                    reply.chunkDelayMs = 2500               // each clip takes a good quarter of a minute
                }
            }
            Kit.tap("download")
            Kit.see(".*Keep downloading with the screen off.*")
            Kit.dialogButton(positive = false)                // "Not now"
            // No second dialog, but a line on the screen with a button for as long as downloads are waiting.
            Kit.until("the standing offer") { Kit.text("notice").contains("Battery optimisation is on") }
            assertEquals("ALLOW…", Kit.text("notice_action").uppercase())
            scenario.recreate()
            Kit.until("the offer on the rebuilt screen") { Kit.text("notice").contains("Battery optimisation is on") }
            assertNull("the dialog does not come back", Kit.find(".*Keep downloading with the screen off.*"))
            // The button opens the same system question.
            Kit.tapUntil("notice_action", "Android's battery-optimisation question") { Kit.device.findObject(Kit.settingsOk) != null }
            Kit.tapAway("Allow on Android's battery-optimisation question") { Kit.device.findObject(Kit.settingsOk) }
            Kit.until("the exemption to be granted") { Power.exempt(Kit.ctx) }
            Kit.until("the offer to go away") { Kit.device.findObject(By.res(Kit.PKG, "notice")) == null }
        }
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun diagnostics_reportHasTheFactsButNeverThePassword() {
        Kit.launch().use {
            // The camera's settings contain its Wi-Fi key ("Net.WIFI_AP.CryptoKey=hunter2secret" in FakeCam), and
            // this camera also repeats the password under a name nobody would think to filter.
            val password = "correct-horse-battery"
            cam.props += "Vendor.Odd.Field=$password\n"
            val names = connectedWithTwoTicked()
            val report = Report.build(Kit.ctx, "Normal", false, password)
            assertTrue(report, report.startsWith("DashLink 0.2 ("))
            for (expected in listOf(
                "Phone: ", "Two Wi-Fi links at once (local-only): ", "Permissions missing: none",
                "Battery: exempt from optimisation=true", "Link: CONNECTED, camera MiVue_Test at 192.72.1.1",
                "== Device info: /cgi-bin/Config.cgi?action=get&property=Vendor.DeviceInfo.*",
                "Vendor.DeviceInfo.Model=MiVue 956W", "Vendor.Odd.Field=[removed]",
                "== Status: ", "== List Normal: ", names[0], "== Downloads"
            )) {
                assertTrue("missing \"$expected\" in:\n$report", report.contains(expected))
            }
            assertFalse("the camera's Wi-Fi key leaked", report.contains("hunter2secret"))
            assertFalse(report.contains("CryptoKey"))
            assertFalse("the typed password leaked", report.contains(password))
            assertTrue(report.length < 110_000)
            assertEquals("the report only reads", 0, cam.requests.count { it.query["action"] !in setOf(null, "get", "dir", "reardir") })
        }
    }

    @Test
    fun diagnostics_buttonOpensTheShareSheet() {
        Kit.launch().use {
            Kit.tap("diag")
            // Android's share sheet comes up, showing the start of the report as what is about to be shared.
            val preview = ".*DashLink 0\\.2 \\(.*diagnostics.*"
            Kit.until("the share sheet", 30_000) { Kit.find(preview) != null || Kit.find(".*Share diagnostics.*") != null }
            Kit.device.pressBack()
            Kit.until("the share sheet to close") { Kit.find(preview) == null && Kit.find(".*Share diagnostics.*") == null }
            assertTrue(Kit.text("status").startsWith("Not connected to the camera."))
        }
    }

    @Test
    fun diagnostics_whileTheCameraIsBusy_doesNotWait() {
        val a = name("A")
        cam.addFile("Normal", a, 6_000_000)
        cam.tweak = { req, reply ->
            if ('?' !in req.target) {
                reply.chunk = 20_000
                reply.chunkDelayMs = 60
            }
        }
        Kit.onMain { Link.connect(Kit.ctx, "MiVue", "12345678", false) }
        Kit.until("the pretend link to come up") { Link.ready }
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            Kit.until("the download to be under way") { (Jobs.all.firstOrNull()?.done ?: 0) > 100_000 }
            val started = SystemClock.uptimeMillis()
            val report = Report.build(Kit.ctx, "Normal", false, "12345678")
            assertTrue("the report came back in ${SystemClock.uptimeMillis() - started} ms", SystemClock.uptimeMillis() - started < 8000)
            assertTrue(report, report.contains("Camera not asked: it is busy with a download"))
            assertTrue(report.contains("$a: downloading"))
            assertTrue(report.contains("Download service: running=true, working=true, locks held=true"))
        }
    }
}
