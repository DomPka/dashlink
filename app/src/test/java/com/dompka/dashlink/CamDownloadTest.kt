package com.dompka.dashlink

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files

/** Cam.download against a local server that behaves, and then one that does not. */
class CamDownloadTest {
    private lateinit var cam: FakeCam
    private lateinit var dir: File
    private val name = "FILE240101-120000F.MP4"
    private val path = "/SD/Normal/F/$name"

    @Before
    fun setUp() {
        cam = FakeCam()
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 3000
        dir = Files.createTempDirectory("dashlink-test").toFile()
    }

    @After
    fun tearDown() {
        cam.close()
        Cam.opener = Cam.Opener { throw IOException("no camera in this test") }
        Cam.downloadTimeoutMs = 15_000
        dir.deleteRecursively()
    }

    private fun dest() = File(dir, name)

    /** One attempt. Returns the exception it ended with, or null when the file is complete. */
    private fun attempt(
        clip: Cam.Clip,
        file: File = dest(),
        cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): Exception? {
        val sink = FileSink(file)
        return try {
            Cam.download(clip, sink, cancelled, progress)
            null
        } catch (e: Exception) {
            e
        } finally {
            sink.close()
        }
    }

    private fun ranges() = cam.fileRequests().map { it.headers["range"] }

    @Test
    fun fullFile() {
        val data = cam.addFile("Normal", name, 300_000)
        val seen = ArrayList<Pair<Long, Long>>()
        assertEquals(null, attempt(cam.clip(path)) { have, total -> seen.add(have to total) })
        assertArrayEquals(data, dest().readBytes())
        assertEquals(listOf<String?>(null), ranges())
        assertEquals("identity", cam.fileRequests()[0].headers["accept-encoding"])
        assertEquals("close", cam.fileRequests()[0].headers["connection"])
        assertTrue(seen.isNotEmpty())
        assertEquals(300_000L to 300_000L, seen.last())
        assertEquals(seen.map { it.first }.sorted(), seen.map { it.first })
    }

    @Test
    fun alreadyComplete_asksNothing() {
        val data = cam.addFile("Normal", name, 50_000)
        dest().writeBytes(data)
        assertEquals(null, attempt(cam.clip(path)))
        assertTrue(cam.requests.isEmpty())
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun resumeFromPartial() {
        val data = cam.addFile("Normal", name, 300_000)
        dest().writeBytes(data.copyOf(100_000))
        val seen = ArrayList<Long>()
        assertEquals(null, attempt(cam.clip(path)) { have, _ -> seen.add(have) })
        assertArrayEquals(data, dest().readBytes())
        assertEquals(listOf<String?>("bytes=100000-"), ranges())
        assertTrue("progress continues from the old part", seen.first() > 100_000L)
    }

    @Test
    fun serverIgnoresRange_startsTheFileOver() {
        val data = cam.addFile("Normal", name, 200_000)
        cam.supportsRange = false
        // The old part is deliberately wrong: if it were kept, the result could not match.
        dest().writeBytes(ByteArray(80_000) { 7 })
        assertEquals(null, attempt(cam.clip(path)))
        assertArrayEquals(data, dest().readBytes())
        assertEquals(listOf<String?>("bytes=80000-"), ranges())
    }

    @Test
    fun rangeNotSatisfiable_416_restartsOnce() {
        val data = cam.addFile("Normal", name, 120_000)
        // More on the phone than the camera has: the old part belongs to some other file.
        dest().writeBytes(ByteArray(150_000) { 9 })
        val first = attempt(cam.clip(path))
        assertTrue("$first", first is Cam.RestartException)
        assertEquals(0L, dest().length())
        assertEquals(null, attempt(cam.clip(path)))
        assertArrayEquals(data, dest().readBytes())
        assertEquals(listOf("bytes=150000-", null), ranges())
    }

    @Test
    fun rangeNotSatisfiable_416_whenThePhoneAlreadyHasItAll() {
        // No size from the list, the whole file already on the phone: 416 means "nothing left to send".
        val data = cam.addFile("Normal", name, 90_000)
        dest().writeBytes(data)
        val clip = Cam.Clip(path, -1, "", "", "")
        assertEquals(null, attempt(clip))
        assertArrayEquals(data, dest().readBytes())
        assertEquals(listOf<String?>("bytes=90000-"), ranges())
    }

    @Test
    fun rangeNotSatisfiable_416_withoutDetails() {
        cam.addFile("Normal", name, 90_000)
        cam.override = { if ('?' in it.target) null else FakeCam.Reply(416) }
        // Size unknown everywhere: the only reading left is "already complete" (as the camera's own app assumes).
        dest().writeBytes(ByteArray(10) { 1 })
        assertEquals(null, attempt(Cam.Clip(path, -1, "", "", "")))
        assertEquals(10L, dest().length())
        // Size known and not reached: the part is thrown away and the caller is told to start over.
        val e = attempt(Cam.Clip(path, 90_000, "", "", ""))
        assertTrue("$e", e is Cam.RestartException)
        assertEquals(0L, dest().length())
    }

    @Test
    fun connectionCutMidStream_thenResumed_isByteIdentical() {
        for (hard in listOf(true, false)) {
            dest().delete()
            cam.requests.clear()
            val data = cam.addFile("Normal", "CUT$hard.MP4", 400_000)
            val clip = cam.clip("/SD/Normal/F/CUT$hard.MP4")
            var cuts = 1
            cam.tweak = { _, reply ->
                if (cuts-- > 0) {
                    reply.cutAfter = 150_000
                    reply.cutHard = hard
                }
            }
            val first = attempt(clip)
            assertTrue("hard=$hard: a cut must be reported, got $first", first is IOException)
            assertFalse("a dropped link is not a storage, restart or cancel problem: $first",
                first is Cam.StorageException || first is Cam.RestartException || first is Cam.CancelledException || first is Cam.HttpException)
            val kept = dest().length()
            assertTrue("hard=$hard kept=$kept", kept in 1..150_000)
            assertArrayEquals("what was kept is the start of the file", data.copyOf(kept.toInt()), dest().readBytes())

            assertEquals(null, attempt(clip))
            assertArrayEquals("hard=$hard", data, dest().readBytes())
            assertEquals(listOf(null, "bytes=$kept-"), ranges())
        }
    }

    @Test
    fun connectionCutAgainAndAgain_stillEndsByteIdentical() {
        val data = cam.addFile("Normal", name, 500_000)
        cam.tweak = { _, reply -> if (reply.body.size > 70_000) reply.cutAfter = 70_000 }
        var attempts = 0
        while (true) {
            attempts++
            val e = attempt(cam.clip(path)) ?: break
            assertTrue("$e", e is IOException)
            if (attempts > 50) fail("still not complete after $attempts attempts")
        }
        assertArrayEquals(data, dest().readBytes())
        assertTrue("it took several attempts: $attempts", attempts in 5..50)
    }

    @Test
    fun unknownSize_minusOne() {
        // Size -1 in the list and no Content-Length either: the file is whatever arrives before the camera hangs up.
        val data = cam.addFile("Normal", name, 250_000)
        cam.tweak = { _, reply -> reply.sendLength = false }
        val totals = HashSet<Long>()
        assertEquals(null, attempt(Cam.Clip(path, -1, "", "", "")) { _, total -> totals.add(total) })
        assertArrayEquals(data, dest().readBytes())
        assertEquals(setOf(-1L), totals)
    }

    @Test
    fun unknownSizeInTheList_butTheCameraSaysHowMuch() {
        val data = cam.addFile("Normal", name, 250_000)
        val totals = HashSet<Long>()
        assertEquals(null, attempt(Cam.Clip(path, -1, "", "", "")) { _, total -> totals.add(total) })
        assertArrayEquals(data, dest().readBytes())
        assertEquals(setOf(250_000L), totals)
        // And a cut is still noticed, because the camera said how much was coming.
        dest().delete()
        var cuts = 1
        cam.tweak = { _, reply -> if (cuts-- > 0) reply.cutAfter = 1000 }
        assertTrue(attempt(Cam.Clip(path, -1, "", "", "")) is IOException)
        assertEquals(null, attempt(Cam.Clip(path, -1, "", "", "")))
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun sizeInTheListIsWrong_theTransferIsBelieved() {
        val data = cam.addFile("Normal", name, 100_000)
        // The list says more than the camera sends. Insisting on the list would download this file for ever.
        assertEquals(null, attempt(Cam.Clip(path, 123_456, "", "", "")))
        assertArrayEquals(data, dest().readBytes())
        // The list says less (the clip grew since it was listed).
        dest().delete()
        assertEquals(null, attempt(Cam.Clip(path, 60_000, "", "", "")))
        assertArrayEquals(data, dest().readBytes())
        assertEquals(2, cam.fileRequests().size)
    }

    @Test
    fun cancel_beforeAnythingIsSent() {
        cam.addFile("Normal", name, 100_000)
        val e = attempt(cam.clip(path), cancelled = { true })
        assertTrue("$e", e is Cam.CancelledException)
        assertTrue(cam.requests.isEmpty())
    }

    @Test
    fun cancel_midStream() {
        cam.addFile("Normal", name, 2_000_000)
        cam.tweak = { _, reply ->
            reply.chunk = 20_000
            reply.chunkDelayMs = 10
        }
        var got = 0L
        val started = System.currentTimeMillis()
        val e = attempt(cam.clip(path), cancelled = { got >= 100_000 }) { have, _ -> got = have }
        assertTrue("$e", e is Cam.CancelledException)
        assertTrue("stopped early, at $got", got in 100_000 until 2_000_000)
        assertTrue("stopped promptly", System.currentTimeMillis() - started < 5_000)
        // The part stays where it is: whether to keep or delete it is the caller's decision (see RunnerTest).
        assertEquals(got, dest().length())
    }

    @Test
    fun cameraRefuses_non200() {
        cam.addFile("Normal", name, 1000)
        for (code in listOf(404, 403, 500, 503, 302)) {
            cam.override = { FakeCam.Reply(code, "no".toByteArray()).also { r -> r.headers["Location"] = "/SD/Normal/F/$name" } }
            val e = attempt(cam.clip(path))
            assertTrue("$code -> $e", e is Cam.HttpException && e.code == code)
        }
        assertEquals("a redirect is an answer, not an instruction", 5, cam.requests.size)
        assertEquals(0L, dest().length())
        val missing = attempt(Cam.Clip("/SD/Normal/F/GONE.MP4", 10, "", "", "")).also { cam.override = null }
        assertTrue(missing is Cam.HttpException)
    }

    @Test
    fun emptyBody_isNotACompleteFile() {
        cam.addFile("Normal", name, 1000)
        cam.override = { if ('?' in it.target) null else FakeCam.Reply(200) }
        for (clip in listOf(cam.clip(path), Cam.Clip(path, -1, "", "", ""))) {
            val e = attempt(clip)
            assertTrue("$e", e is IOException && e !is Cam.HttpException)
            assertEquals(0L, dest().length())
        }
        // The same without a Content-Length: an empty stream.
        cam.override = { if ('?' in it.target) null else FakeCam.Reply(200).also { r -> r.sendLength = false } }
        assertTrue(attempt(Cam.Clip(path, -1, "", "", "")) is IOException)
    }

    @Test
    fun partialContentFromTheWrongPlace() {
        val data = cam.addFile("Normal", name, 100_000)
        dest().writeBytes(data.copyOf(40_000))
        // Asked for 40000-, the camera sends 50000-: appending that would silently corrupt the clip.
        cam.override = {
            if ('?' in it.target) null else FakeCam.Reply(206, data.copyOfRange(50_000, 100_000)).also { r ->
                r.headers["Content-Range"] = "bytes 50000-99999/100000"
            }
        }
        val e = attempt(cam.clip(path))
        assertTrue("$e", e is Cam.RestartException)
        assertEquals(0L, dest().length())

        // Asked for 40000-, the camera sends the whole file as 206 from byte 0: take it from the top.
        dest().writeBytes(ByteArray(40_000) { 3 })
        cam.override = {
            if ('?' in it.target) null else FakeCam.Reply(206, data).also { r ->
                r.headers["Content-Range"] = "bytes 0-99999/100000"
            }
        }
        assertEquals(null, attempt(cam.clip(path)))
        assertArrayEquals(data, dest().readBytes())

        // 206 without a Content-Range header: trusted to be what was asked for.
        dest().writeBytes(data.copyOf(40_000))
        cam.override = { if ('?' in it.target) null else FakeCam.Reply(206, data.copyOfRange(40_000, 100_000)) }
        assertEquals(null, attempt(cam.clip(path)))
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun phoneStorageFails_isNotMistakenForADroppedLink() {
        cam.addFile("Normal", name, 300_000)
        val sink = MemorySink()
        sink.failAt = 100_000
        try {
            Cam.download(cam.clip(path), sink, { false }) { _, _ -> }
            fail("should have failed")
        } catch (e: Cam.StorageException) {
            assertTrue(e.message!!.contains("ENOSPC"))
        }
        assertTrue(sink.length() <= 100_000)
    }

    @Test
    fun cameraStopsAnswering_timesOut() {
        cam.addFile("Normal", name, 1000)
        Cam.downloadTimeoutMs = 400
        cam.tweak = { _, reply -> reply.stallMs = 5000 }
        val started = System.currentTimeMillis()
        val e = attempt(cam.clip(path))
        assertTrue("$e", e is SocketTimeoutException)
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test
    fun cameraStallsMidFile_timesOut_andTheRestIsFetchedLater() {
        val data = cam.addFile("Normal", name, 200_000)
        Cam.downloadTimeoutMs = 400
        var stalls = 1
        cam.tweak = { _, reply ->
            if (stalls-- > 0) {
                reply.chunk = 50_000
                reply.chunkDelayMs = 3000
            }
        }
        val e = attempt(cam.clip(path))
        assertTrue("$e", e is IOException)
        assertTrue(dest().length() in 1 until 200_000)
        assertEquals(null, attempt(cam.clip(path)))
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun noLinkToTheCamera() {
        Cam.opener = Cam.Opener { throw IOException("not connected to the camera") }
        val e = attempt(Cam.Clip(path, 10, "", "", ""))
        assertTrue(e is IOException && e.message == "not connected to the camera")
    }

    @Test
    fun unusualFileNames_reachTheCameraIntact() {
        val odd = "/SD/Photo/F/my photo #1 (50%) ä?.JPG"
        cam.files[odd] = byteArrayOf(1, 2, 3, 4)
        assertEquals(null, attempt(Cam.Clip(odd, 4, "", "", "")))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), dest().readBytes())
        val sent = cam.fileRequests().single().target
        assertFalse("nothing in a file name may start a query: $sent", '?' in sent || '#' in sent || ' ' in sent)
        // A path without the leading slash is made absolute rather than glued to the address.
        dest().delete()
        cam.files["/SD/Photo/F/plain.JPG"] = byteArrayOf(5)
        assertEquals(null, attempt(Cam.Clip("SD/Photo/F/plain.JPG", 1, "", "", "")))
        assertEquals("/SD/Photo/F/plain.JPG", cam.fileRequests().last().target)
    }
}
