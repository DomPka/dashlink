package com.dompka.dashlink

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * One job from start to finish: what happens when the link drops, the camera refuses, the phone is
 * full or the user taps Stop. The camera is FakeCam (or a scripted stand-in), the phone's storage and
 * the clock are pretend ones, so ten minutes of waiting take no time at all.
 */
class RunnerTest {

    private class FakeStore : Runner.Store {
        val unfinished = LinkedHashMap<String, ByteArrayOutputStream>()
        val published = LinkedHashMap<String, ByteArray>()
        val already = HashSet<String>()
        var free = 1L shl 40
        var failOpen: String? = null
        var failPublish: String? = null
        var failWriteAt = -1L
        var opens = 0
        var discards = 0
        var openNow = 0
        private var seq = 0

        override fun freeBytes(): Long = free
        override fun has(clip: Cam.Clip): Boolean = clip.path in already

        override fun open(job: Job): Runner.Out {
            opens++
            failOpen?.let { throw Cam.StorageException(it) }
            val key = job.dest?.takeIf { it in unfinished } ?: "mem:${++seq}".also {
                job.dest = it
                unfinished[it] = ByteArrayOutputStream()
            }
            val buf = unfinished[key]!!
            openNow++
            return object : Runner.Out {
                override fun length(): Long = buf.size().toLong()
                override fun truncate(length: Long) {
                    val keep = buf.toByteArray().copyOf(length.toInt())
                    buf.reset()
                    buf.write(keep)
                }

                override fun append(buf2: ByteArray, count: Int) {
                    if (failWriteAt >= 0 && buf.size() + count > failWriteAt) throw IOException("write failed: ENOSPC (No space left on device)")
                    buf.write(buf2, 0, count)
                }

                override fun close() {
                    openNow--
                }
            }
        }

        override fun publish(job: Job) {
            failPublish?.let { throw Cam.StorageException(it) }
            published[job.clip.name] = unfinished.remove(job.dest!!)!!.toByteArray()
            job.dest = null
        }

        override fun discard(job: Job) {
            discards++
            job.dest?.let { unfinished.remove(it) }
            job.dest = null
        }
    }

    private class FakeClock : Runner.Clock {
        var time = 5_000_000L
        var sleeps = 0
        override fun now(): Long = time
        override fun sleep(ms: Long) {
            sleeps++
            time += ms
        }
    }

    private lateinit var cam: FakeCam
    private lateinit var store: FakeStore
    private lateinit var clock: FakeClock
    private val states = ArrayList<String>()
    private var link = LinkState.CONNECTED
    private var halt = false
    private val name = "FILE240101-120000F.MP4"
    private val path = "/SD/Normal/F/$name"

    @Before
    fun setUp() {
        cam = FakeCam()
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 3000
        store = FakeStore()
        clock = FakeClock()
        states.clear()
        link = LinkState.CONNECTED
        halt = false
    }

    @After
    fun tearDown() {
        cam.close()
        Cam.opener = Cam.Opener { throw IOException("no camera in this test") }
        Cam.downloadTimeoutMs = 15_000
        assertEquals("every file that was opened is closed again", 0, store.openNow)
    }

    private fun runner(source: Runner.Source = Runner.Source(Cam::download)) = Runner(
        source, store, clock, { link }, { halt }
    ) { job -> if (states.lastOrNull() != job.state) states.add(job.state) }

    private fun fileRequests() = cam.fileRequests().size

    // ---------------------------------------------------------------- the good path

    @Test
    fun savesAndPublishes() {
        val data = cam.addFile("Normal", name, 200_000)
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertArrayEquals(data, store.published[name])
        assertEquals(Jobs.SAVED, job.state)
        assertEquals(200_000L, job.done)
        assertNull(job.dest)
        assertNull(job.lastError)
        assertEquals(0L, job.bytesPerSec)
        assertEquals(listOf(Jobs.DOWNLOADING, Jobs.SAVING, Jobs.SAVED), states)
        assertTrue(store.unfinished.isEmpty())
        assertEquals(0, clock.sleeps)
    }

    @Test
    fun alreadyOnThePhone_isNotDownloadedAgain() {
        cam.addFile("Normal", name, 200_000)
        store.already.add(path)
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertEquals(Jobs.SAVED, job.state)
        assertTrue("the camera was not asked", cam.requests.isEmpty())
        assertTrue("no second copy", store.published.isEmpty() && store.unfinished.isEmpty())
        assertEquals(0, store.opens)
    }

    // ---------------------------------------------------------------- the link

    @Test
    fun linkDrops_itWaitsAndContinuesFromTheSameByte() {
        val data = cam.addFile("Normal", name, 300_000)
        var cuts = 2
        cam.tweak = { _, reply -> if (cuts-- > 0) reply.cutAfter = 80_000 }
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertArrayEquals("byte-identical after two drops", data, store.published[name])
        assertEquals(3, fileRequests())
        assertNull(cam.fileRequests()[0].headers["range"])
        assertTrue(cam.fileRequests()[1].headers["range"]!!.startsWith("bytes="))
        assertTrue(cam.fileRequests()[2].rangeStart > cam.fileRequests()[1].rangeStart)
        assertEquals("waited 3 s after each drop", 2, clock.sleeps)
        assertEquals(
            listOf(Jobs.DOWNLOADING, Jobs.WAITING, Jobs.DOWNLOADING, Jobs.WAITING, Jobs.DOWNLOADING, Jobs.SAVING, Jobs.SAVED),
            states
        )
        assertNull(job.lastError)
    }

    @Test
    fun cameraOutOfReach_pausesAfterTenMinutes_andKeepsThePart() {
        var attempts = 0
        val source = Runner.Source { _, sink, _, progress ->
            attempts++
            if (attempts == 1) {
                sink.append(ByteArray(1000), 1000)
                progress(1000, 5000)
            }
            clock.time += 15_000                        // every attempt ends in a 15 s time-out
            throw IOException("timeout")
        }
        val job = Job(Cam.Clip(path, 5000, "", "", ""))
        assertEquals(Runner.Result.PAUSED, runner(source).run(job))
        assertEquals("back in the queue, not failed", Jobs.QUEUED, job.state)
        assertEquals("IOException: timeout", job.lastError)
        assertEquals(1000, store.unfinished[job.dest]!!.size())
        assertEquals(0, store.discards)
        val waited = clock.time - 5_000_000L
        assertTrue("gave up after about ten minutes, not before: $waited", waited in Runner.AWAY_MS..Runner.AWAY_MS + 60_000)
        assertTrue("$attempts attempts", attempts in 20..60)
        assertTrue("it was shown as waiting in between", Jobs.WAITING in states)
        assertEquals(Jobs.QUEUED, states.last())
    }

    @Test
    fun linkGoneForGood_pausesAtOnce() {
        // The user disconnected, or the link gave up reconnecting: nothing will bring the camera back by itself.
        Cam.opener = Cam.Opener { throw IOException("not connected to the camera") }
        for (state in listOf(LinkState.IDLE, LinkState.NOT_FOUND)) {
            link = state
            clock.sleeps = 0
            val job = Job(Cam.Clip(path, 5000, "", "", ""))
            assertEquals(Runner.Result.PAUSED, runner().run(job))
            assertEquals(Jobs.QUEUED, job.state)
            assertEquals(0, clock.sleeps)
            assertEquals("IOException: not connected to the camera", job.lastError)
        }
    }

    @Test
    fun linkBeingReestablished_isWaitedFor_howeverLongItTakes() {
        // The Wi-Fi dropped and Link is asking for it again. That has its own limit (twenty attempts); as
        // long as it is still trying, the download waits - also past the ten minutes a silent camera gets.
        var attempts = 0
        val source = Runner.Source { _, sink, _, progress ->
            attempts++
            clock.time += 1000
            if (clock.time - 5_000_000L < 25 * 60_000L) {
                link = if (attempts % 2 == 0) LinkState.LOST else LinkState.SEARCHING
                throw IOException("not connected to the camera")
            }
            link = LinkState.CONNECTED                  // back after twenty-five minutes
            sink.append(ByteArray(1000), 1000)
            progress(1000, 1000)
        }
        link = LinkState.LOST
        val job = Job(Cam.Clip(path, 1000, "", "", ""))
        assertEquals(Runner.Result.SAVED, runner(source).run(job))
        assertTrue(Jobs.WAITING in states)
        assertTrue("$attempts attempts", attempts > 300)

        // And when the link then gives up, the queue stops at the next attempt.
        link = LinkState.SEARCHING
        var tries = 0
        val dead = Runner.Source { _, _, _, _ ->
            if (++tries == 5) link = LinkState.NOT_FOUND
            throw IOException("not connected to the camera")
        }
        val other = Job(Cam.Clip("/SD/Normal/F/OTHER.MP4", 1000, "", "", ""))
        assertEquals(Runner.Result.PAUSED, runner(dead).run(other))
        assertEquals(5, tries)
    }

    @Test
    fun silentCamera_clockStartsWhenTheLinkIsBack() {
        // Reconnecting took eight minutes, then the camera is connected but sends nothing: it still gets
        // its full ten minutes from that moment, not two.
        val source = Runner.Source { _, _, _, _ ->
            clock.time += 15_000
            link = if (clock.time - 5_000_000L < 8 * 60_000L) LinkState.SEARCHING else LinkState.CONNECTED
            throw IOException("timeout")
        }
        link = LinkState.SEARCHING
        val job = Job(Cam.Clip(path, 1000, "", "", ""))
        assertEquals(Runner.Result.PAUSED, runner(source).run(job))
        // Eight minutes of reconnecting plus ten of silence, give or take one 18-second round.
        val waited = clock.time - 5_000_000L
        assertTrue("$waited", waited in 18 * 60_000L - 20_000..18 * 60_000L + 20_000)
    }

    @Test
    fun slowButSteadyProgress_isNeverMistakenForAStall() {
        // A terrible link: every attempt gets 10 more bytes and then drops. That is still progress.
        var attempts = 0
        val source = Runner.Source { _, sink, _, progress ->
            attempts++
            sink.append(ByteArray(10), 10)
            progress(sink.length(), 1000)
            clock.time += 15_000
            if (sink.length() < 1000) throw IOException("dropped")
        }
        val job = Job(Cam.Clip(path, 1000, "", "", ""))
        assertEquals(Runner.Result.SAVED, runner(source).run(job))
        assertEquals(100, attempts)
        assertEquals(1000, store.published[name]!!.size)
        assertTrue("far longer than the ten-minute limit", clock.time - 5_000_000L > 2 * Runner.AWAY_MS)
    }

    @Test
    fun serviceGoingAway_stopsWithoutLosingThePart() {
        cam.addFile("Normal", name, 400_000)
        cam.tweak = { _, reply -> reply.chunk = 20_000 }
        val job = Job(cam.clip(path))
        val source = Runner.Source { clip, sink, cancelled, progress ->
            Cam.download(clip, sink, cancelled) { have, total ->
                progress(have, total)
                if (have >= 60_000) halt = true
            }
        }
        assertEquals(Runner.Result.PAUSED, runner(source).run(job))
        assertEquals(Jobs.QUEUED, job.state)
        assertFalse(job.cancel)
        assertEquals(0, store.discards)
        assertTrue(store.unfinished[job.dest]!!.size() in 60_000 until 400_000)
        // And when it is already going away before the job starts, the job is not touched at all.
        val other = Job(cam.clip(path))
        assertEquals(Runner.Result.PAUSED, runner().run(other))
        assertNull(other.dest)
    }

    // ---------------------------------------------------------------- the camera says no

    @Test
    fun cameraNoLongerHasTheClip_failsAtOnce_andTheQueueMovesOn() {
        // Loop recording has overwritten it since the list was loaded.
        val job = Job(Cam.Clip("/SD/Normal/F/GONE.MP4", 1000, "", "", ""))
        assertEquals(Runner.Result.FAILED, runner().run(job))
        assertEquals(Jobs.FAILED, job.state)
        assertEquals("HttpException: HTTP 404", job.lastError)
        assertEquals("asked once, not for ten minutes", 1, cam.requests.size)
        assertEquals(0, clock.sleeps)
        assertTrue("nothing left behind", store.unfinished.isEmpty())
        assertNull(job.dest)
    }

    @Test
    fun cameraKeepsAnsweringWithAnError_givesUpAfterAFewTries() {
        cam.addFile("Normal", name, 1000)
        for (code in listOf(500, 503, 408, 429)) {
            cam.requests.clear()
            clock.sleeps = 0
            cam.override = { FakeCam.Reply(code) }
            val job = Job(cam.clip(path))
            assertEquals("$code", Runner.Result.FAILED, runner().run(job))
            assertEquals(Runner.MAX_REFUSALS + 1, cam.requests.size)
            assertEquals(Runner.MAX_REFUSALS, clock.sleeps)
            assertEquals("HttpException: HTTP $code", job.lastError)
        }
        assertTrue(store.unfinished.isEmpty())
    }

    @Test
    fun cameraErrorThatPasses_isSurvived() {
        val data = cam.addFile("Normal", name, 50_000)
        var errors = 3
        cam.tweak = { _, reply -> if (errors-- > 0) reply.status = 503 }
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertArrayEquals(data, store.published[name])
        assertEquals(3, clock.sleeps)
    }

    @Test
    fun cameraThatRejectsEveryResume_cannotMakeItDownloadForEver() {
        cam.addFile("Normal", name, 1000)
        cam.override = { if ('?' in it.target) null else FakeCam.Reply(416) }
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.FAILED, runner().run(job))
        assertEquals(Runner.MAX_RESTARTS + 1, cam.requests.size)
        assertTrue(job.lastError!!.contains("RestartException"))
        assertTrue(store.unfinished.isEmpty())
    }

    @Test
    fun listedSizeDoesNotMatchTheFile_isSavedOnce() {
        // Version 0.1 trusted the size in the list: "stream ended at 800 of 1000", ask to resume, 416,
        // delete, download again... for ever. The transfer is believed now.
        val data = cam.addFile("Normal", name, 800)
        val job = Job(Cam.Clip(path, 1000, "", "", ""))
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertArrayEquals(data, store.published[name])
        assertEquals(1, fileRequests())
        assertEquals(0, clock.sleeps)
    }

    // ---------------------------------------------------------------- the phone's storage

    @Test
    fun phoneFullWhileWriting_pausesTheQueue_insteadOfRetrying() {
        cam.addFile("Normal", name, 300_000)
        store.failWriteAt = 100_000
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.STORAGE, runner().run(job))
        assertEquals("still wanted: it continues when there is room", Jobs.QUEUED, job.state)
        assertTrue(job.lastError!!.contains("ENOSPC"))
        assertEquals("asking the camera again would not help", 1, fileRequests())
        assertEquals(0, clock.sleeps)
        assertEquals(0, store.discards)

        // Room again: the same job continues from what it had.
        store.failWriteAt = -1
        val had = store.unfinished[job.dest]!!.size()
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertEquals(300_000, store.published[name]!!.size)
        // (How much arrived before the phone was full depends on timing; with nothing kept there is nothing to resume.)
        assertEquals(if (had > 0) had.toLong() else -1L, cam.fileRequests()[1].rangeStart)
    }

    @Test
    fun notEnoughFreeSpace_isNoticedBeforeAnythingIsDownloaded() {
        store.free = 100L * 1024 * 1024
        val job = Job(Cam.Clip(path, 900L * 1024 * 1024, "", "", ""))
        assertEquals(Runner.Result.STORAGE, runner().run(job))
        assertEquals(Jobs.QUEUED, job.state)
        assertTrue(job.lastError!!, job.lastError!!.contains("not enough free space on the phone: 900 MB needed, 100 MB free"))
        assertTrue(cam.requests.isEmpty())
        // A clip that fits is not held back by the safety margin's rounding.
        val small = cam.addFile("Normal", "S.MP4", 1000)
        store.free = Runner.SPARE_BYTES + 1000
        assertEquals(Runner.Result.SAVED, runner().run(Job(cam.clip("/SD/Normal/F/S.MP4"))))
        assertArrayEquals(small, store.published["S.MP4"])
    }

    @Test
    fun sharedStorageRefusesTheFile_pausesTheQueue() {
        cam.addFile("Normal", name, 1000)
        store.failOpen = "could not create $name in shared storage"
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.STORAGE, runner().run(job))
        assertEquals(Jobs.QUEUED, job.state)
        assertTrue(job.lastError!!.contains("could not create"))
        assertTrue(cam.requests.isEmpty())
        assertEquals(0, clock.sleeps)
    }

    @Test
    fun publishingFails_thenWorks_withoutDownloadingAgain() {
        val data = cam.addFile("Normal", name, 100_000)
        store.failPublish = "could not publish $name"
        val job = Job(cam.clip(path))
        assertEquals(Runner.Result.STORAGE, runner().run(job))
        assertEquals(Jobs.QUEUED, job.state)
        assertEquals(100_000, store.unfinished[job.dest]!!.size())
        assertEquals(1, fileRequests())

        store.failPublish = null
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertArrayEquals(data, store.published[name])
        assertEquals("the finished part was simply published", 1, fileRequests())
    }

    // ---------------------------------------------------------------- Stop

    @Test
    fun stoppedBeforeItStarted() {
        cam.addFile("Normal", name, 1000)
        val job = Job(cam.clip(path))
        job.cancel = true
        assertEquals(Runner.Result.CANCELLED, runner().run(job))
        assertEquals(Jobs.CANCELLED, job.state)
        assertTrue(cam.requests.isEmpty())
        assertEquals(0, store.opens)
    }

    @Test
    fun stoppedMidDownload_leavesNoPartialFileBehind() {
        cam.addFile("Normal", name, 400_000)
        cam.tweak = { _, reply -> reply.chunk = 20_000 }
        val job = Job(cam.clip(path))
        val source = Runner.Source { clip, sink, cancelled, progress ->
            Cam.download(clip, sink, cancelled) { have, total ->
                progress(have, total)
                if (have >= 60_000) job.cancel = true          // the user taps Stop
            }
        }
        assertEquals(Runner.Result.CANCELLED, runner(source).run(job))
        assertEquals(Jobs.CANCELLED, job.state)
        assertTrue("the half-downloaded file is gone", store.unfinished.isEmpty())
        assertNull(job.dest)
        assertTrue(store.published.isEmpty())
        assertEquals(1, store.discards)
    }

    @Test
    fun stoppedWhileWaitingForTheCamera() {
        cam.addFile("Normal", name, 100_000)
        cam.tweak = { _, reply -> reply.cutAfter = 10_000 }
        val job = Job(cam.clip(path))
        val r = Runner(Runner.Source(Cam::download), store, object : Runner.Clock {
            override fun now(): Long = clock.now()
            override fun sleep(ms: Long) {
                job.cancel = true                               // Stop arrives during the 3 s wait
            }
        }, { LinkState.CONNECTED }, { false }) { }
        assertEquals(Runner.Result.CANCELLED, r.run(job))
        assertTrue(store.unfinished.isEmpty())
        assertEquals(1, fileRequests())
    }

    // ---------------------------------------------------------------- what the screen is told

    @Test
    fun progressAndSpeedAreReported() {
        val reported = ArrayList<Long>()
        val source = Runner.Source { _, sink, _, progress ->
            repeat(10) {
                sink.append(ByteArray(1_000_000), 1_000_000)
                clock.time += 500
                progress(sink.length(), 10_000_000)
            }
        }
        val job = Job(Cam.Clip(path, 10_000_000, "", "", ""))
        val r = Runner(source, store, clock, { LinkState.CONNECTED }, { false }) { j ->
            if (j.state == Jobs.DOWNLOADING && j.bytesPerSec > 0) reported.add(j.bytesPerSec)
        }
        assertEquals(Runner.Result.SAVED, r.run(job))
        assertTrue("about once a second, not on every chunk: ${reported.size}", reported.size in 4..5)
        assertTrue(reported.all { it == 2_000_000L })
        assertEquals(10_000_000L, job.total)
        assertEquals(0L, job.bytesPerSec)
    }

    @Test
    fun sizeUnknown_totalComesFromTheCamera() {
        cam.addFile("Normal", name, 70_000)
        val job = Job(Cam.Clip(path, -1, "", "", ""))
        assertEquals(-1L, job.total)
        assertEquals(Runner.Result.SAVED, runner().run(job))
        assertEquals(70_000L, job.total)
        assertEquals(70_000L, job.done)
    }
}
