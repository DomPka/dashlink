package com.dompka.dashlink

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The download service on a real Android: foreground service, wake lock, MediaStore. The camera is
 * FakeCam on 127.0.0.1 and the Wi-Fi link is FakeRadio; everything between those two is the real app.
 */
@RunWith(AndroidJUnit4::class)
class DownloadTest {
    private lateinit var cam: FakeCam
    private lateinit var radio: FakeRadio
    private val tag = System.nanoTime().toString()

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
        DownloadService.stopAll(Kit.ctx)
        Kit.onMain { Link.disconnect() }
        try {
            Kit.until("the service to stop", 20_000) { !DownloadService.alive && !DownloadService.working }
        } finally {
            cam.close()
            Kit.deleteStored(tag)
        }
    }

    private fun name(letter: String) = "T${tag}_$letter.MP4"

    private fun connect() {
        Kit.onMain { Link.connect(Kit.ctx, "MiVue", "12345678", false) }
        Kit.until("the pretend link to come up") { Link.ready }
    }

    private fun job(name: String): Job? = Jobs.all.lastOrNull { it.clip.name == name }

    private fun waitSaved(name: String, timeoutMs: Long = 60_000) =
        Kit.until("$name to be saved", timeoutMs) { job(name)?.state == Jobs.SAVED }

    private fun published(name: String): Kit.Stored =
        Kit.waitFor("$name to be visible in shared storage") { Kit.stored(tag).singleOrNull { it.name == name && !it.pending } }

    private fun assertServiceGone() {
        Kit.until("the service to finish and let go of its locks", 20_000) {
            !DownloadService.alive && !DownloadService.working && !DownloadService.locksHeld
        }
        assertNull(DownloadService.foregroundError)
        assertEquals("Android no longer lists the wake lock", emptyList<String>(), Kit.wakeLocksHeld())
    }

    // ---------------------------------------------------------------- start and stop

    @Test
    fun service_startedWithNothingToDo_stopsItself() {
        Kit.launch().use {
            val before = DownloadService.starts
            Kit.ctx.startForegroundService(Intent(Kit.ctx, DownloadService::class.java))
            Kit.until("the service to receive the start") { DownloadService.starts > before }
            assertServiceGone()
            assertTrue(Jobs.all.isEmpty())
        }
    }

    @Test
    fun service_startedAgainAndAgain_whileItIsStopping() {
        // The start/stop hand-over: a start that arrives just as the queue runs empty must neither be
        // lost nor end in "did not then call startForeground".
        Kit.launch().use {
            val before = DownloadService.starts
            repeat(40) {
                assertNull(DownloadService.start(Kit.ctx))
                if (it % 3 == 0) SystemClock.sleep(15)
            }
            Kit.until("every start to be delivered") { DownloadService.starts == before + 40 }
            assertServiceGone()
        }
    }

    @Test
    fun service_stoppedFromOutside_releasesItsLocks_andKeepsTheClip() {
        val a = name("A")
        cam.addFile("Normal", a, 12_000_000)
        cam.tweak = { _, reply ->
            reply.chunk = 20_000
            reply.chunkDelayMs = 40                         // about 24 seconds: long enough to be stopped half-way
        }
        connect()
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            Kit.until("the download to be under way") { (job(a)?.done ?: 0) > 100_000 }
            assertTrue("the service runs in the foreground", DownloadService.alive && DownloadService.locksHeld)
            assertNull(DownloadService.foregroundError)
        }
        // The screen is gone; the foreground service must not care.
        Kit.holds("the download goes on without the screen", 1500) {
            DownloadService.alive && DownloadService.locksHeld && job(a)?.state == Jobs.DOWNLOADING
        }
        // Not only the app's own bookkeeping: Android's lists of held locks.
        assertEquals("the wake lock is held while downloading", 1, Kit.wakeLocksHeld().size)
        Kit.wifiLocksHeld()?.let { assertEquals("the Wi-Fi lock is held while downloading", 1, it.size) }

        assertTrue(Kit.ctx.stopService(Intent(Kit.ctx, DownloadService::class.java)))
        Kit.until("the service to be gone", 20_000) { !DownloadService.alive && !DownloadService.working }
        assertFalse("wake lock and Wi-Fi lock are released", DownloadService.locksHeld)
        Kit.until("Android to stop listing the wake lock") { Kit.wakeLocksHeld().isEmpty() }
        Kit.until("Android to stop listing the Wi-Fi lock") { Kit.wifiLocksHeld()?.isEmpty() ?: true }
        assertEquals("the clip is still wanted", Jobs.QUEUED, job(a)!!.state)
        assertEquals("its unfinished file is kept", 1, Kit.stored(tag).count { it.pending })
    }

    // ---------------------------------------------------------------- the whole way, through the screen

    @Test
    fun listTickDownload_endsUpInSharedStorage_byteIdentical() {
        val a = name("A")
        val b = name("B")
        val dataA = cam.addFile("Normal", a, 700_000, "2026-03-01 10:00:00")
        val dataB = cam.addFile("Normal", b, 300_000, "2026-03-01 10:01:00")
        cam.addFile("Normal", name("C"), 1000, "2026-03-01 10:02:00")
        Kit.launch().use {
            Kit.connectThroughUi()
            Kit.until("the pretend link to come up") { Link.ready }
            Kit.until("the camera's name to be shown") { Kit.text("status").contains("MiVue_Test at 192.72.1.1") }
            Kit.until("the model to be shown") { Kit.text("status").contains("MiVue 956W, firmware 1.0.test") }
            Kit.until("the camera's full name to be kept instead of the typed start of it") {
                Kit.ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE).getString("ssid", null) == "MiVue_Test"
            }
            Kit.until("the list to be loaded") { Kit.text("progress").contains("Normal: 3 files") }

            Kit.tapText(".*${Regex.escape(a)}.*")
            Kit.tapText(".*${Regex.escape(b)}.*")
            Kit.tap("download")
            waitSaved(a)
            waitSaved(b)

            assertArrayEquals(dataA, Kit.bytes(published(a).uri))
            assertArrayEquals(dataB, Kit.bytes(published(b).uri))
            assertEquals("nothing unfinished is left", 0, Kit.stored(tag).count { it.pending })
            assertEquals("the third clip was not ticked", 2, Kit.stored(tag).size)
            assertServiceGone()
            Kit.until("the list to mark them as saved") { Kit.find(".*${Regex.escape(a)}.*✓ saved.*") != null }
            assertTrue(Kit.text("progress").contains("0 queued, 2 saved"))
            assertEquals("the camera was never asked two things at once", 1, cam.maxActive)
        }
    }

    @Test
    fun linkCutMidFile_thenBack_fileIsByteIdentical() {
        val a = name("A")
        val data = cam.addFile("Normal", a, 900_000)
        var cuts = 2
        cam.tweak = { req, reply -> if ('?' !in req.target && cuts-- > 0) reply.cutAfter = 250_000 }
        connect()
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            Kit.until("it to wait for the camera after the cut") { job(a)?.state == Jobs.WAITING }
            waitSaved(a)
            assertArrayEquals(data, Kit.bytes(published(a).uri))
            assertEquals(3, cam.fileRequests().size)
            assertTrue(cam.fileRequests()[1].rangeStart > 0)
            assertServiceGone()
        }
    }

    @Test
    fun cameraIgnoresRange_fileIsStartedOver_andStillIdentical() {
        val a = name("A")
        val data = cam.addFile("Normal", a, 600_000)
        cam.supportsRange = false
        var cuts = 1
        cam.tweak = { req, reply -> if ('?' !in req.target && cuts-- > 0) reply.cutAfter = 200_000 }
        connect()
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            waitSaved(a)
            assertArrayEquals(data, Kit.bytes(published(a).uri))
            assertEquals(1, Kit.stored(tag).size)
        }
    }

    @Test
    fun wifiDropsDuringDownload_reconnects_andFinishes() {
        val a = name("A")
        val data = cam.addFile("Normal", a, 6_000_000)
        cam.tweak = { _, reply ->
            reply.chunk = 20_000
            reply.chunkDelayMs = 40
        }
        connect()
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            Kit.until("the download to be under way") { (job(a)?.done ?: 0) > 200_000 }

            // The camera's Wi-Fi goes away: no route, and whatever was in flight dies with it.
            val real = Cam.opener
            Cam.opener = Cam.Opener { throw java.io.IOException("not connected to the camera") }
            radio.answer = false
            radio.drop()
            cam.hangUp()
            Kit.until("the link to be reported lost") { Link.state == LinkState.LOST || Link.state == LinkState.SEARCHING }
            Kit.until("the job to wait") { job(a)?.state == Jobs.WAITING }
            Kit.until("the screen to say so") { Kit.text("status").contains("dropped") }
            assertTrue("the name was learned, so it reconnects by itself", Link.reconnecting)

            // ...and comes back. Link asks again by itself (it knows the exact name by now).
            Kit.until("Link to ask for the camera again by itself") { radio.asked.size >= 2 }
            cam.tweak = null
            Cam.opener = real
            radio.answer = true
            radio.connectNow()
            Kit.until("the link to come up again", 30_000) { Link.ready }
            waitSaved(a)
            assertArrayEquals(data, Kit.bytes(published(a).uri))
            assertTrue(radio.asked.size >= 2)
            assertTrue(radio.asked.last().want.exact)
            assertServiceGone()
        }
    }

    // ---------------------------------------------------------------- Stop

    @Test
    fun stop_removesTheHalfDownloadedFile_andTheQueue() {
        val a = name("A")
        val b = name("B")
        cam.addFile("Normal", a, 12_000_000)
        cam.addFile("Normal", b, 1000)
        cam.tweak = { _, reply ->
            reply.chunk = 20_000
            reply.chunkDelayMs = 40
        }
        connect()
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"), cam.clip("/SD/Normal/F/$b"))))
            Kit.until("the download to be under way") { (job(a)?.done ?: 0) > 100_000 }
            assertEquals(1, Kit.stored(tag).count { it.pending })

            Kit.tap("stop")
            Kit.until("both jobs to be stopped") { job(a)?.state == Jobs.CANCELLED && job(b)?.state == Jobs.CANCELLED }
            Kit.until("nothing of it to be left in shared storage") { Kit.stored(tag).isEmpty() }
            assertServiceGone()
            assertEquals("", Kit.ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE).getString("queue", "?"))
        }
    }

    // ---------------------------------------------------------------- storage

    @Test
    fun sameClipTwice_isNotStoredTwice_butADifferentClipWithTheSameNameIs() {
        val a = name("A")
        val front = cam.addFile("Normal", a, 200_000)
        connect()
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            waitSaved(a)
            assertServiceGone()
            val requests = cam.fileRequests().size

            // The very same clip again: it is already on the phone.
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            Kit.until("the second job to finish") { Jobs.all.count { it.clip.name == a && it.state == Jobs.SAVED } == 2 }
            assertEquals("not downloaded again", requests, cam.fileRequests().size)
            assertEquals("not stored again", 1, Kit.stored(tag).size)
            assertServiceGone()

            // A different clip that happens to carry the same file name (rear camera): both are kept.
            val rear = cam.addFile("Normal", a, 150_000, rear = true)
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/R/$a"))))
            Kit.until("the rear clip to be saved") { Jobs.all.count { it.clip.name == a && it.state == Jobs.SAVED } == 3 }
            val files = Kit.waitFor("two visible files") { Kit.stored(tag).filter { !it.pending }.takeIf { it.size == 2 } }
            val contents = files.map { Kit.bytes(it.uri) }
            assertTrue(contents.any { it.contentEquals(front) })
            assertTrue(contents.any { it.contentEquals(rear) })
            assertEquals("each has its own name", 2, files.map { it.name }.toSet().size)
        }
    }

    @Test
    fun clipTheCameraNoLongerHas_failsAtOnce_andTheNextOneIsSaved() {
        val gone = name("GONE")
        val b = name("B")
        val data = cam.addFile("Normal", b, 100_000)
        connect()
        Kit.launch().use {
            val missing = Cam.Clip("/SD/Normal/F/$gone", 5_000_000, "2026-03-01 09:00:00", "", "")
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(missing, cam.clip("/SD/Normal/F/$b"))))
            waitSaved(b, 30_000)
            assertEquals(Jobs.FAILED, job(gone)!!.state)
            assertEquals("HttpException: HTTP 404", job(gone)!!.lastError)
            assertArrayEquals(data, Kit.bytes(published(b).uri))
            assertEquals("nothing left of the failed one", 1, Kit.stored(tag).size)
            Kit.until("the screen to count it") { Kit.text("progress").contains("1 saved, 1 failed") }
            assertServiceGone()
        }
    }

    @Test
    fun clipPublishedJustBeforeTheAppWasKilled_isLeftAlone() {
        val a = name("A")
        val data = cam.addFile("Normal", a, 150_000)
        connect()
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"))))
            waitSaved(a)
            assertServiceGone()
            val file = published(a)
            val requests = cam.fileRequests().size

            // What a new process finds if Android killed the old one between making the clip visible and
            // writing that down: the clip still queued, still pointing at the (now finished) file.
            val again = Job(cam.clip("/SD/Normal/F/$a"))
            again.dest = file.uri.toString()
            Jobs.all.add(again)
            assertNull(DownloadService.start(Kit.ctx))
            Kit.until("the restored job to be recognised as done") { again.state == Jobs.SAVED }
            assertNull(again.dest)
            assertServiceGone()
            assertEquals("not fetched again", requests, cam.fileRequests().size)
            val still = Kit.stored(tag)
            assertEquals("the finished clip was neither deleted nor doubled", listOf(file.uri), still.map { it.uri })
            assertFalse(still.single().pending)
            assertArrayEquals(data, Kit.bytes(file.uri))
        }
    }

    // ---------------------------------------------------------------- the app is killed

    @Test
    fun queueOfAKilledProcess_isBack_andContinuesTheSameFile() {
        val a = name("A")
        val b = name("B")
        val dataA = cam.addFile("Normal", a, 800_000)
        val dataB = cam.addFile("Normal", b, 100_000)
        val prefs = Kit.ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE)

        // First life: the link drops with part of A on the phone and B still queued.
        var first = true
        cam.tweak = { req, reply ->
            if ('?' !in req.target) {
                reply.cutAfter = if (first) 300_000 else 0
                first = false
            }
        }
        connect()
        val saved: String
        val part: Kit.Stored
        val had: Int
        Kit.launch().use {
            assertNull(DownloadService.enqueue(Kit.ctx, listOf(cam.clip("/SD/Normal/F/$a"), cam.clip("/SD/Normal/F/$b"))))
            Kit.until("A to be waiting with a part on the phone") { job(a)?.state == Jobs.WAITING && (job(a)?.done ?: 0) > 0 }
            saved = prefs.getString("queue", null)!!
            assertTrue("both clips are in the saved queue", saved.contains(a) && saved.contains(b))
            assertTrue("the service has noted that it is at work", prefs.getBoolean("working", false))
            part = Kit.stored(tag).single { it.pending }
            had = Kit.bytes(part.uri).size
            assertTrue("part on the phone: $had", had in 1..300_000)
        }

        // Android kills the process: nothing in memory survives, only what was written down.
        // (Simulated by stopping the service and wiping the in-memory queue; the files and the saved queue stay.)
        Kit.ctx.stopService(Intent(Kit.ctx, DownloadService::class.java))
        Kit.until("the service to be gone") { !DownloadService.alive && !DownloadService.working }
        Kit.onMain { Link.disconnect() }
        Jobs.all.clear()
        Jobs.paused = null
        prefs.edit().putString("queue", saved).putBoolean("working", true).commit()

        // Second life.
        DownloadService.restore(Kit.ctx)
        assertEquals(listOf(a, b), Jobs.all.map { it.clip.name })
        assertTrue(Jobs.interrupted)
        assertEquals(part.uri.toString(), job(a)!!.dest)
        cam.tweak = null
        cam.requests.clear()
        Kit.launch().use {
            Kit.until("the screen to say that downloads were cut off") { Kit.text("notice").contains("cut off") }
            // The camera comes back: the queue goes on by itself, from the byte it had.
            connect()
            waitSaved(a)
            waitSaved(b)
            assertArrayEquals(dataA, Kit.bytes(published(a).uri))
            assertArrayEquals(dataB, Kit.bytes(published(b).uri))
            assertEquals("A was continued, not started over", had.toLong(), cam.fileRequests().first().rangeStart)
            assertEquals("no stray files", 2, Kit.stored(tag).size)
            assertFalse(Jobs.interrupted)
            assertServiceGone()
        }
    }

    @Test
    fun leftovers_areSweptWhenTheServiceNextRuns() {
        // An unfinished file nobody will continue (a crash at the wrong moment, a job stopped before it ran).
        val a = name("A")
        val orphan = Job(Cam.Clip("/SD/Normal/F/$a", 1000, "", "", ""))
        val files = MediaFiles(Kit.ctx)
        files.open(orphan).use { it.append(ByteArray(500), 500) }
        assertEquals(1, Kit.stored(tag).count { it.pending })
        Kit.launch().use {
            assertNull(DownloadService.start(Kit.ctx))
            Kit.until("the leftover to be removed") { Kit.stored(tag).isEmpty() }
            assertServiceGone()
        }
    }
}
