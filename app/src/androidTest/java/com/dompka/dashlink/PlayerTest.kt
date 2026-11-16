package com.dompka.dashlink

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * The clip player against FakeCam serving a real MP4 (made by the emulator's own encoder, index at the end like
 * a dashcam's): it plays, it seeks, it only ever asks for byte ranges, it lets go of the camera as soon as it is
 * closed, it downloads, it waits out a running download, and it says so when a clip cannot be played.
 */
@RunWith(AndroidJUnit4::class)
class PlayerTest {
    private lateinit var rig: MediaRig
    private val tag = System.nanoTime().toString()

    @Before
    fun setUp() {
        Kit.prepare()
        Kit.grantAll()
        rig = MediaRig()
        rig.connect()
    }

    @After
    fun tearDown() {
        try {
            assertTrue("not a read: ${rig.cam.violations.map { it.target }}", rig.cam.violations.isEmpty())
        } finally {
            rig.close()
            Kit.deleteStored(tag)
        }
    }

    private fun name(letter: String) = "T${tag}_$letter.MP4"

    private fun intent(clip: Cam.Clip, folder: String) = Intent(Kit.ctx, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_PATH, clip.path).putExtra(PlayerActivity.EXTRA_SIZE, clip.size)
        .putExtra(PlayerActivity.EXTRA_TIME, clip.time).putExtra(PlayerActivity.EXTRA_RES, clip.res)
        .putExtra(PlayerActivity.EXTRA_SECS, clip.secs).putExtra(PlayerActivity.EXTRA_FOLDER, folder)

    /** "mm:ss" as seconds. */
    private fun seconds(text: String): Int {
        val m = Regex("(\\d+):(\\d{2})").matchEntire(text) ?: throw AssertionError("not a time: \"$text\"")
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun elapsed() = seconds(Kit.text("player_elapsed"))

    private fun awaitElapsed(atLeast: Int, what: String, timeoutMs: Long = 30_000) {
        Kit.until("$what: the position to reach $atLeast s", timeoutMs) { elapsed() >= atLeast }
    }

    /** Playing again: the position moves on from where it stood. */
    private fun awaitMoving(what: String, timeoutMs: Long = 30_000) {
        val from = elapsed()
        Kit.until("$what: the position to move on from $from s", timeoutMs) { elapsed() > from }
    }

    private fun cameraFreeWithin(ms: Long) {
        Kit.until("the camera to be free", ms) {
            try {
                Cam.session(0) {}
                true
            } catch (e: Cam.BusyException) {
                false
            }
        }
    }

    @Test
    fun plays_seeks_readsOnlyRanges_andLetsGoOfTheCameraWhenClosed() {
        val clip = rig.clip("Normal", name("P"), TestVideo.mp4(20, 640, 360, 25), "2026-03-01 10:08:00")
        ActivityScenario.launch<PlayerActivity>(intent(clip, "Normal")).use {
            awaitElapsed(2, "playing")
            assertEquals("00:20", Kit.text("player_total"))
            assertEquals("Pause", Kit.view("player_play").contentDescription)
            val before = elapsed()
            Kit.tap("player_fwd10")
            awaitElapsed(before + 9, "after +10 s", 15_000)
            val ahead = elapsed()
            Kit.tap("player_back10")
            Kit.until("-10 s to go back from $ahead s") { elapsed() <= ahead - 8 }
            Kit.tap("player_play")
            Kit.until("the button to offer Play once paused") { Kit.view("player_play").contentDescription == "Play" }
            val paused = elapsed()
            SystemClock.sleep(1_500)
            assertEquals("paused means paused", paused, elapsed())
            val files = rig.cam.fileRequests()
            assertTrue("the clip was read", files.isNotEmpty())
            assertTrue("every read is a Range request: ${files.map { it.headers["range"] }}", files.all { it.headers["range"] != null })
        }
        cameraFreeWithin(2_000)
    }

    @Test
    fun download_fromThePlayer_storesTheClip_andShowsSaved() {
        val data = TestVideo.mp4(6, 640, 360, 25)
        val clip = rig.clip("Normal", name("D"), data, "2026-03-01 10:09:00", secs = "6")
        // Slow enough to see the player wait for it.
        rig.cam.tweak = { req, reply ->
            if (req.path == clip.path && req.headers["range"] == null) {
                reply.chunk = 64 * 1024
                reply.chunkDelayMs = 120
            }
        }
        ActivityScenario.launch<PlayerActivity>(intent(clip, "Normal")).use {
            awaitElapsed(1, "playing")
            Kit.tap("player_download")
            Kit.until("the player to wait for the download") { Kit.find(Regex.escape(LiveSurface.DOWNLOAD_BUSY)) != null }
            Kit.until("Stop to be offered while it runs") { Kit.device.findObject(By.res(Kit.PKG, "player_stop")) != null }
            val stored = Kit.waitFor("the clip in shared storage", 60_000) { Kit.stored(tag).singleOrNull { it.name == clip.name && !it.pending } }
            assertArrayEquals(data, Kit.bytes(stored.uri))
            Kit.until("the Saved stamp", 20_000) { Kit.device.findObject(By.res(Kit.PKG, "player_saved")) != null }
            assertEquals("Saved", Kit.text("player_saved"))
            assertEquals("Saved earlier", Kit.text("player_saved_note"))
            assertEquals("the button keeps its name", "Download", Kit.text("player_download"))
            Kit.until("the busy card to go once the download is done", 20_000) { Kit.find(Regex.escape(LiveSurface.DOWNLOAD_BUSY)) == null }
            awaitMoving("playing again after the download")
        }
    }

    @Test
    fun aDownloadStarts_thePlayerLetsGo_andOneOpenedMeanwhileWaits_thenPlays() {
        val clip = rig.clip("Normal", name("W"), TestVideo.mp4(6, 640, 360, 25), "2026-03-01 10:10:00", secs = "6")
        val other = rig.clip("Normal", name("X"), ByteArray(3_000_000) { (it * 7).toByte() }, "2026-03-01 10:11:00")
        ActivityScenario.launch<PlayerActivity>(intent(clip, "Normal")).use {
            awaitElapsed(1, "playing before the download")
            // Started while the player is on screen, as Android wants for a foreground service.
            rig.slowDownload(other, 60)
            Kit.see(Regex.escape(LiveSurface.DOWNLOAD_BUSY))
        }
        assertTrue("the download still runs", DownloadService.working)
        val read = rig.cam.fileRequests().count { it.path == clip.path }
        ActivityScenario.launch<PlayerActivity>(intent(clip, "Normal")).use {
            Kit.see(Regex.escape(LiveSurface.DOWNLOAD_BUSY))
            Kit.holds("the clip is not read while the download runs", 3_000) {
                rig.cam.fileRequests().count { it.path == clip.path } == read
            }
            DownloadService.stopAll(Kit.ctx)
            awaitElapsed(1, "playing once the download stopped")
        }
    }

    @Test
    fun linkLost_letsGo_andSaysSo_thenPlaysAgain() {
        val clip = rig.clip("Normal", name("K"), TestVideo.mp4(10, 640, 360, 25), "2026-03-01 10:12:00", secs = "10")
        ActivityScenario.launch<PlayerActivity>(intent(clip, "Normal")).use {
            awaitElapsed(1, "playing")
            rig.radio.answer = false
            rig.radio.drop()
            Kit.see("Lost the camera")
            Kit.until("Link to ask for the camera again by itself") { rig.radio.asked.size >= 2 }
            rig.radio.answer = true
            rig.radio.connectNow()
            Kit.until("the link to come up again", 30_000) { Link.ready }
            Kit.until("the lost card to go") { Kit.find("Lost the camera") == null }
            awaitMoving("playing again")
        }
    }

    @Test
    fun brokenClip_saysItCannotBePlayed_andDownloadStaysAvailable() {
        val junk = ByteArray(2_500_000).also { Random(5).nextBytes(it) }
        val clip = rig.clip("Normal", name("B"), junk, "2026-03-01 10:13:00")
        ActivityScenario.launch<PlayerActivity>(intent(clip, "Normal")).use {
            Kit.until("the error", 40_000) { Kit.device.findObject(By.res(Kit.PKG, "player_error")) != null }
            assertEquals("Can't play this clip", Kit.text("player_error"))
            assertEquals("Try downloading it instead.", Kit.text("player_error_detail"))
            assertTrue("Download is still there to tap", Kit.view("player_download").isEnabled)
        }
        cameraFreeWithin(2_000)
    }
}
