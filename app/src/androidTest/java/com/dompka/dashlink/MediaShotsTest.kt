package com.dompka.dashlink

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * Pictures of the live viewer and the clip player in the states a user sees, taken on every build next to
 * ScreenshotTest's (the "screenshots" folder of the emulator job's artifact): playing sideways and upright, waiting
 * for a download, no stream, a clip playing in light and dark, a clip that cannot be played. The pictures on them
 * are the test video, not camera footage. Each test also checks that the screen got where it should.
 */
@RunWith(AndroidJUnit4::class)
class MediaShotsTest {
    private lateinit var rig: MediaRig
    private val dir = "/sdcard/Download/dashlink-shots"

    @Before
    fun setUp() {
        Kit.prepare()
        Kit.grantAll()
        Kit.shell("mkdir -p $dir")
        rig = MediaRig()
        rig.connect()
    }

    @After
    fun tearDown() {
        try {
            Kit.shell("cmd uimode night no")
            Kit.device.setOrientationNatural()
            Kit.device.unfreezeRotation()
        } finally {
            rig.close()
            Kit.deleteStored("FILE2603")
        }
    }

    private fun shot(name: String) {
        SystemClock.sleep(900)                  // fades and the first frames settle
        Kit.shell("screencap -p $dir/$name.png")
    }

    private fun <T> on(s: ActivityScenario<LiveActivity>, read: (LiveActivity) -> T): T {
        var out: Result<T>? = null
        s.onActivity { out = runCatching { read(it) } }
        return out!!.getOrThrow()
    }

    private fun phase(s: ActivityScenario<LiveActivity>) = on(s) { it.findViewById<LiveSurface>(R.id.live_surface).status.phase }

    private fun awaitPlaying(s: ActivityScenario<LiveActivity>) =
        Kit.until("the live view to play", 30_000) { phase(s) == Live.Phase.PLAYING }

    /** Sideways the controls fade out after a few seconds; a tap on the picture brings them back for the picture. */
    private fun controlsUp(s: ActivityScenario<LiveActivity>) {
        val up = { on(s) { it.findViewById<View>(R.id.live_close).let { v -> v.isShown && (v.parent as View).alpha == 1f } } }
        if (!up()) Kit.device.click(Kit.device.displayWidth / 2, Kit.device.displayHeight / 2)
        Kit.until("the viewer's controls to show") { up() }
    }

    private fun liveStream() {
        rig.rtsp.streams["/liveRTSP/av4"] = TestVideo.h264(4, 960, 540, 25).stream()
    }

    // ---------------------------------------------------------------- live viewer

    @Test
    fun shot20_liveViewer_sideways() {
        liveStream()
        Kit.device.setOrientationLeft()
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            awaitPlaying(s)
            Kit.until("the readout to show the frame rate") { on(s) { it.findViewById<TextView>(R.id.live_readout).text.contains("fps") } }
            SystemClock.sleep(1500)
            controlsUp(s)
            shot("20-live-viewer")
        }
    }

    @Test
    fun shot21_liveViewer_upright() {
        liveStream()
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            awaitPlaying(s)
            Kit.until("the readout to show the frame rate") { on(s) { it.findViewById<TextView>(R.id.live_readout).text.contains("fps") } }
            SystemClock.sleep(1500)
            shot("21-live-viewer-portrait")
        }
    }

    @Test
    fun shot22_liveViewer_waitingForADownload() {
        liveStream()
        Kit.device.setOrientationLeft()
        val clip = rig.clip("Normal", "FILE260301-100900F.MP4", ByteArray(3_000_000) { (it * 17).toByte() }, "2026-03-01 10:09:00")
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            awaitPlaying(s)
            SystemClock.sleep(1500)
            rig.slowDownload(clip, 90)
            Kit.until("the busy card") { on(s) { it.findViewById<TextView>(R.id.live_card_title).let { t -> t.isShown && t.text == LiveSurface.DOWNLOAD_BUSY } } }
            shot("22-live-busy")
        }
    }

    @Test
    fun shot23_liveViewer_noStream() {
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            Kit.until("the not-available card", 20_000) { phase(s) == Live.Phase.UNAVAILABLE }
            assertEquals("Live view not available", on(s) { it.findViewById<TextView>(R.id.live_card_title).text.toString() })
            shot("23-live-unavailable")
        }
    }

    // ---------------------------------------------------------------- player

    private fun playerIntent(clip: Cam.Clip) = Intent(Kit.ctx, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_PATH, clip.path).putExtra(PlayerActivity.EXTRA_SIZE, clip.size)
        .putExtra(PlayerActivity.EXTRA_TIME, clip.time).putExtra(PlayerActivity.EXTRA_RES, clip.res)
        .putExtra(PlayerActivity.EXTRA_SECS, clip.secs).putExtra(PlayerActivity.EXTRA_FOLDER, "Normal")

    private fun seconds(text: String): Int {
        val m = Regex("(\\d+):(\\d{2})").matchEntire(text) ?: return -1
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    /** A clip on the camera that is already on the phone, so the player also shows its "Saved" stamp. */
    private fun savedClip(): Cam.Clip {
        val name = "FILE260301-100800F.MP4"
        Kit.ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE).edit().putStringSet("saved", setOf(name)).commit()
        return rig.clip("Normal", name, TestVideo.mp4(8, 960, 540, 25), "2026-03-01 10:08:00", res = "960x540", secs = "8")
    }

    private fun playerShot(name: String, clip: Cam.Clip) {
        ActivityScenario.launch<PlayerActivity>(playerIntent(clip)).use {
            Kit.until("the clip to play", 30_000) { seconds(Kit.text("player_elapsed")) >= 2 }
            Kit.view("player_saved")
            shot(name)
        }
    }

    @Test
    fun shot24_player() = playerShot("24-player", savedClip())

    @Test
    fun shot25_player_dark() {
        Kit.shell("cmd uimode night yes")
        SystemClock.sleep(1500)
        playerShot("25-player-dark", savedClip())
    }

    @Test
    fun shot26_player_cannotPlay() {
        val junk = ByteArray(2_500_000).also { Random(26).nextBytes(it) }
        val clip = rig.clip("Normal", "FILE260301-101000F.MP4", junk, "2026-03-01 10:10:00", res = "2560x1440", secs = "60")
        ActivityScenario.launch<PlayerActivity>(playerIntent(clip)).use {
            Kit.until("the error", 40_000) { Kit.device.findObject(By.res(Kit.PKG, "player_error")) != null }
            assertEquals("Can't play this clip", Kit.text("player_error"))
            shot("26-player-error")
        }
    }
}
