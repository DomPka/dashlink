package com.dompka.dashlink

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Thumbnails made on the phone from real clips (MP4 from the emulator's encoder) and photos on FakeCam: they
 * appear, they come from the cache the second time without asking the camera, and nothing is fetched while a
 * download runs - they simply wait and come afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ThumbsTest {
    private lateinit var rig: MediaRig
    private val tag = System.nanoTime().toString()

    /** What each view's ready() said, by view. */
    private val answers = ConcurrentHashMap<ImageView, Boolean>()

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

    private fun views(n: Int): List<ImageView> {
        val out = ArrayList<ImageView>()
        Kit.onMain { repeat(n) { out.add(ImageView(Kit.ctx)) } }
        return out
    }

    private fun bind(view: ImageView, clip: Cam.Clip) {
        answers.remove(view)
        Kit.onMain { Thumbs.bind(view, clip) { ok -> answers[view] = ok } }
    }

    /** The picture [view] shows, read on the main thread. */
    private fun picture(view: ImageView): Bitmap? {
        var b: Bitmap? = null
        Kit.onMain { b = (view.drawable as? BitmapDrawable)?.bitmap }
        return b
    }

    private fun awaitPictures(views: List<ImageView>, what: String, timeoutMs: Long = 60_000) {
        Kit.until("$what: ${views.size} thumbnails (answers so far: ${answers.values})", timeoutMs) {
            views.all { answers[it] == true && picture(it) != null }
        }
    }

    private fun clips(n: Int): List<Cam.Clip> {
        val video = TestVideo.mp4(3, 640, 360, 25)
        return (0 until n).map { rig.clip("Normal", "T${tag}_$it.MP4", video, "2026-03-01 10:0$it:00", secs = "3") }
    }

    @Test
    fun clipsGetThumbnails_thenTheyComeFromTheCache() {
        val clips = clips(3)
        val photoBytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(TestVideo.picture(1280, 720, 0, 25), 1280, 720, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 85, it)
        }.toByteArray()
        val photo = rig.clip("Photo", "T${tag}_P.JPG", photoBytes, "2026-03-01 10:05:00", res = "1280x720", secs = "")
        val views = views(4)
        for ((v, c) in views.zip(clips + photo)) bind(v, c)
        awaitPictures(views, "first time")
        for (v in views) {
            val b = picture(v)!!
            assertTrue("a thumbnail-sized picture, not the whole frame: ${b.width}x${b.height}", b.width in 100..400 && b.height in 50..300)
        }
        val asked = rig.cam.fileRequests().size
        assertTrue("the clips were read", asked > 0)
        assertTrue("every read is a Range request", rig.cam.fileRequests().all { it.headers["range"] != null })

        // Again in new views: from memory, at once.
        val again = views(4)
        for ((v, c) in again.zip(clips + photo)) bind(v, c)
        awaitPictures(again, "from memory", 5_000)
        // And once memory is gone (as when Android asks for it back): from the disk cache.
        Thumbs.dropMemory()
        val third = views(4)
        for ((v, c) in third.zip(clips + photo)) bind(v, c)
        awaitPictures(third, "from the disk cache", 10_000)
        assertEquals("no new request to the camera for pictures it had made already", asked, rig.cam.fileRequests().size)
    }

    @Test
    fun whileADownloadRuns_nothingIsFetched_thenTheThumbnailsCome() {
        val clips = clips(2)
        val big = rig.clip("Normal", "T${tag}_D.MP4", ByteArray(3_000_000) { (it * 13).toByte() }, "2026-03-01 10:09:00")
        rig.slowDownload(big, 60)
        val views = views(2)
        for ((v, c) in views.zip(clips)) bind(v, c)
        Kit.holds("no thumbnail is fetched while the download runs", 4_000) {
            rig.cam.fileRequests().none { r -> clips.any { it.path == r.path } }
        }
        for (v in views) {
            assertNull("no answer yet: the request waits", answers[v])
            assertNull(picture(v))
        }
        DownloadService.stopAll(Kit.ctx)
        Kit.until("the download to stop", 20_000) { !DownloadService.working }
        awaitPictures(views, "after the download")
        for (v in views) assertNotNull(picture(v))
    }
}
