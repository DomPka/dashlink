package com.dompka.dashlink

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pictures of the screen in the states a user sees most, taken on every build and kept with the test
 * results (the "screenshots" folder of the emulator job's artifact). They are how a change to the look
 * of the app is judged without a phone at hand; each test also checks that the screen got there.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private lateinit var cam: FakeCam
    private lateinit var radio: FakeRadio
    private val dir = "/sdcard/Download/dashlink-shots"

    @Before
    fun setUp() {
        Kit.prepare()
        cam = FakeCam()
        radio = FakeRadio()
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 5000
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
            Kit.deleteStored("FILE2603")
        }
    }

    private fun shot(name: String) {
        SystemClock.sleep(900)                  // ripples and the list's first layout settle
        Kit.shell("screencap -p $dir/$name.png")
    }

    private fun name(minute: Int) = "FILE260301-10%02d00F.MP4".format(minute)

    /** A camera with a morning's worth of clips. Only the newest one has content to download. */
    private fun camera() {
        val sizes = listOf(101, 98, 104, 97, 102, 99, 103, 96)
        val older = sizes.mapIndexed { i, mb ->
            FakeCam.Entry("/SD/Normal/F/${name(i)}", mb * 1_048_576L, "2026-03-01 10:%02d:00".format(i), "2560x1440", "60")
        }
        cam.folders["Normal"] = older
        cam.addFile("Normal", name(8), 24 * 1_048_576, "2026-03-01 10:08:00")
        cam.folders["Normal"] = cam.folders["Normal"]!!.map {
            if (it.path.endsWith(name(8))) FakeCam.Entry(it.path, it.size, it.time, "2560x1440", "60") else it
        }
        cam.folders["Event"] = emptyList()
        cam.folders["Parking"] = emptyList()
        cam.folders["Photo"] = emptyList()
        // One of them is on the phone already.
        Kit.ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE).edit().putStringSet("saved", setOf(name(5))).commit()
    }

    private fun connectedWithList() {
        Kit.connectThroughUi()
        Kit.until("the list to be loaded") { Kit.text("note").contains("Normal: 9 files") }
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
            if ('?' !in req.target) {
                reply.chunk = 256 * 1024
                reply.chunkDelayMs = 150                    // about fourteen seconds for the 24 MB clip
            }
        }
        Kit.launch().use {
            connectedWithList()
            shot("3-clips")
            Kit.tapText(".*${Regex.escape(name(8))}.*")
            Kit.tapText(".*${Regex.escape(name(7))}.*")
            shot("4-two-ticked")
            Kit.tap("download")
            Kit.until("the download to be well under way") { (Jobs.all.firstOrNull()?.done ?: 0) > 7 * 1_048_576 }
            shot("5-downloading")
        }
    }

    @Test
    fun shot4_dark() {
        Kit.grantAll()
        camera()
        Kit.shell("cmd uimode night yes")
        SystemClock.sleep(1500)
        Kit.launch().use {
            shot("6-dark-not-connected")
            connectedWithList()
            Kit.tapText(".*${Regex.escape(name(8))}.*")
            shot("7-dark-clips")
        }
    }

    @Test
    fun shot5_unfolded_andSideways() {
        Kit.grantAll()
        camera()
        Kit.launch().use {
            connectedWithList()
            Kit.tapText(".*${Regex.escape(name(8))}.*")
            Kit.shell("wm size 1812x2176")                  // roughly the inner screen of a fold
            Kit.shell("wm density 380")
            SystemClock.sleep(2500)
            Kit.until("the wide layout") { Kit.text("status").contains("MiVue_Test") }
            shot("8-unfolded")
            Kit.shell("wm size reset")
            Kit.shell("wm density reset")
            Kit.device.setOrientationLeft()
            SystemClock.sleep(2500)
            Kit.until("the sideways layout") { Kit.text("status").contains("MiVue_Test") }
            shot("9-sideways")
        }
    }
}
