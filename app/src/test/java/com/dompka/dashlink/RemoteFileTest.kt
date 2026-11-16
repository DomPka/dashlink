package com.dompka.dashlink

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * RemoteFile against FakeCam: reads anywhere in a clip, what MediaPlayer's way of reading costs in requests,
 * replies that are not what was asked for, a camera that stalls, close(), and sharing the camera with others.
 */
class RemoteFileTest {
    private lateinit var cam: FakeCam
    private val opened = ArrayList<RemoteFile>()
    private val name = "FILE240101-120000F.MP4"
    private val path = "/SD/Normal/F/$name"

    @Before
    fun setUp() {
        cam = FakeCam()
        Cam.opener = cam.opener()
    }

    @After
    fun tearDown() {
        for (f in opened) f.close()
        // However a test ended, once its files are closed the camera is free for everyone else.
        Cam.session(waitMs = 2000) { }
        cam.close()
        Cam.opener = Cam.Opener { throw IOException("no camera in this test") }
        RemoteFile.deadlineMs = 8_000
        assertTrue("something other than a read was sent: ${cam.violations.map { it.target }}", cam.violations.isEmpty())
    }

    private fun remote(size: Long, sequential: Boolean, file: String = path) =
        RemoteFile(file, size, sequential).also { opened.add(it) }

    /** Reads the way a player does: again and again, until it has what it asked for or the file ends. */
    private fun readFully(f: RemoteFile, position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = f.readAt(position + done, out, done, length - done)
            if (n < 0) break
            assertTrue("a read returned nothing at ${position + done}", n > 0)
            done += n
        }
        return out.copyOf(done)
    }

    private fun assertReads(f: RemoteFile, data: ByteArray, position: Long, length: Int) {
        val end = minOf(data.size.toLong(), position + length).toInt()
        assertArrayEquals("$length bytes at $position", data.copyOfRange(position.toInt(), end), readFully(f, position, length))
    }

    private fun waitUntil(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("gave up waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun ranges() = cam.fileRequests().map { it.headers["range"] }

    // ---------------------------------------------------------------- reading

    @Test(timeout = 30_000)
    fun randomReads_anywhere_matchTheFile() {
        val data = cam.addFile("Normal", name, 3_000_000)
        for (sequential in listOf(false, true)) {
            cam.requests.clear()
            val f = remote(data.size.toLong(), sequential)
            val rnd = Random(if (sequential) 1L else 2L)
            repeat(300) { assertReads(f, data, rnd.nextInt(data.size).toLong(), 1 + rnd.nextInt(70_000)) }
            if (!sequential) {
                // Thumbnails ask for aligned blocks with an explicit end, each block once: the whole clip fits in memory.
                val asked = cam.fileRequests()
                assertTrue("${ranges()}", asked.size <= (data.size + RANDOM_BLOCK - 1) / RANDOM_BLOCK)
                assertEquals(asked.size, asked.map { it.rangeStart }.toSet().size)
                for (r in asked) {
                    assertEquals(0L, r.rangeStart % RANDOM_BLOCK)
                    assertEquals(minOf(r.rangeStart + RANDOM_BLOCK, data.size.toLong()) - 1, r.rangeEnd)
                }
                assertEquals("requests overlapped", 1, cam.maxActive)
            }
        }
    }

    @Test
    fun aReadAcrossABlockBoundary_getsBothSides() {
        val data = cam.addFile("Normal", name, 1_000_000)
        for ((sequential, block) in listOf(false to RANDOM_BLOCK, true to STREAM_BLOCK)) {
            val f = remote(data.size.toLong(), sequential)
            val at = block - 10L
            // Only the block that holds the first byte is waited for: the answer may stop at its end, never before.
            val buf = ByteArray(100)
            val n = f.readAt(at, buf, 0, 100)
            if (sequential) assertTrue("$n", n in 10..100) else assertEquals("nothing is read ahead for thumbnails", 10, n)
            assertArrayEquals(data.copyOfRange(at.toInt(), at.toInt() + n), buf.copyOf(n))
            assertReads(f, data, at, 100)
            // With both blocks in memory one call returns all of it, at any offset in the caller's buffer.
            val big = ByteArray(300)
            assertEquals(100, f.readAt(at, big, 150, 100))
            assertArrayEquals(data.copyOfRange(at.toInt(), at.toInt() + 100), big.copyOfRange(150, 250))
            assertTrue("the rest of the buffer is left alone", (big.copyOf(150) + big.copyOfRange(250, 300)).all { it == 0.toByte() })
        }
    }

    @Test
    fun theEndOfTheFile_isMinusOne_andNoReadIsEmpty() {
        val size = 300_001                      // not a multiple of either block size
        val data = cam.addFile("Normal", name, size)
        for (sequential in listOf(false, true)) {
            val f = remote(size.toLong(), sequential)
            val buf = ByteArray(10)
            assertEquals(-1, f.readAt(size.toLong(), buf, 0, 10))
            assertEquals(-1, f.readAt(size + 1_000_000L, buf, 0, 10))
            assertEquals(1, f.readAt(size - 1L, buf, 0, 10))
            assertEquals(data[size - 1], buf[0])
            for (pos in listOf(0L, 1L, 131_071L, 131_072L, 262_143L, 262_144L, 299_990L)) {
                val n = f.readAt(pos, buf, 0, 10)
                assertTrue("$n at $pos", n in 1..10)
                assertArrayEquals(data.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
            }
            assertEquals("nothing asked for, nothing given", 0, f.readAt(5, buf, 0, 0))
            assertEquals(size.toLong(), f.length())
            assertEquals(size.toLong(), f.bufferedUntil(size - 5L))
        }
        assertTrue("the camera was asked for bytes past the end: ${ranges()}", cam.fileRequests().all { it.rangeStart < size })
        // An empty clip: nothing to read, and no read waits for bytes that will never come.
        cam.files["/SD/Normal/F/EMPTY.MP4"] = ByteArray(0)
        val empty = remote(0, sequential = false, file = "/SD/Normal/F/EMPTY.MP4")
        assertEquals(0L, empty.length())
        assertEquals(-1, empty.readAt(0, ByteArray(10), 0, 10))
    }

    @Test
    fun bufferedUntil_isTheEndOfWhatIsInMemory_fromThere() {
        val data = cam.addFile("Normal", name, 2_000_000)
        val f = remote(data.size.toLong(), sequential = false)
        assertEquals("nothing yet", 1000L, f.bufferedUntil(1000))
        assertReads(f, data, 2L * RANDOM_BLOCK, 10)
        assertReads(f, data, 3L * RANDOM_BLOCK, 10)
        assertEquals(4L * RANDOM_BLOCK, f.bufferedUntil(2L * RANDOM_BLOCK + 5))
        assertEquals("a gap ends it", 1000L, f.bufferedUntil(1000))
        assertReads(f, data, 7L * RANDOM_BLOCK, 10)
        assertEquals("never past the end of the file", data.size.toLong(), f.bufferedUntil(7L * RANDOM_BLOCK))
    }

    @Test(timeout = 60_000)
    fun theWayMediaPlayerReads_headThenIndexThenPlayback_takesThreeRequests() {
        val size = 40 * MB + 12_345
        val data = cam.addFile("Normal", name, size)
        val f = remote(size.toLong(), sequential = true)
        // The head: the first boxes of the file.
        assertReads(f, data, 0, 8)
        assertReads(f, data, 8, 4096)
        // The index, which an MP4 written while recording has at its end, read in pieces up to the last byte.
        val index = size - 300_000L
        var p = index
        while (p < size) {
            assertReads(f, data, p, 16_384)
            p += 16_384
        }
        // Then playback from the start of the media data, in steps of 64 KB, well past what the cache holds.
        p = 40
        while (p < 30 * MB) {
            assertReads(f, data, p, 65_536)
            p += 65_536
        }
        // More than the whole cache has been through memory since, and the index is still there.
        val before = cam.fileRequests().size
        assertReads(f, data, index, 300_000)
        assertEquals("the index was fetched again", before, cam.fileRequests().size)
        while (p < size) {
            assertReads(f, data, p, 65_536)
            p += 65_536
        }
        // From the start, reading ahead; the tail on its own; and on from where the first one had got to.
        val asked = cam.fileRequests()
        assertTrue("${ranges()}", asked.size <= 3)
        assertEquals("bytes=0-", asked[0].headers["range"])
        assertEquals("the tail is asked for with an explicit end", size - 1L, asked[1].rangeEnd)
        assertTrue("${asked[1].rangeStart}", asked[1].rangeStart in index - STREAM_BLOCK..index)
        assertEquals(-1L, asked[2].rangeEnd)
        assertEquals(0L, asked[2].rangeStart % STREAM_BLOCK)
    }

    @Test(timeout = 30_000)
    fun readAhead_hasTheNextMegabytesReady_beforeThePlayerAsks() {
        val data = cam.addFile("Normal", name, 12 * MB)
        val f = remote(data.size.toLong(), sequential = true)
        assertReads(f, data, 0, 65_536)
        waitUntil("the reader to run ahead") { f.bufferedUntil(65_536) >= 65_536 + 5 * MB }
        val ahead = f.bufferedUntil(65_536)
        // Now the camera stops answering altogether, and a read that waited for it would fail at once:
        // what the player reads next must already be on the phone.
        cam.override = { FakeCam.Reply(503) }
        cam.hangUp()
        RemoteFile.deadlineMs = 200
        var p = 65_536L
        while (p + 65_536 <= ahead) {
            assertReads(f, data, p, 65_536)
            p += 65_536
        }
    }

    @Test
    fun anUnknownSize_isLearnedFromTheFirstReply() {
        val data = cam.addFile("Normal", name, 777_777)
        for (sequential in listOf(false, true)) {
            cam.requests.clear()
            val f = remote(-1, sequential)
            assertEquals(777_777L, f.length())
            assertEquals("one request: ${ranges()}", 1, cam.fileRequests().size)
            assertEquals(0L, cam.fileRequests()[0].rangeStart)
            assertReads(f, data, 700_000, 100_000)
            assertEquals(-1, f.readAt(777_777, ByteArray(1), 0, 1))
        }
        // A camera that gives no total ("bytes a-b/*"): length() cannot learn it and says so; reading still works.
        cam.tweak = { _, reply -> reply.headers["Content-Range"]?.let { reply.headers["Content-Range"] = it.substringBefore('/') + "/*" } }
        val f = remote(-1, sequential = false)
        try {
            f.length()
            fail("a length nobody said")
        } catch (e: IOException) {
            assertEquals("the camera did not say how long the clip is", e.message)
        }
        assertReads(f, data, 1000, 1000)
        // Its last block ends early, the way the end of a file does: from then on the end is known.
        assertReads(f, data, 777_000, 5000)
        assertEquals(-1, f.readAt(777_777, ByteArray(1), 0, 1))
        assertEquals(777_777L, f.length())
    }

    // ---------------------------------------------------------------- replies that are not what was asked for

    @Test
    fun aCameraThatIgnoresTheEndOfTheRange_isCutOffOnThePhone() {
        val data = cam.addFile("Normal", name, 2_000_000)
        cam.ignoreRangeEnd = true
        val f = remote(data.size.toLong(), sequential = false)
        for (pos in listOf(1_500_000L, 10L, 900_000L, 1_999_990L, 1_500_100L, 20L)) assertReads(f, data, pos, 5000)
        // Each block was asked for with an end; each reply ran on to the end of the file and was dropped after it.
        val asked = cam.fileRequests()
        assertEquals("${ranges()}", 4, asked.size)
        assertTrue("${ranges()}", asked.all { it.rangeEnd == minOf(it.rangeStart + RANDOM_BLOCK, data.size.toLong()) - 1 })
    }

    @Test
    fun aCameraThatIgnoresRange_isReadFromTheStartOnly() {
        val data = cam.addFile("Normal", name, 3_000_000)
        cam.supportsRange = false
        // Thumbnails: the first block is taken from the reply with the whole clip, which is then dropped.
        val thumbs = remote(data.size.toLong(), sequential = false)
        assertReads(thumbs, data, 0, 10_000)
        try {
            thumbs.readAt(2_000_000, ByteArray(10), 0, 10)
            fail("there is no way to that byte")
        } catch (e: CannotSeekException) {
            assertEquals("the camera cannot seek in this clip", e.message)
        }
        assertEquals("having seen it once, the phone does not ask again", 1, cam.fileRequests().size)
        // Playback from the start: the whole clip, as one plain stream.
        val play = remote(data.size.toLong(), sequential = true)
        var p = 0L
        while (p < data.size) {
            assertReads(play, data, p, 65_536)
            p += 65_536
        }
        assertEquals(2, cam.fileRequests().size)
        // A player that starts anywhere else cannot be served.
        val seek = remote(data.size.toLong(), sequential = true)
        try {
            seek.readAt(2_500_000, ByteArray(10), 0, 10)
            fail("there is no way to that byte")
        } catch (e: CannotSeekException) {
            // expected
        }
    }

    @Test
    fun repliesThatAreNotWhatWasAskedFor_neverReachTheCaller() {
        val data = cam.addFile("Normal", name, 1_000_000)
        fun partial(header: String?, from: Long) = FakeCam.Reply(206, data.copyOfRange(from.toInt(), data.size)).also { r ->
            if (header != null) r.headers["Content-Range"] = header
        }
        val cases = listOf<Pair<String, (FakeCam.Request) -> FakeCam.Reply>>(
            // Bytes from somewhere else: taking them would show the wrong part of the clip.
            "instead of" to { req -> partial("bytes ${req.rangeStart + 1000}-999999/1000000", req.rangeStart + 1000) },
            // No Content-Range at all: where these bytes belong cannot be known.
            "did not say" to { req -> partial(null, req.rangeStart) },
            "makes no sense" to { req -> partial("pieces ${req.rangeStart}-999999", req.rangeStart) },
            "makes no sense" to { req -> partial("bytes */1000000", req.rangeStart) },
            "makes no sense" to { req -> partial("bytes ${req.rangeStart}-999999/900000", req.rangeStart) },
            "HTTP 416" to { _ -> FakeCam.Reply(416).also { it.headers["Content-Range"] = "bytes */1000000" } },
            "HTTP 404" to { _ -> FakeCam.Reply(404, "no".toByteArray()) },
            "HTTP 500" to { _ -> FakeCam.Reply(500, "no".toByteArray()) },
            // A redirect is an answer, not an instruction.
            "HTTP 302" to { _ -> FakeCam.Reply(302).also { it.headers["Location"] = path } },
        )
        for (sequential in listOf(false, true)) {
            for ((message, reply) in cases) {
                cam.override = { if ('?' in it.target) null else reply(it) }
                val f = remote(data.size.toLong(), sequential)
                val buf = ByteArray(100) { 42 }
                try {
                    f.readAt(500_000, buf, 0, 100)
                    fail("$message: the reply was taken")
                } catch (e: IOException) {
                    assertTrue("$message: ${e.message}", e.message!!.contains(message))
                }
                assertTrue("$message: something reached the caller's buffer", buf.all { it == 42.toByte() })
            }
        }
    }

    @Test(timeout = 30_000)
    fun aStreamCutHalfWay_isPickedUpAgain_withTheRightBytes() {
        val data = cam.addFile("Normal", name, 3_000_000)
        for (hard in listOf(true, false)) {
            cam.requests.clear()
            var cuts = 1
            cam.tweak = { req, reply ->
                if ('?' !in req.target && cuts-- > 0) {
                    reply.cutAfter = 700_000
                    reply.cutHard = hard
                    // In pieces, so that the phone has the reply's head well before the cut: a reset that came
                    // first would be a request that failed, not a stream that broke.
                    reply.chunk = 100_000
                    reply.chunkDelayMs = 20
                }
            }
            val f = remote(data.size.toLong(), sequential = true)
            var p = 0L
            while (p < data.size) {
                assertReads(f, data, p, 65_536)
                p += 65_536
            }
            // A reset can take bytes with it that had already arrived, so the second request may start anywhere
            // up to the cut: at the first block that was not complete.
            val asked = cam.fileRequests()
            assertEquals("hard=$hard: ${ranges()}", 2, asked.size)
            assertEquals(0L, asked[1].rangeStart % STREAM_BLOCK)
            assertTrue("picked up no later than the cut: ${asked[1].rangeStart}", asked[1].rangeStart in 0..700_000)
        }
    }

    @Test(timeout = 30_000)
    fun aStreamThatKeepsBreaking_failsTheRead_insteadOfAskingForEver() {
        val data = cam.addFile("Normal", name, 3_000_000)
        for (hard in listOf(true, false)) {
            // Every reply breaks off before a single block is complete (a moment after its head, as above).
            cam.tweak = { req, reply ->
                if ('?' !in req.target) {
                    reply.cutAfter = 10_000
                    reply.cutHard = hard
                    reply.chunk = 5_000
                    reply.chunkDelayMs = 50
                }
            }
            for (sequential in listOf(false, true)) {
                cam.requests.clear()
                val f = remote(data.size.toLong(), sequential)
                try {
                    f.readAt(0, ByteArray(10), 0, 10)
                    fail("hard=$hard: a block that never arrives was read")
                } catch (e: IOException) {
                    assertTrue("hard=$hard: $e", e.message != "timed out waiting for the camera")
                }
                // One more try after the first break, and then no more until someone reads again.
                Thread.sleep(200)
                assertEquals("hard=$hard sequential=$sequential: ${ranges()}", 2, cam.fileRequests().size)
            }
        }
        // Once the camera behaves again, so does reading.
        cam.tweak = null
        assertReads(remote(data.size.toLong(), sequential = true), data, 0, 100_000)
    }

    @Test
    fun aPathThatIsNotAFile_isNeverAskedFor() {
        for (bad in listOf("/cgi-bin/Config.cgi?action=del&property=/SD/Normal/F/A.MP4", "/SD/../cgi-bin/Config.cgi", "/SD/Normal/F/a%3Fb.MP4", "/SD/Normal/F/A.MP4#x")) {
            for (sequential in listOf(false, true)) {
                val f = remote(1000, sequential, bad)
                try {
                    f.readAt(0, ByteArray(10), 0, 10)
                    fail("must be refused: $bad")
                } catch (e: Cam.RefusedException) {
                    assertTrue(e.message!!.startsWith("refused"))
                }
            }
        }
        assertTrue("nothing reached the camera", cam.requests.isEmpty())
    }

    // ---------------------------------------------------------------- a camera that stalls, and close()

    @Test(timeout = 30_000)
    fun aCameraThatStopsAnswering_isGivenUpOnAtTheDeadline() {
        val data = cam.addFile("Normal", name, 500_000)
        RemoteFile.deadlineMs = 500
        for (midBody in listOf(false, true)) {
            // Silent before the reply begins, or part-way through the first block.
            cam.tweak = { req, reply ->
                if ('?' !in req.target) {
                    if (midBody) {
                        reply.chunk = 50_000
                        reply.chunkDelayMs = 10_000
                    } else {
                        reply.stallMs = 10_000
                    }
                }
            }
            for (sequential in listOf(false, true)) {
                val f = remote(data.size.toLong(), sequential)
                val started = System.nanoTime()
                try {
                    f.readAt(0, ByteArray(10), 0, 10)
                    fail("the camera never sent the block")
                } catch (e: IOException) {
                    assertEquals("timed out waiting for the camera", e.message)
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                assertTrue("gave up after $ms ms", ms in 450..3000)
                f.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun close_endsAWaitingRead_atOnce_andFreesTheCamera() {
        val data = cam.addFile("Normal", name, 1_000_000)
        RemoteFile.deadlineMs = 1_500
        for (midBody in listOf(false, true)) {
            cam.requests.clear()
            cam.tweak = { req, reply ->
                if ('?' !in req.target) {
                    if (midBody) {
                        reply.chunk = 50_000
                        reply.chunkDelayMs = 20_000
                    } else {
                        reply.stallMs = 20_000
                    }
                }
            }
            val f = remote(data.size.toLong(), sequential = true)
            val error = AtomicReference<Throwable>()
            val ended = AtomicLong()
            val t = Thread {
                try {
                    f.readAt(0, ByteArray(100), 0, 100)
                } catch (e: Throwable) {
                    error.set(e)
                }
                ended.set(System.nanoTime())
            }
            t.start()
            waitUntil("the request to reach the camera") { cam.fileRequests().isNotEmpty() }
            Thread.sleep(100)
            // While its transfer is open, the reader has the camera.
            try {
                Cam.session(waitMs = 0) { }
                fail("midBody=$midBody: the camera was free during a transfer")
            } catch (e: Cam.BusyException) {
                // expected
            }
            val closing = System.nanoTime()
            f.close()
            t.join(5000)
            assertTrue("midBody=$midBody: ${error.get()}", error.get() is IOException)
            val ms = (ended.get() - closing) / 1_000_000
            assertTrue("midBody=$midBody: the read ended $ms ms after close()", ms < 300)
            // As soon as close() has returned, someone else can have the camera.
            Cam.session(waitMs = 0) { }
            try {
                f.readAt(0, ByteArray(1), 0, 1)
                fail("a read after close()")
            } catch (e: IOException) {
                assertEquals("closed", e.message)
            }
        }
    }

    // ---------------------------------------------------------------- sharing the camera

    @Test(timeout = 30_000)
    fun readingAhead_givesTheCameraUp_toAnyoneWaitingForIt() {
        val data = cam.addFile("Normal", name, 6 * MB)
        // About a tenth of a second per 128 KB block, so that the other side's wait can be told in blocks.
        cam.tweak = { req, reply ->
            if ('?' !in req.target) {
                reply.chunk = 16 * 1024
                reply.chunkDelayMs = 12
            }
        }
        val f = remote(data.size.toLong(), sequential = true)
        assertReads(f, data, 0, 1000)
        waitUntil("read-ahead to be under way") { f.bufferedUntil(0) >= 2L * STREAM_BLOCK }
        // A file list, say, queues up for the camera and gets it once the block in progress is in.
        val waited = AtomicLong(-1)
        val inside = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val other = Thread {
            val asked = System.nanoTime()
            Cam.session {
                waited.set((System.nanoTime() - asked) / 1_000_000)
                Cam.get(Cam.STATUS)
                inside.countDown()
                leave.await(10, TimeUnit.SECONDS)
            }
        }
        other.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        assertTrue("the other side waited ${waited.get()} ms", waited.get() < 400)
        // Meanwhile the player needs bytes that are not in memory: it waits for its turn instead of barging in.
        val requests = cam.fileRequests().size
        val want = f.bufferedUntil(0) + 100_000
        val got = AtomicReference<ByteArray>()
        val player = Thread { got.set(readFully(f, want, 50_000)) }
        player.start()
        Thread.sleep(300)
        assertEquals("the clip was asked for while someone else had the camera", requests, cam.fileRequests().size)
        assertTrue(player.isAlive)
        leave.countDown()
        other.join(5000)
        player.join(10_000)
        assertArrayEquals(data.copyOfRange(want.toInt(), want.toInt() + 50_000), got.get())
        assertEquals("it went on from the block it needed", want / STREAM_BLOCK * STREAM_BLOCK, cam.fileRequests().last().rangeStart)
        // And playback carries on from there.
        assertReads(f, data, want + 50_000, 300_000)
    }

    @Test(timeout = 60_000)
    fun twoThreadsReadingAtOnce_eachGetTheirOwnBytes() {
        val data = cam.addFile("Normal", name, 3_000_000)
        for (sequential in listOf(false, true)) {
            val f = remote(data.size.toLong(), sequential)
            val failures = CopyOnWriteArrayList<Throwable>()
            val threads = (1..2).map { n ->
                Thread {
                    try {
                        val rnd = Random(n * 31L + (if (sequential) 1 else 0))
                        repeat(150) { assertReads(f, data, rnd.nextInt(data.size).toLong(), 1 + rnd.nextInt(100_000)) }
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(30_000) }
            assertTrue("sequential=$sequential: $failures", failures.isEmpty())
            if (!sequential) assertEquals("requests overlapped", 1, cam.maxActive)
        }
    }

    private companion object {
        const val MB = 1024 * 1024

        /** The block sizes RemoteFile works in: playback, and thumbnails. */
        const val STREAM_BLOCK = 128 * 1024
        const val RANDOM_BLOCK = 256 * 1024
    }
}
