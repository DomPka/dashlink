package com.dompka.dashlink

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * The full-screen live viewer against FakeRtsp sending real H.264 (from the emulator's own encoder) and real
 * Motion JPEG: it plays, the picture is a picture, it gives the camera up when something else asks and comes back
 * by itself, it keeps away from the camera while a download runs, and closing it ends the session (TEARDOWN).
 */
@RunWith(AndroidJUnit4::class)
class LiveViewTest {
    private lateinit var rig: MediaRig

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
            assertTrue("requests a read-only client must never send: ${rig.rtsp.violations}", rig.rtsp.violations.isEmpty())
            assertTrue("not a read: ${rig.cam.violations.map { it.target }}", rig.cam.violations.isEmpty())
        } finally {
            rig.close()
        }
    }

    private fun <T> on(s: ActivityScenario<LiveActivity>, read: (LiveActivity) -> T): T {
        var out: Result<T>? = null
        s.onActivity { out = runCatching { read(it) } }
        return out!!.getOrThrow()
    }

    private fun status(s: ActivityScenario<LiveActivity>): Live.Status = on(s) { it.findViewById<LiveSurface>(R.id.live_surface).status }

    private fun text(s: ActivityScenario<LiveActivity>, id: Int): String = on(s) { it.findViewById<TextView>(id).text.toString() }

    private fun shown(s: ActivityScenario<LiveActivity>, id: Int): Boolean = on(s) { it.findViewById<View>(id).isShown }

    private fun describe(s: ActivityScenario<LiveActivity>): String {
        val st = status(s)
        return "status ${st.phase} \"${st.detail}\", readout \"${text(s, R.id.live_readout)}\", card shown ${shown(s, R.id.live_card)} " +
            "\"${text(s, R.id.live_card_title)}\"; RTSP requests ${rig.rtsp.requests.takeLast(12)}; live log ${Live.log().takeLast(8)}"
    }

    private fun awaitPlaying(s: ActivityScenario<LiveActivity>, what: String, timeoutMs: Long = 30_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!(status(s).phase == Live.Phase.PLAYING && text(s, R.id.live_readout).contains("fps"))) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("The viewer did not play ($what) within ${timeoutMs / 1000} s: ${describe(s)}")
            SystemClock.sleep(150)
        }
    }

    /** The frame on screen is a picture: bright somewhere, and not one flat colour. */
    private fun assertPicture(s: ActivityScenario<LiveActivity>) {
        val frame = Kit.waitFor("a frame on the live surface", 10_000) { on(s) { it.findViewById<LiveSurface>(R.id.live_surface).snapshot() } }
        val lum = samples(frame)
        assertTrue("the frame is black: brightest sample ${lum.max()} of ${lum.size}", lum.max() > 60)
        assertTrue("the frame is one flat colour: samples from ${lum.min()} to ${lum.max()}", lum.max() - lum.min() > 40)
    }

    private fun samples(b: Bitmap): List<Int> {
        val out = ArrayList<Int>()
        for (gy in 1..8) for (gx in 1..8) {
            val c = b.getPixel(b.width * gx / 9, b.height * gy / 9)
            out.add((((c ushr 16) and 0xff) * 3 + ((c ushr 8) and 0xff) * 6 + (c and 0xff)) / 10)
        }
        return out
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

    // ---------------------------------------------------------------- H.264 and Motion JPEG

    @Test
    fun h264_plays_showsAPicture_andClosingEndsTheSession() {
        rig.rtsp.streams["/liveRTSP/av4"] = TestVideo.h264(4, 640, 360, 25).stream()
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            awaitPlaying(s, "H.264")
            assertPicture(s)
            assertEquals("LIVE", text(s, R.id.live_chip))
            assertTrue("the detail names the stream: ${status(s).detail}", status(s).detail.startsWith("H.264 640x360"))
            assertTrue("no state card while playing", !shown(s, R.id.live_card))
            assertTrue(
                "the stamp is the phone's clock: ${text(s, R.id.live_stamp)}",
                Regex("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(text(s, R.id.live_stamp)),
            )
            assertEquals("no TEARDOWN while it plays", 0, rig.rtsp.requests("TEARDOWN").size)
            Kit.tap("live_close")
            Kit.until("the session to be ended with TEARDOWN", 5_000) { rig.rtsp.requests("TEARDOWN").isNotEmpty() }
        }
        cameraFreeWithin(2_000)
    }

    @Test
    fun motionJpeg_plays_andShowsAPicture() {
        rig.rtsp.streams["/liveRTSP/av4"] = TestVideo.jpegStream(TestVideo.jpeg(640, 360, 25), 25)
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            awaitPlaying(s, "Motion JPEG")
            assertPicture(s)
            assertTrue("the detail names the stream: ${status(s).detail}", status(s).detail.startsWith("JPEG 640x360"))
        }
        Kit.until("the session to be ended with TEARDOWN", 5_000) { rig.rtsp.requests("TEARDOWN").isNotEmpty() }
    }

    // ---------------------------------------------------------------- sharing the camera

    @Test
    fun anotherRequest_getsTheCameraWithinASecond_andTheViewerComesBackByItself() {
        rig.rtsp.streams["/liveRTSP/av4"] = TestVideo.h264(4, 640, 360, 25).stream()
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            awaitPlaying(s, "before the other request")
            val plays = rig.rtsp.requests("PLAY").size
            val waited = AtomicLong(-1)
            val other = Thread({
                val t0 = SystemClock.uptimeMillis()
                Cam.session(10_000) {
                    waited.set(SystemClock.uptimeMillis() - t0)
                    SystemClock.sleep(4_000)                    // a long list, say
                }
            }, "other-request")
            other.start()
            Kit.until("the other request to get the camera", 5_000) { waited.get() >= 0 }
            assertTrue("the other request waited ${waited.get()} ms for the camera", waited.get() < 1_500)
            Kit.until("the viewer to say it is paused") { status(s).phase == Live.Phase.PAUSED_BUSY }
            Kit.until("the card to say the camera is busy") { shown(s, R.id.live_card) && text(s, R.id.live_card_title) == "Camera busy" }
            other.join(10_000)
            awaitPlaying(s, "after the other request")
            assertTrue("a new stream was started", rig.rtsp.requests("PLAY").size > plays)
            assertPicture(s)
        }
    }

    @Test
    fun whileADownloadRuns_theViewerKeepsAwayFromTheCamera_thenPlays() {
        rig.rtsp.streams["/liveRTSP/av4"] = TestVideo.h264(4, 640, 360, 25).stream()
        val clip = rig.clip("Normal", "T${System.nanoTime()}_L.MP4", ByteArray(3_000_000) { (it * 31).toByte() }, "2026-03-01 10:00:00")
        try {
            // A download that starts while the viewer plays: the viewer steps aside and waits.
            ActivityScenario.launch(LiveActivity::class.java).use { s ->
                awaitPlaying(s, "before the download")
                rig.slowDownload(clip, 60)
                Kit.until("the busy card") { shown(s, R.id.live_card) && text(s, R.id.live_card_title) == LiveSurface.DOWNLOAD_BUSY }
                assertEquals("Live view resumes when the download finishes.", text(s, R.id.live_card_detail))
                val sent = rig.rtsp.requests.size
                Kit.holds("no live-view request while the download runs", 3_000) { rig.rtsp.requests.size == sent }
            }
            // A viewer opened while it runs does not even try.
            val sent = rig.rtsp.requests.size
            ActivityScenario.launch(LiveActivity::class.java).use { s ->
                Kit.until("the busy card on a new viewer") { shown(s, R.id.live_card) && text(s, R.id.live_card_title) == LiveSurface.DOWNLOAD_BUSY }
                Kit.holds("not a single live-view request from the new viewer", 3_000) { rig.rtsp.requests.size == sent }
                assertEquals(Live.Phase.PAUSED_BUSY, status(s).phase)
                DownloadService.stopAll(Kit.ctx)
                Kit.until("the download to stop", 20_000) { !DownloadService.working }
                awaitPlaying(s, "after the download")
                assertPicture(s)
            }
        } finally {
            Kit.deleteStored(clip.name)
        }
    }

    // ---------------------------------------------------------------- no stream

    @Test
    fun noStream_saysSo_andRetryFindsOneThatAppeared() {
        ActivityScenario.launch(LiveActivity::class.java).use { s ->
            Kit.until("the not-available card", 20_000) { status(s).phase == Live.Phase.UNAVAILABLE && shown(s, R.id.live_card) }
            assertEquals("Live view not available", text(s, R.id.live_card_title))
            assertTrue(text(s, R.id.live_card_detail), text(s, R.id.live_card_detail).startsWith("No live stream found"))
            assertTrue("Retry is offered", shown(s, R.id.live_retry))
            rig.rtsp.streams["/liveRTSP/av4"] = TestVideo.h264(2, 640, 360, 25).stream()
            Kit.tap("live_retry")
            awaitPlaying(s, "after Retry", 15_000)
        }
    }
}
