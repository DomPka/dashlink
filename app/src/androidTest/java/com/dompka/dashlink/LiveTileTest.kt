package com.dompka.dashlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live tile on the main screen, against a pretend camera that streams Motion JPEG (FakeRtsp, showing a drawn road
 * at dusk) over the pretend Wi-Fi link (FakeRadio): it plays while the camera is connected, steps aside for a download
 * and comes back by itself, says so when there is no stream and asks again at once on Try again, hands the camera to
 * the full-screen viewer, and ends the stream when the link goes. The camera is only ever read from.
 */
@RunWith(AndroidJUnit4::class)
class LiveTileTest {
    private lateinit var cam: FakeCam
    private lateinit var rtsp: FakeRtsp
    private lateinit var radio: FakeRadio
    private lateinit var globals: Kit.Globals
    private lateinit var backOff: LongArray
    private val tag = System.nanoTime().toString()

    @Before
    fun setUp() {
        Kit.prepare()
        Kit.grantAll()
        globals = Kit.Globals()
        backOff = LiveTiming.backOffMs
        cam = FakeCam()
        cam.folders["Normal"] = emptyList()                 // a card with nothing on it yet, unless a test adds clips
        rtsp = FakeRtsp()
        // The stream the app tries first when the camera's settings name none.
        rtsp.streams["/liveRTSP/av4"] = Pictures.stream(Pictures.roadAtDusk())
        radio = FakeRadio()
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 5000
        Live.sockets = rtsp.sockets()
        Kit.onMain { Link.radio = radio }
    }

    @After
    fun tearDown() {
        try {
            DownloadService.stopAll(Kit.ctx)
            Kit.onMain { Link.disconnect() }
            Kit.until("the service to stop", 20_000) { !DownloadService.alive && !DownloadService.working }
        } finally {
            cam.close()
            rtsp.close()
            Kit.deleteStored(tag)
            LiveTiming.backOffMs = backOff
            globals.restore()
        }
    }

    private fun name(letter: String) = "T${tag}_$letter.MP4"

    private fun tileShown() = Kit.device.findObject(By.res(Kit.PKG, "live_tile")) != null

    /** The tile shows the camera's picture: only then does its readout count frames. */
    private fun playing() = Kit.textOf("live_readout")?.contains(" fps") == true

    private fun awaitPlaying(what: String) = Kit.until(what, 30_000) { playing() }

    /** Connects and waits until the (empty) Normal folder has been read, so that the list no longer needs the camera. */
    private fun connectedAndListed() {
        Kit.connectThroughUi()
        Kit.until("the folder to be read") { Kit.find(".*Normal: 0 files.*") != null }
    }

    private fun assertOnlyReads() {
        assertEquals("RTSP requests a read-only client must never send", emptyList<FakeRtsp.Request>(), rtsp.violations.toList())
        assertEquals("HTTP requests a read-only client must never send", emptyList<FakeCam.Request>(), cam.violations.toList())
    }

    @Test
    fun connected_theTilePlaysWhatTheCameraSees() {
        Kit.launch().use {
            assertTrue(Kit.text("status").startsWith("Not connected to the camera."))
            assertEquals("no live tile without a camera", false, tileShown())
            connectedAndListed()
            Kit.view("live_tile")
            assertEquals("Live view", Kit.text("live_title"))
            awaitPlaying("the tile to play")
            assertEquals("LIVE", Kit.text("live_chip"))
            assertNull("nothing is written over a playing picture", Kit.textOf("live_state"))
            val readout = Kit.text("live_readout")
            assertTrue(readout, Regex("^\\d+ fps · \\d+ kb/s").containsMatchIn(readout))
            assertTrue("the picture came by RTSP", rtsp.requests("PLAY").isNotEmpty())
            // Nothing else wants the camera now, so the picture keeps coming.
            Kit.holds("the picture keeps coming", 3000) { playing() }
            assertOnlyReads()
        }
    }

    @Test
    fun aDownload_hasTheCamera_andTheTileComesBackByItself() {
        val a = name("A")
        cam.addFile("Normal", a, 6_000_000, "2026-03-01 10:00:00")
        cam.tweak = { req, reply ->
            if (req.download) {
                reply.chunk = 20_000
                reply.chunkDelayMs = 40                     // about twelve seconds
            }
        }
        Kit.launch().use {
            Kit.connectThroughUi()
            Kit.until("the list to be loaded") { Kit.textOf("note")?.contains("Normal: 1 files") == true }
            awaitPlaying("the tile to play")
            Kit.clip(a).click()
            Kit.tap("download")
            Kit.until("the tile to step aside for the download", 20_000) { Kit.textOf("live_state") == LiveSurface.DOWNLOAD_BUSY }
            assertEquals("PAUSED", Kit.text("live_chip"))
            assertEquals("—", Kit.text("live_readout"))
            Kit.until("the download to be under way") { Jobs.all.single().state == Jobs.DOWNLOADING && Jobs.all.single().done > 0 }
            // Not a single stream is set up while the download runs.
            val plays = rtsp.requests("PLAY").size
            Kit.holds("no stream while the download has the camera", 3000) {
                rtsp.requests("PLAY").size == plays && Jobs.all.single().state == Jobs.DOWNLOADING
            }
            Kit.until("the clip to be saved", 60_000) { Jobs.all.single().state == Jobs.SAVED }
            awaitPlaying("the tile to play again once the download is done")
            assertEquals("LIVE", Kit.text("live_chip"))
            assertEquals("the camera was never asked two things at once", 1, cam.maxActive)
            assertOnlyReads()
        }
    }

    @Test
    fun disconnecting_hidesTheTile_andEndsTheStream() {
        Kit.launch().use {
            connectedAndListed()
            awaitPlaying("the tile to play")
            val ends = rtsp.requests("TEARDOWN").size
            Kit.tap("connect")                              // Disconnect
            Kit.until("the tile to go") { !tileShown() }
            Kit.until("the stream to be ended with a TEARDOWN") { rtsp.requests("TEARDOWN").size > ends }
            val asked = rtsp.requests.size
            Kit.holds("nothing more is asked of the camera's stream", 3000) { rtsp.requests.size == asked }
            assertTrue(Kit.text("status").startsWith("Not connected to the camera."))
            assertOnlyReads()
        }
    }

    @Test
    fun noStream_theTileSaysSo_andTryAgainAsksAtOnce() {
        // A camera that refuses every live-view connection. Live itself would only ask again after a minute here.
        LiveTiming.backOffMs = longArrayOf(60_000)
        val attempts = AtomicInteger()
        Live.sockets = object : LiveSockets {
            override fun tcp(host: String, port: Int, timeoutMs: Int): Socket {
                attempts.incrementAndGet()
                throw ConnectException("Connection refused")
            }

            override fun udp(): DatagramSocket = throw ConnectException("Connection refused")
        }
        Kit.launch().use {
            connectedAndListed()
            Kit.until("the tile to say there is no live view", 30_000) { Kit.textOf("live_state") == "Live view not available" }
            assertEquals("The camera did not answer on port 554", Kit.text("live_reason"))
            assertEquals("—", Kit.text("live_readout"))
            assertNull("no LIVE chip without a picture", Kit.textOf("live_chip"))
            assertNull("Try again takes the place of Open", Kit.textOf("live_open"))
            val before = attempts.get()
            Kit.tap("live_retry")
            Kit.until("a new attempt right after Try again", 5000) { attempts.get() > before }
            Kit.until("the tile to say so again") { Kit.textOf("live_state") == "Live view not available" }
            assertTrue("it still offers to try again", Kit.textOf("live_retry") == "Try again")
        }
    }

    @Test
    fun open_handsTheCameraToTheViewer_andTheTilePlaysAgainAfter() {
        Kit.launch().use {
            connectedAndListed()
            awaitPlaying("the tile to play")
            val ends = rtsp.requests("TEARDOWN").size
            Kit.tap("live_open")
            Kit.until("the live viewer to be in front", 15_000) { Kit.inFront() is LiveActivity }
            Kit.until("the tile to let go of its stream") { rtsp.requests("TEARDOWN").size > ends }
            Kit.device.pressBack()
            Kit.until("the main screen to be back", 15_000) { Kit.inFront() is MainActivity }
            awaitPlaying("the tile to play again")
            // The tile itself opens the viewer too.
            Kit.tap("live_tile")
            Kit.until("the live viewer to be in front again", 15_000) { Kit.inFront() is LiveActivity }
            Kit.device.pressBack()
            Kit.until("the main screen to be back again", 15_000) { Kit.inFront() is MainActivity }
            assertOnlyReads()
        }
    }
}
