package com.dompka.dashlink

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures of the screen in the states a user sees most, taken on every build and kept with the test
 * results (the "screenshots" folder of the emulator job's artifact). They are how a change to the look
 * of the app is judged without a phone at hand; each test also checks that the screen got there.
 *
 * The pretend camera is a fuller one here. Its live stream shows a drawn road at dusk (FakeRtsp), so the live tile
 * plays whenever the camera is connected, and its clips are short real H.264 files made on the phone and padded out
 * to a dashcam clip's size, so that the list can show thumbnails. Shots 20 and up (the viewer, the player) are in
 * another test.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private lateinit var cam: FakeCam
    private lateinit var rtsp: FakeRtsp
    private lateinit var radio: FakeRadio
    private lateinit var globals: Kit.Globals
    private val dir = "/sdcard/Download/dashlink-shots"

    /** Clips served from a short real MP4 and the header of an empty "free" box after it: path -> (those bytes, full size). */
    private val padded = ConcurrentHashMap<String, Pair<ByteArray, Long>>()

    /** One real clip per light of day; null when this phone could not make them (the rows then keep their placeholders). */
    private val clips: List<ByteArray>? by lazy {
        try {
            listOf(FakePictures.Light.DUSK, FakePictures.Light.DAY, FakePictures.Light.NIGHT).map { FakePictures.mp4(FakePictures.road(640, 352, it)) }
        } catch (e: Exception) {
            Log.w("ScreenshotTest", "no clips for thumbnails: ${Cam.describe(e)}")
            null
        }
    }

    @Before
    fun setUp() {
        Kit.prepare()
        globals = Kit.Globals()
        cam = FakeCam()
        cam.override = { req -> padded[req.path]?.let { (head, size) -> slice(req, head, size) } }
        rtsp = FakeRtsp()
        rtsp.streams["/liveRTSP/av4"] = FakePictures.stream(FakePictures.roadAtDusk())
        radio = FakeRadio()
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 5000
        Live.sockets = rtsp.sockets()
        Kit.onMain { Link.radio = radio }
        Kit.shell("mkdir -p $dir")
    }

    @After
    fun tearDown() {
        try {
            Kit.shell("cmd uimode night no")
            Kit.shell("wm size reset")
            Kit.shell("wm density reset")
            Kit.device.setOrientationNatural()
            Kit.device.unfreezeRotation()
            DownloadService.stopAll(Kit.ctx)
            Kit.onMain { Link.disconnect() }
            Kit.until("the service to stop", 20_000) { !DownloadService.alive && !DownloadService.working }
        } finally {
            cam.close()
            rtsp.close()
            Kit.deleteStored("FILE2603")
            globals.restore()
        }
    }

    private fun shot(name: String) {
        SystemClock.sleep(900)                  // ripples and the list's first layout settle
        Kit.shell("screencap -p $dir/$name.png")
    }

    private fun name(minute: Int) = "FILE260301-10%02d00F.MP4".format(minute)

    /**
     * A camera with a morning's worth of clips in Normal, some in Event and Parking. Only the newest Normal clip has
     * all of its content, for the download; the others are a real short clip padded out to their size.
     */
    private fun camera() {
        val sizes = listOf(101, 98, 104, 97, 102, 99, 103, 96)
        val older = sizes.mapIndexed { i, mb ->
            entry("/SD/Normal/F/${name(i)}", mb * 1_048_576L, "2026-03-01 10:%02d:00".format(i), "60", clip = i)
        }
        val newest = "/SD/Normal/F/${name(8)}"
        val content = clips?.let { FakePictures.padded(it[0], 24 * 1_048_576) }
            ?: ByteArray(24 * 1_048_576).also { Random(8).nextBytes(it) }
        cam.files[newest] = content
        cam.folders["Normal"] = older + FakeCam.Entry(newest, content.size.toLong(), "2026-03-01 10:08:00", "2560x1440", "60")
        cam.folders["Event"] = (0 until 4).map { i ->
            entry("/SD/Event/F/FILE260301-09%02d00E.MP4".format(i * 7), (31L + 3 * i) * 1_048_576, "2026-03-01 09:%02d:00".format(i * 7), "30", clip = i + 1)
        }
        cam.folders["Parking"] = (0 until 3).map { i ->
            entry("/SD/Parking/F/FILE260228-23%02d00P.MP4".format(i * 11), (12L + i) * 1_048_576, "2026-02-28 23:%02d:00".format(i * 11), "20", clip = 2)
        }
        cam.folders["Photo"] = emptyList()
        // One of them is on the phone already.
        Kit.ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE).edit().putStringSet("saved", setOf(name(5))).commit()
    }

    /** A listed clip of [size] bytes, served from real clip number [clip] when there are clips (else not at all: 404). */
    private fun entry(path: String, size: Long, time: String, secs: String, clip: Int): FakeCam.Entry {
        clips?.let { all ->
            val head = all[clip % all.size]
            padded[path] = (head + FakePictures.freeBox(size - head.size)) to size
        }
        return FakeCam.Entry(path, size, time, "2560x1440", secs)
    }

    /** The part of a padded clip that [req] asks for, at most 4 MB at a time: a thumbnail only ever reads pieces. */
    private fun slice(req: FakeCam.Request, head: ByteArray, size: Long): FakeCam.Reply {
        val from = req.rangeStart.coerceAtLeast(0)
        if (from >= size) return FakeCam.Reply(416).also { it.headers["Content-Range"] = "bytes */$size" }
        val last = minOf(if (req.rangeEnd >= from) req.rangeEnd else size - 1, size - 1, from + 4 * 1_048_576 - 1)
        val body = ByteArray((last - from + 1).toInt())
        if (from < head.size) System.arraycopy(head, from.toInt(), body, 0, minOf(head.size - from.toInt(), body.size))
        val reply = FakeCam.Reply(206, body)
        reply.headers["Content-Type"] = "video/mp4"
        reply.headers["Content-Range"] = "bytes $from-$last/$size"
        return reply
    }

    private val config: Configuration get() = Kit.ctx.resources.configuration

    /** Switches night mode on and waits until the app's own resources have followed. */
    private fun night() {
        Kit.shell("cmd uimode night yes")
        Kit.until("night mode") { (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    }

    /** Makes the screen roughly the inner screen of a fold, and waits until the app has been told. */
    private fun unfold() {
        Kit.shell("wm size 1812x2176")
        Kit.shell("wm density 380")
        Kit.until("the unfolded screen") { config.densityDpi == 380 && config.screenWidthDp >= 600 }
    }

    private fun connectedWithList() {
        Kit.connectThroughUi()
        Kit.until("the list to be loaded") { Kit.text("note").contains("Normal: 9 files") }
    }

    /** Shows the folder [name] (chosen the way its chip does it: on a phone some chips are scrolled out of sight). */
    private fun folder(name: String, files: Int) {
        Kit.onMain {
            Browser.folder = Browser.folders.indexOf(name).coerceAtLeast(0)
            Browser.load()
        }
        Kit.until("the $name folder to be loaded") { Kit.textOf("note")?.contains("$name: $files files") == true }
    }

    /** The live tile shows the camera's picture: the LIVE chip is up and its readout counts frames. */
    private fun livePicture() = Kit.until("the live tile to show the camera's picture", 30_000) {
        Kit.textOf("live_chip") == "LIVE" && (Kit.textOf("live_readout")?.let { Regex("[1-9][0-9]* fps").containsMatchIn(it) } ?: true)
    }

    /** The thumbnails of the rows on show, as far as they come: the shot is taken either way. */
    private fun thumbnails() {
        if (clips != null) Kit.waitUpTo(15_000) { Kit.thumbnailsShown() >= 2 }
    }

    @Test
    fun shot1_notConnected() {
        Kit.grantAll()
        Kit.launch().use {
            assertTrue(Kit.text("status").startsWith("Not connected to the camera."))
            shot("1-not-connected")
        }
    }

    @Test
    fun shot2_permissionsMissing() {
        Kit.launch().use {
            Kit.answerPermissionDialogs(allow = false)
            assertTrue(Kit.text("notice").contains("Location"))
            shot("2-permissions-missing")
        }
    }

    @Test
    fun shot3_clips_andADownload() {
        Kit.grantAll()
        camera()
        cam.tweak = { req, reply ->
            if (req.download) {
                reply.chunk = 256 * 1024
                reply.chunkDelayMs = 150                    // about fourteen seconds for the 24 MB clip
            }
        }
        Kit.launch().use {
            connectedWithList()
            thumbnails()
            livePicture()
            shot("3-clips")
            // "Select all" ticks the lot, and once more unticks it.
            Kit.tap("select_all")
            Kit.until("every clip to be ticked") { Kit.text("download") == "Download 9 clips" && Kit.text("select_all") == "Select none" }
            Kit.tap("select_all")
            Kit.until("no clip to be ticked") { Kit.text("download") == "Download selected" && Kit.text("select_all") == "Select all" }
            Kit.clip(name(8)).click()
            Kit.clip(name(7)).click()
            Kit.until("two clips to be ticked") { Kit.text("download") == "Download 2 clips" }
            shot("4-two-ticked")
            Kit.tap("download")
            Kit.until("the download to be well under way") { (Jobs.all.firstOrNull()?.done ?: 0) > 7 * 1_048_576 }
            // The live tile has stepped aside: its last picture, blurred, and why.
            Kit.until("the live tile to wait for the download") { Kit.textOf("live_state")?.startsWith("Camera busy") == true }
            shot("5-downloading")
        }
    }

    @Test
    fun shot4_dark() {
        Kit.grantAll()
        camera()
        night()
        Kit.launch().use {
            shot("6-dark-not-connected")
            connectedWithList()
            thumbnails()
            livePicture()
            Kit.clip(name(8)).click()
            shot("7-dark-clips")
        }
    }

    @Test
    fun shot5_unfolded_andSideways() {
        Kit.grantAll()
        camera()
        Kit.launch().use {
            connectedWithList()
            Kit.clip(name(8)).click()
            unfold()
            Kit.until("the wide layout") { Kit.text("status").contains("MiVue_Test") }
            thumbnails()
            livePicture()
            shot("8-unfolded")
            Kit.shell("wm size reset")
            Kit.shell("wm density reset")
            Kit.until("the phone screen again") { config.densityDpi != 380 && config.screenWidthDp < 600 }
            Kit.device.setOrientationLeft()
            // A turn asked for while the display is still settling from the resize can be dropped: ask again
            // every few seconds until the app has been told.
            var askedAt = SystemClock.uptimeMillis()
            Kit.until("the phone to be sideways", 30_000) {
                val sideways = config.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (!sideways && SystemClock.uptimeMillis() - askedAt > 3_000) {
                    Kit.device.setOrientationLeft()
                    askedAt = SystemClock.uptimeMillis()
                }
                sideways
            }
            // Sideways the live tile is further down the left column, out of sight: only the screen is waited for.
            Kit.until("the sideways layout") { Kit.text("status").contains("MiVue_Test") }
            thumbnails()
            shot("9-sideways")
        }
    }

    @Test
    fun shot10_liveTile() {
        Kit.grantAll()
        camera()
        Kit.launch().use {
            connectedWithList()
            // Event clips: the amber rail on every row, the lamp on the chosen chip.
            folder("Event", 4)
            thumbnails()
            livePicture()
            shot("10-live-tile")
        }
    }

    @Test
    fun shot11_liveTileUnavailable() {
        Kit.grantAll()
        camera()
        // A camera that does not let anyone in on its live-view port.
        Live.sockets = object : LiveSockets {
            override fun tcp(host: String, port: Int, timeoutMs: Int): Socket = throw ConnectException("Connection refused")
            override fun udp(port: Int): DatagramSocket = throw ConnectException("Connection refused")
        }
        Kit.launch().use {
            connectedWithList()
            thumbnails()
            Kit.until("the tile to say there is no live view", 30_000) { Kit.textOf("live_state") == "Live view not available" }
            shot("11-live-tile-unavailable")
        }
    }

    @Test
    fun shot12_darkLiveTile() {
        Kit.grantAll()
        camera()
        night()
        Kit.launch().use {
            connectedWithList()
            // Parking clips: the accent rail.
            folder("Parking", 3)
            thumbnails()
            livePicture()
            shot("12-dark-live-tile")
        }
    }

    @Test
    fun shot13_wideLiveTile() {
        Kit.grantAll()
        camera()
        unfold()                                            // from the start this time
        Kit.launch().use {
            connectedWithList()
            folder("Event", 4)
            thumbnails()
            livePicture()
            shot("13-wide-live-tile")
        }
    }
}
