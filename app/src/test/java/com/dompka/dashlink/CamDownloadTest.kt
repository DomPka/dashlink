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
        assertTrue("something other than a read was sent: ${cam.violations.map { it.target }}", cam.violations.isEmpty())
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
    fun rangeNotSatisfiable_416_withoutDetails_isNeverTakenForComplete() {
        // A bare 416 says "not from there" and nothing else. Version 0.1 read it as "you have it all" when
        // the list gave no size - and would have published a ten-byte stump as a finished clip.
        val data = cam.addFile("Normal", name, 90_000)
        cam.override = { if (it.rangeStart < 0) null else FakeCam.Reply(416) }
        for (clip in listOf(Cam.Clip(path, -1, "", "", ""), Cam.Clip(path, 90_000, "", "", ""))) {
            dest().writeBytes(ByteArray(10) { 1 })
            val e = attempt(clip)
            assertTrue("$e", e is Cam.RestartException)
            assertEquals("the stump is thrown away", 0L, dest().length())
            // The next attempt asks for the whole file and gets it.
            assertEquals(null, attempt(clip))
            assertArrayEquals(data, dest().readBytes())
            dest().delete()
        }
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
            assertFalse("a dropped link is not a storage, restart, cancel or \"this clip\" problem: $first",
                first is Cam.StorageException || first is Cam.RestartException || first is Cam.CancelledException ||
                    first is Cam.HttpException || first is Cam.NoDataException || first is Cam.RefusedException)
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
            assertTrue("$e", e is Cam.NoDataException)
            assertEquals(0L, dest().length())
        }
        // The same without a Content-Length: an empty stream.
        cam.override = { if ('?' in it.target) null else FakeCam.Reply(200).also { r -> r.sendLength = false } }
        assertTrue(attempt(Cam.Clip(path, -1, "", "", "")) is Cam.NoDataException)
        // And a reply that is announced and then never comes: the camera answered, the clip did not.
        Cam.downloadTimeoutMs = 300
        cam.override = { if ('?' in it.target) null else FakeCam.Reply(200, ByteArray(500)).also { r -> r.cutAfter = 0 } }
        assertTrue(attempt(cam.clip(path)) is Cam.NoDataException)
    }

    @Test
    fun resumeThatBringsNothing() {
        val data = cam.addFile("Normal", name, 1000)
        // The camera accepts the resume point and then sends no bytes at all.
        cam.override = {
            if (it.rangeStart < 0) null else FakeCam.Reply(206).also { r -> r.headers["Content-Range"] = "bytes ${it.rangeStart}-999/1000" }
        }
        // Size known and not reached: this clip is in trouble (a damaged spot on the card, say). Not a link problem.
        dest().writeBytes(data.copyOf(400))
        val e = attempt(cam.clip(path))
        assertTrue("$e", e is Cam.NoDataException)
        assertEquals("the part is kept", 400L, dest().length())

        // The camera says the file is exactly as long as what the phone has: nothing was left to send.
        cam.override = {
            if (it.rangeStart < 0) null else FakeCam.Reply(206).also { r -> r.headers["Content-Range"] = "bytes ${it.rangeStart}-399/400" }
        }
        assertEquals(null, attempt(Cam.Clip(path, -1, "", "", "")))
        assertEquals(400L, dest().length())

        // No size from the list, none from the camera: whether 400 bytes is all of it cannot be known. Start over.
        cam.override = { if (it.rangeStart < 0) null else FakeCam.Reply(206) }
        val unsure = attempt(Cam.Clip(path, -1, "", "", ""))
        assertTrue("$unsure", unsure is Cam.RestartException)
        assertEquals(0L, dest().length())
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
    fun contentRangeWrittenSloppily_orNotAtAll() {
        val data = cam.addFile("Normal", name, 100_000)
        fun reply(header: String, from: Int) = { req: FakeCam.Request ->
            if ('?' in req.target) null else FakeCam.Reply(206, data.copyOfRange(from, 100_000)).also { it.headers["Content-Range"] = header }
        }
        // "=" where the space belongs, and no total: small web servers write it like this. Still readable.
        for (header in listOf("bytes=40000-99999/100000", "bytes 40000-99999", "Bytes  40000-99999/*", "bytes:40000-")) {
            dest().writeBytes(data.copyOf(40_000))
            cam.override = reply(header, 40_000)
            assertEquals(header, null, attempt(cam.clip(path)))
            assertArrayEquals(header, data, dest().readBytes())
        }
        // The same spelling, but it is the whole file from the top: must not be glued onto the old part.
        dest().writeBytes(ByteArray(40_000) { 3 })
        cam.override = reply("bytes=0-99999/100000", 0)
        assertEquals(null, attempt(cam.clip(path)))
        assertArrayEquals(data, dest().readBytes())
        // A header that is there but makes no sense is not "no header": the part cannot be continued on trust.
        for (header in listOf("pieces 1-2", "bytes abc-def/ghi", "bytes -5-10/100", "40000-99999/100000")) {
            dest().writeBytes(data.copyOf(40_000))
            cam.override = reply(header, 0)
            val e = attempt(cam.clip(path))
            assertTrue("$header -> $e", e is Cam.RestartException)
            assertEquals(header, 0L, dest().length())
        }
    }

    @Test
    fun moreBytesThanTheFileHas_areNotKept() {
        val data = cam.addFile("Normal", name, 1000)
        dest().writeBytes(data.copyOf(400))
        // The camera says the file has 1000 bytes and then sends 900 more after byte 400.
        cam.override = {
            if ('?' in it.target) null else FakeCam.Reply(206, ByteArray(900) { 5 }).also { r -> r.headers["Content-Range"] = "bytes 400-999/1000" }
        }
        val e = attempt(cam.clip(path))
        assertTrue("$e", e is Cam.RestartException)
        assertEquals(0L, dest().length())
    }

    @Test(timeout = 20_000)
    fun waitingForTheCameraToBeFree_stillHearsStop() {
        cam.addFile("Normal", name, 1000)
        val inside = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // Something else has the camera for a long time (a listing of thousands of files, say).
        val holder = Thread {
            Cam.session {
                inside.countDown()
                release.await()
            }
        }
        holder.start()
        inside.await()
        val started = System.currentTimeMillis()
        val e = attempt(cam.clip(path), cancelled = { System.currentTimeMillis() - started > 600 })
        assertTrue("$e", e is Cam.CancelledException)
        assertTrue("Stop was heard while waiting", System.currentTimeMillis() - started < 5000)
        assertTrue(cam.requests.isEmpty())
        release.countDown()
        holder.join()
        // And once the camera is free the download simply runs.
        assertEquals(null, attempt(cam.clip(path)))
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
        val odd = "/SD/Photo/F/my photo (1) ä & b=c;d,e+f!.JPG"
        cam.files[odd] = byteArrayOf(1, 2, 3, 4)
        assertEquals(null, attempt(Cam.Clip(odd, 4, "", "", "")))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), dest().readBytes())
        val sent = cam.fileRequests().single().target
        assertFalse("spaces and accents travel escaped: $sent", ' ' in sent || 'ä' in sent || '?' in sent || '#' in sent)
        assertEquals(odd, cam.fileRequests().single().path)
        // A path without the leading slash is made absolute rather than glued to the address.
        dest().delete()
        cam.files["/SD/Photo/F/plain.JPG"] = byteArrayOf(5)
        assertEquals(null, attempt(Cam.Clip("SD/Photo/F/plain.JPG", 1, "", "", "")))
        assertEquals("/SD/Photo/F/plain.JPG", cam.fileRequests().last().target)
    }

    @Test
    fun fileNamesNoCameraWouldUse_areNotAskedFor() {
        // Nothing in a file name may ever start a query, hide a path, or be read two ways.
        for (name in listOf("a?b.MP4", "a#b.MP4", "a%3Fb.MP4", "a%2e%2e.MP4", "a\\b.MP4", "a\u0000b.MP4", "a\nb.MP4")) {
            val e = attempt(Cam.Clip("/SD/Normal/F/$name", 4, "", "", ""))
            assertTrue("$name -> $e", e is Cam.RefusedException)
        }
        assertTrue(cam.requests.isEmpty())
    }
}
