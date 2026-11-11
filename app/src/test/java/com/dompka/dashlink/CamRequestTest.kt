package com.dompka.dashlink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Settings requests, whole-folder listings, "one request at a time" and the read-only rule. */
class CamRequestTest {
    private lateinit var cam: FakeCam

    @Before
    fun setUp() {
        cam = FakeCam()
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 3000
    }

    @After
    fun tearDown() {
        cam.close()
        Cam.opener = Cam.Opener { throw IOException("no camera in this test") }
        Cam.downloadTimeoutMs = 15_000
    }

    private fun entries(n: Int, folder: String = "Normal") = (0 until n).map {
        FakeCam.Entry("/SD/$folder/F/F%05d.MP4".format(it), 1000L + it, "2026-01-01 %02d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60))
    }

    private fun listAll(folder: String = "Normal", rear: Boolean = false) = Cam.listAll(folder, rear, { true }) { }

    // ---------------------------------------------------------------- get

    @Test
    fun get_ok() {
        val body = Cam.get(Cam.INFO)
        assertEquals("MiVue 956W", Cam.parseProps(body)["Vendor.DeviceInfo.Model"])
        assertEquals(Cam.INFO, cam.requests.single().target)
        assertEquals("GET", cam.requests.single().method)
    }

    @Test
    fun get_non200() {
        for (code in listOf(400, 404, 500, 503, 301)) {
            cam.override = { FakeCam.Reply(code, "x".toByteArray()).also { r -> r.headers["Location"] = Cam.INFO } }
            try {
                Cam.get(Cam.STATUS)
                fail("HTTP $code must not look like an answer")
            } catch (e: Cam.HttpException) {
                assertEquals(code, e.code)
            }
        }
        assertEquals("redirects are not followed", 5, cam.requests.size)
    }

    @Test
    fun get_emptyBody() {
        cam.override = { FakeCam.Reply(200) }
        assertEquals("", Cam.get(Cam.INFO))
        assertTrue(Cam.parseProps(Cam.get(Cam.INFO)).isEmpty())
        assertTrue(Cam.list("Normal", false, 0, 16).isEmpty())
        val all = listAll()
        assertTrue(all.clips.isEmpty())
        assertNull(all.error)
    }

    @Test
    fun get_timeout() {
        cam.tweak = { _, reply -> reply.stallMs = 5000 }
        val started = System.currentTimeMillis()
        try {
            Cam.get(Cam.STATUS, 300)
            fail("should time out")
        } catch (e: SocketTimeoutException) {
            assertTrue(System.currentTimeMillis() - started < 3000)
        }
    }

    @Test
    fun get_nothingListening() {
        cam.close()
        try {
            Cam.get(Cam.STATUS, 1000)
            fail("should fail")
        } catch (e: IOException) {
            // connection refused
        }
    }

    @Test
    fun get_replyThatNeverEnds_isCutOff() {
        cam.override = { FakeCam.Reply(200, ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }) }
        try {
            Cam.get(Cam.INFO)
            fail("a 3 MB settings reply must be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("larger"))
        }
    }

    // ---------------------------------------------------------------- whole folders

    @Test
    fun listAll_pagesOfSixteen_newestFirst() {
        cam.folders["Normal"] = entries(40)
        val all = listAll()
        assertNull(all.error)
        assertFalse(all.truncated)
        assertEquals(40, all.clips.size)
        assertEquals("F00039.MP4", all.clips.first().name)
        assertEquals("F00000.MP4", all.clips.last().name)
        // 16 + 16 + 8, then one empty page that says "that was all".
        assertEquals(listOf("0", "16", "32", "40"), cam.requests.map { it.query["from"] })
        assertTrue(cam.requests.all { it.query["count"] == "16" && it.query["action"] == "dir" && it.query["property"] == "Normal" })
    }

    @Test
    fun listAll_moreThanFiveThousandFiles() {
        cam.folders["Normal"] = entries(5003)
        val all = listAll()
        assertNull(all.error)
        assertFalse(all.truncated)
        assertEquals(5003, all.clips.size)
        assertEquals(5003, all.clips.map { it.path }.toSet().size)
        assertEquals(5003 / 16 + 2, cam.requests.size)
    }

    @Test
    fun listAll_cameraThatNeverStops_isCappedAndSaysSo() {
        // Every page is full of files nobody has seen before, for ever.
        cam.override = { req ->
            val from = req.query["from"]!!.toInt()
            FakeCam.Reply(200, cam.xml((from until from + 16).map { FakeCam.Entry("/SD/Normal/F/N$it.MP4", 1, "t") }).toByteArray())
        }
        val all = listAll()
        assertNull(all.error)
        assertTrue(all.truncated)
        assertEquals(Cam.MAX_FILES, all.clips.size)
        assertEquals(Cam.MAX_FILES / 16, cam.requests.size)
    }

    @Test
    fun listAll_cameraThatRepeatsThePage_ends() {
        // "from" is ignored: every request returns the same first page.
        val page = entries(16)
        cam.override = { FakeCam.Reply(200, cam.xml(page).toByteArray()) }
        val all = listAll()
        assertNull(all.error)
        assertFalse(all.truncated)
        assertEquals(16, all.clips.size)
        assertEquals(2, cam.requests.size)
    }

    @Test
    fun listAll_overlappingPages_noDuplicates() {
        // Each page starts one entry early (a camera that counts from 1, or a clip that appeared meanwhile).
        val files = entries(50)
        cam.override = { req ->
            val from = maxOf(0, req.query["from"]!!.toInt() - 1)
            FakeCam.Reply(200, cam.xml(files.drop(from).take(16)).toByteArray())
        }
        val all = listAll()
        assertEquals(50, all.clips.size)
        assertEquals(50, all.clips.map { it.path }.toSet().size)
        assertFalse(all.truncated)
    }

    @Test(timeout = 60_000)
    fun listAll_oneNewFilePerPageForEver_isCapped() {
        // The nastiest kind of "never ends": each page repeats old entries and adds a single new one.
        cam.override = { req ->
            val from = req.query["from"]!!.toInt()
            FakeCam.Reply(200, cam.xml((maxOf(0, from - 15)..from).map { FakeCam.Entry("/SD/Normal/F/S$it.MP4", 1, "t") }).toByteArray())
        }
        val all = listAll()
        assertTrue(all.truncated)
        assertTrue("stopped by the page limit, long before the file limit", all.clips.size < Cam.MAX_FILES)
        assertTrue(cam.requests.size <= Cam.MAX_FILES / 16 * 2 + 16)
    }

    @Test
    fun listAll_rearCameraAbsent() {
        cam.folders["Normal"] = entries(3)
        // No rear camera, variant 1: the camera answers reardir with an error.
        val refused = listAll(rear = true)
        assertTrue(refused.clips.isEmpty())
        assertEquals("HttpException: HTTP 404", refused.error)
        assertEquals("reardir", cam.requests.single().query["action"])
        // Variant 2: it answers with an empty list.
        cam.rearFolders["Normal"] = emptyList()
        val empty = listAll(rear = true)
        assertTrue(empty.clips.isEmpty())
        assertNull(empty.error)
        // Variant 3: it answers with something that is not a list at all.
        cam.override = { FakeCam.Reply(200, "709\n?\n".toByteArray()) }
        assertTrue(listAll(rear = true).clips.isEmpty())
        // The front camera is unaffected.
        cam.override = null
        assertEquals(3, listAll().clips.size)
    }

    @Test
    fun listAll_rearCameraPresent() {
        cam.addFile("Normal", "A.MP4", 10)
        cam.addFile("Normal", "B.MP4", 10, rear = true)
        assertEquals(listOf("/SD/Normal/F/A.MP4"), listAll().clips.map { it.path })
        assertEquals(listOf("/SD/Normal/R/B.MP4"), listAll(rear = true).clips.map { it.path })
    }

    @Test
    fun listAll_failurePartWay_keepsWhatWasRead() {
        cam.folders["Normal"] = entries(40)
        cam.tweak = { req, reply -> if (req.query["from"] == "32") reply.status = 500 }
        val all = listAll()
        assertEquals(32, all.clips.size)
        assertEquals("HttpException: HTTP 500", all.error)
        // The same when the link simply drops.
        cam.requests.clear()
        cam.tweak = { req, reply -> if (req.query["from"] == "16") reply.cutAfter = 10 }
        val cut = listAll()
        assertNotNull(cut.error)
        assertEquals(16, cut.clips.size)
    }

    @Test
    fun listAll_stopsWhenToldTo() {
        cam.folders["Normal"] = entries(100)
        var pages = 0
        val all = Cam.listAll("Normal", false, { pages < 2 }) { pages++ }
        assertEquals(32, all.clips.size)
        assertNull(all.error)
        assertEquals(2, cam.requests.size)
    }

    @Test
    fun listAll_folderNamesFromTheCameraCannotAddCommands() {
        cam.folders["Normal"] = entries(1)
        // The camera's own settings name its folders; a strange value there must stay a value.
        val all = Cam.listAll("Normal&action=del&property=/SD", false, { true }) { }
        assertNotNull("no such folder", all.error)
        val req = cam.requests.single()
        assertEquals("dir", req.query["action"])
        assertEquals(1, req.target.split('&').count { it.substringAfter('?').startsWith("action=") })
        // What the original code did with folder names is unchanged for ordinary ones.
        assertEquals(
            "/cgi-bin/Config.cgi?action=dir&property=Normal&count=16&from=32&format=all&backward=",
            Cam.listUrl("/Normal/,extra", false, 32, 16)
        )
        assertEquals(
            "/cgi-bin/Config.cgi?action=reardir&property=Event&count=16&from=0&format=all&backward=",
            Cam.listUrl("Event", true, 0, 16)
        )
    }

    // ---------------------------------------------------------------- one request at a time

    @Test(timeout = 30_000)
    fun cameraIsNeverAskedTwoThingsAtOnce() {
        cam.addFile("Normal", "SLOW.MP4", 400_000)
        cam.folders["Event"] = entries(40, "Event")
        cam.tweak = { req, reply ->
            if ('?' !in req.target) {
                reply.chunk = 20_000
                reply.chunkDelayMs = 30          // the file takes about 600 ms
            }
        }
        val started = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val sink = MemorySink()
        val download = Thread {
            try {
                Cam.download(cam.clip("/SD/Normal/F/SLOW.MP4"), sink, { false }) { _, _ -> started.countDown() }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        download.start()
        assertTrue(started.await(10, TimeUnit.SECONDS))

        // A keep-alive does not wait: it is skipped while the camera is busy.
        try {
            Cam.get(Cam.STATUS, 5000, waitMs = 0)
            fail("the camera was busy")
        } catch (e: Cam.BusyException) {
            // expected
        }
        // A report waits a little, then gives up.
        try {
            Cam.session(50) { Cam.get(Cam.STATUS) }
            fail("the camera was busy")
        } catch (e: Cam.BusyException) {
            // expected
        }
        // A file list waits for its turn and then gets it.
        val all = listAll("Event")
        assertFalse("the list had to wait for the download", download.isAlive && sink.length() < 400_000)
        assertEquals(40, all.clips.size)
        assertNull(all.error)

        download.join(10_000)
        assertNull(error.get())
        assertEquals(400_000L, sink.length())
        assertEquals("requests overlapped", 1, cam.maxActive)
    }

    @Test(timeout = 30_000)
    fun aWholeListingIsOneTurn() {
        cam.folders["Normal"] = entries(64)
        cam.tweak = { _, reply -> reply.stallMs = 40 }
        val inside = CountDownLatch(1)
        val t = Thread { Cam.listAll("Normal", false, { true }) { inside.countDown() } }
        t.start()
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        // Between two pages of the listing nobody else gets in.
        var busy = 0
        while (t.isAlive) {
            try {
                Cam.get(Cam.STATUS, 1000, waitMs = 0)
            } catch (e: Cam.BusyException) {
                busy++
            }
            Thread.sleep(5)
        }
        t.join()
        assertTrue(busy > 0)
        assertEquals(1, cam.maxActive)
        assertFalse("no keep-alive slipped in between pages", cam.requests.dropLast(1).any { it.query["property"] == "status" })
    }

    // ---------------------------------------------------------------- read-only

    @Test
    fun onlyReadsAreAllowed() {
        val allowed = listOf(
            Cam.INFO,
            Cam.STATUS,
            Cam.listUrl("Normal", false, 0, 16),
            Cam.listUrl("Parking", true, 160, 16),
            "/SD/Normal/F/FILE240101-120000F.MP4",
            "/SD/Photo/F/IMG%200001.JPG"
        )
        for (a in allowed) Cam.requireRead(a)

        val refused = listOf(
            "/cgi-bin/Config.cgi?action=set&property=Camera.Menu.VideoRes&value=1080P30",
            "/cgi-bin/Config.cgi?action=del&property=/SD/Normal/F/FILE240101-120000F.MP4",
            "/cgi-bin/Config.cgi?action=format&property=SD0",
            "/cgi-bin/Config.cgi?action=setcamid&property=Camera.Preview.Source.1.Camid&value=front",
            "/cgi-bin/Config.cgi?action=play&property=x",
            "/cgi-bin/Config.cgi?property=status",                          // no action at all
            "/cgi-bin/Config.cgi?action=get&property=x&action=del",         // a second action behind a harmless one
            "/cgi-bin/Config.cgi?action=del&action=get&property=x",
            "/cgi-bin/Config.cgi?ACTION=del&action=get&property=x",
            "/cgi-bin/Config.cgi?%61ction=del&action=get&property=x",       // "action", percent-encoded
            "/cgi-bin/Config.cgi?action=%64el&property=x",                  // "del", percent-encoded
            "/cgi-bin/Config.cgi?action=GET&property=x",                    // not one of the three, letter for letter
            "/cgi-bin/Config.cgi",                                          // a script without a query is not a file
            "/cgi-bin/Other.cgi?action=get&property=x",
            "/cgi-bin/FWupload.cgi",
            "/CGI-BIN/Config.cgi",
            "/SD/Normal/F/evil.cgi",
            "/SD/Normal/F/x.MP4#frag",
            "SD/Normal/F/x.MP4",                                            // not absolute
            "http://192.72.1.1/cgi-bin/Config.cgi?action=del",
            ""
        )
        for (r in refused) {
            try {
                Cam.requireRead(r)
                fail("must be refused: $r")
            } catch (e: IOException) {
                assertTrue(e.message!!.startsWith("refused"))
            }
        }
    }

    @Test
    fun refusedRequestsNeverLeaveThePhone() {
        for (bad in listOf(
            "/cgi-bin/Config.cgi?action=set&property=Net.WIFI_AP.SSID&value=x",
            "/cgi-bin/Config.cgi?action=del&property=/SD/Normal/F/A.MP4"
        )) {
            try {
                Cam.get(bad)
                fail("must be refused")
            } catch (e: IOException) {
                assertTrue(e.message!!.startsWith("refused"))
            }
        }
        // A file list that names a command where a file should be: "downloading" it must not send that command.
        val hostile = Cam.Clip("/cgi-bin/Config.cgi?action=del&property=/SD/Normal/F/A.MP4", 100, "", "", "")
        try {
            Cam.download(hostile, MemorySink(), { false }) { _, _ -> }
            fail("must be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("refused"))
        }
        assertTrue("nothing reached the camera", cam.requests.isEmpty())
    }

    @Test
    fun everythingTheAppSends_isARead() {
        // Everything the app can ask for, in one go; then look at what the camera actually received.
        cam.addFile("Normal", "A.MP4", 50_000)
        cam.addFile("Normal", "B.MP4", 50_000, rear = true)
        cam.folders["Event"] = entries(20, "Event")
        Cam.get(Cam.INFO)
        Cam.get(Cam.STATUS)
        listAll("Normal")
        listAll("Normal", rear = true)
        listAll("Event")
        listAll("NoSuchFolder")
        Cam.session { Cam.get(Cam.listUrl("Event", false, 0, 16)) }
        Cam.download(cam.clip("/SD/Normal/F/A.MP4"), MemorySink(), { false }) { _, _ -> }
        var cuts = 1
        cam.tweak = { _, reply -> if (cuts-- > 0) reply.cutAfter = 10_000 }
        val sink = MemorySink()
        try {
            Cam.download(cam.clip("/SD/Normal/R/B.MP4"), sink, { false }) { _, _ -> }
            fail("was cut")
        } catch (e: IOException) {
            // resumed below
        }
        Cam.download(cam.clip("/SD/Normal/R/B.MP4"), sink, { false }) { _, _ -> }

        assertTrue(cam.requests.size > 10)
        for (r in cam.requests) {
            assertEquals("GET", r.method)
            if ('?' in r.target) {
                assertEquals("/cgi-bin/Config.cgi", r.path)
                assertTrue("unexpected action in ${r.target}", r.query["action"] in setOf("get", "dir", "reardir"))
                assertEquals(1, r.target.substringAfter('?').split('&').count { it.startsWith("action=") })
            } else {
                assertTrue(r.target, r.target.startsWith("/SD/"))
            }
            for (word in listOf("set", "del", "format")) {
                assertFalse("${r.target} contains action=$word", r.target.contains("action=$word"))
            }
        }
    }
}
