package com.dompka.dashlink

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The RTSP conversation: how replies and '$' frames are read off one connection, and what the client sends and
 * does against FakeRtsp - TCP, the fall back to UDP, keep-alives, TEARDOWN, and control URLs it must not follow.
 */
class RtspTest {
    private val sockets = ArrayList<Socket>()
    private var harness: LiveHarness? = null

    @After
    fun tearDown() {
        for (s in sockets) s.close()
        harness?.close()
    }

    private fun live(props: String? = null): LiveHarness = LiveHarness(props).also { harness = it }

    // ---------------------------------------------------------------- reading one connection

    /** A connection to a server socket this test writes to: the client end, and the server end. */
    private fun pair(): Pair<RtspConnection, Socket> {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { ss ->
            val client = Socket()
            client.connect(ss.localSocketAddress, 1000)
            val server = ss.accept()
            server.tcpNoDelay = true
            sockets.add(client)
            sockets.add(server)
            return RtspConnection(client, "127.0.0.1", "/liveRTSP/av4") to server
        }
    }

    private fun frame(channel: Int, size: Int): ByteArray {
        val b = ByteArray(4 + size) { (it * 7).toByte() }
        b[0] = '$'.code.toByte()
        b[1] = channel.toByte()
        b[2] = (size ushr 8).toByte()
        b[3] = size.toByte()
        return b
    }

    private fun mixed(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(frame(0, 10))
        b.write("RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 1234;timeout=60\r\n\r\n".toByteArray())
        b.write(frame(1, 3))
        b.write(byteArrayOf(0x00, 0x13, 0xff.toByte(), 'x'.code.toByte(), 'y'.code.toByte()))      // neither text nor a frame
        b.write("RTSP/1.0 404 Not Found\nCSeq: 4\nContent-Length: 5\n\nhello".toByteArray())        // bare line feeds
        b.write("SET_PARAMETER rtsp://127.0.0.1/liveRTSP/av4 RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 2\r\n\r\nab".toByteArray())
        b.write(frame(0, 65535))
        return b.toByteArray()
    }

    private fun readMixed(c: RtspConnection) {
        val until = System.currentTimeMillis() + 3000
        fun next() = c.read(until) {}
        assertEquals(RtspConnection.Kind.DATA, next())
        assertEquals(0, c.channel)
        assertEquals(10, c.dataLength)
        assertEquals("the frame's second payload byte", (5 * 7).toByte(), c.buffer[c.dataOffset + 1])
        assertEquals(RtspConnection.Kind.RESPONSE, next())
        assertEquals(200, c.response!!.status)
        assertEquals(3, c.response!!.cseq)
        assertEquals("1234;timeout=60", c.response!!.header("SESSION"))
        assertEquals(RtspConnection.Kind.DATA, next())
        assertEquals(1, c.channel)
        assertEquals(RtspConnection.Kind.RESPONSE, next())
        assertEquals(404, c.response!!.status)
        assertEquals("hello", String(c.response!!.body))
        assertEquals("a request from the camera is passed over", RtspConnection.Kind.OTHER, next())
        assertEquals(RtspConnection.Kind.DATA, next())
        assertEquals(65535, c.dataLength)
        assertEquals(5L, c.skipped)
    }

    @Test
    fun reader_framesRepliesAndRequestsMixed_garbageSkipped() {
        val (c, server) = pair()
        server.getOutputStream().write(mixed())
        readMixed(c)
    }

    @Test
    fun reader_everythingArrivingOneByteAtATime() {
        val (c, server) = pair()
        val bytes = mixed()
        val writer = Thread {
            val out = server.getOutputStream()
            for (b in bytes) {
                out.write(b.toInt())
                out.flush()
            }
        }
        writer.start()
        readMixed(c)
        writer.join()
    }

    @Test
    fun reader_garbageThatStartsLikeText_isSkippedToo() {
        val (c, server) = pair()
        server.getOutputStream().write("RTSX 200\r\nHELLO world\r\nOPTIONS\r\n\r\nRTSP/1.0 200 OK\r\nCSeq: 9\r\n\r\n".toByteArray())
        assertEquals(RtspConnection.Kind.RESPONSE, c.read(System.currentTimeMillis() + 3000) {})
        assertEquals(9, c.response!!.cseq)
        assertTrue(c.skipped > 20)
    }

    @Test
    fun reader_aReplyClaimingToBeHuge_isRefused() {
        val (c, server) = pair()
        server.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 99999999\r\n\r\n".toByteArray())
        try {
            c.read(System.currentTimeMillis() + 3000) {}
            fail("must not wait for 100 MB")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("99999999"))
        }
    }

    @Test
    fun reader_theCameraHangingUp_isTheEnd() {
        val (c, server) = pair()
        server.getOutputStream().write("RTSP/1.0 200".toByteArray())
        server.close()
        try {
            c.read(System.currentTimeMillis() + 3000) {}
            fail("the connection is gone")
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test
    fun call_waitsForTheReplyWithItsOwnCSeq() {
        val (c, server) = pair()
        var request: String? = null
        val answer = Thread {
            request = server.getInputStream().bufferedReader().readLine()
            server.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: 99\r\nPublic: x\r\n\r\nRTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: OPTIONS\r\n\r\n".toByteArray())
        }
        answer.start()
        val r = c.call("OPTIONS", "rtsp://127.0.0.1/liveRTSP/av4", emptyList(), 3000, {})
        answer.join()
        assertEquals("OPTIONS rtsp://127.0.0.1/liveRTSP/av4 RTSP/1.0", request)
        assertEquals("the reply to another request is passed over", "OPTIONS", r.header("public"))
    }

    @Test
    fun call_noReply_timesOut_askingWhetherToGoOnAllTheWhile() {
        val (c, _) = pair()
        var asked = 0
        val t0 = System.currentTimeMillis()
        try {
            c.call("OPTIONS", "rtsp://127.0.0.1/liveRTSP/av4", emptyList(), 400, { asked++ })
            fail("no reply came")
        } catch (e: SocketTimeoutException) {
            // expected
        }
        assertTrue(System.currentTimeMillis() - t0 < 2000)
        assertTrue("asked $asked times", asked >= 5)
        // And a turn that says stop ends the wait at once.
        val stop = RuntimeException("stop")
        try {
            c.call("OPTIONS", "rtsp://127.0.0.1/liveRTSP/av4", emptyList(), 5000, { throw stop })
            fail("should have stopped")
        } catch (e: RuntimeException) {
            assertTrue(e === stop)
        }
    }

    @Test
    fun send_refusedRequestsNeverLeaveThePhone() {
        val (c, server) = pair()
        for ((method, url) in listOf(
            "SET_PARAMETER" to "rtsp://127.0.0.1/liveRTSP/av4",
            "ANNOUNCE" to "rtsp://127.0.0.1/liveRTSP/av4",
            "SETUP" to "rtsp://127.0.0.1/liveRTSP/av2/trackID=1",
            "SETUP" to "rtsp://10.0.0.1/liveRTSP/av4/trackID=1",
            "PLAY" to "rtsp://127.0.0.1/cgi-bin/Config.cgi?action=del",
        )) {
            try {
                c.send(method, url)
                fail("must be refused: $method $url")
            } catch (e: Cam.RefusedException) {
                // expected
            }
        }
        server.soTimeout = 200
        try {
            val n = server.getInputStream().read()
            fail("something reached the camera: $n")
        } catch (e: SocketTimeoutException) {
            // nothing was sent
        }
    }

    // ---------------------------------------------------------------- the whole client against FakeRtsp

    @Test(timeout = 15_000)
    fun tcp_picturesReachTheSink_formatFirst() {
        val h = live()
        h.h264()
        h.start()
        h.awaitFrames(25)
        assertEquals("format", h.sink.calls.first())
        val f = h.sink.formats.single() as VideoFormat.H264
        assertArrayEquals(FakeRtsp.sps(640, 360), f.sps)
        assertArrayEquals(FakeRtsp.PPS, f.pps)
        assertEquals(640, f.width)
        assertEquals(360, f.height)
        val first = h.sink.frames.first()
        assertTrue(first.key)
        assertEquals(0L, first.ptsUs)
        assertEquals(20_000L, h.sink.frames[1].ptsUs)
        assertEquals("the in-band SPS comes first", 0x67, first.data[4].toInt())
        assertTrue("the sink is called on the streaming thread", h.sink.threads.all { it === h.thread })
        assertTrue("and so is the status", h.statusThreads.all { it === h.thread })
        val playing = h.statuses.first { it.phase == Live.Phase.PLAYING }
        assertEquals("/liveRTSP/av4", playing.stream)
        assertEquals("TCP", playing.transport)
        assertEquals("H.264 640x360", playing.detail)
        h.stop()

        assertEquals(listOf("TCP"), h.rtsp.transports)
        assertEquals(listOf("127.0.0.1:554"), h.rtsp.connects)
        val r = h.rtsp.requests
        assertEquals(
            listOf(
                "OPTIONS rtsp://127.0.0.1/liveRTSP/av4",
                "DESCRIBE rtsp://127.0.0.1/liveRTSP/av4",
                "SETUP rtsp://127.0.0.1/liveRTSP/av4/trackID=1",
                "PLAY rtsp://127.0.0.1/liveRTSP/av4",
            ),
            r.take(4).map { it.toString() },
        )
        assertEquals("TEARDOWN rtsp://127.0.0.1/liveRTSP/av4", r.last().toString())
        assertEquals((1..r.size).map { "$it" }, r.map { it.headers["cseq"] })
        assertTrue(r.all { it.headers["user-agent"] == "DashLink" })
        assertEquals("application/sdp", r[1].headers["accept"])
        assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1", r[2].headers["transport"])
        assertNull(r[2].headers["session"])
        assertEquals("npt=0.000-", r[3].headers["range"])
        assertNotNull(r[3].headers["session"])
        assertEquals(r[3].headers["session"], r.last().headers["session"])
    }

    @Test(timeout = 15_000)
    fun udp_whenTcpIsRefused_onTheSameConnection() {
        val h = live()
        h.h264()
        h.rtsp.tcp = false
        h.start()
        h.awaitFrames(10)
        assertEquals("UDP", h.statuses.first { it.phase == Live.Phase.PLAYING }.transport)
        assertEquals(listOf("UDP"), h.rtsp.transports)
        assertEquals("one connection: the camera only said no to the transport", 1, h.rtsp.connects.size)
        val setups = h.rtsp.requests("SETUP")
        assertEquals(2, setups.size)
        assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1", setups[0].headers["transport"])
        assertTrue(setups[1].headers["transport"]!!.matches(Regex("RTP/AVP;unicast;client_port=\\d+-\\d+")))
        assertTrue(Live.log().any { it.endsWith("av4 TCP: SETUP 461, trying UDP") })
        val f = h.sink.frames
        assertTrue(f.first().key)
        assertArrayEquals("then P pictures", byteArrayOf(0, 0, 0, 1, 0x41), f[1].data.copyOf(5))
    }

    @Test(timeout = 15_000)
    fun udp_whenTheCameraHangsUpOnATcpSetup() {
        val h = live()
        h.h264()
        h.rtsp.closeOnTcpSetup = true
        h.start()
        h.awaitFrames(10)
        assertEquals(listOf("UDP"), h.rtsp.transports)
        assertEquals("a new connection for the second try", 2, h.rtsp.connects.size)
        assertEquals("UDP", h.last()!!.transport)
    }

    @Test(timeout = 15_000)
    fun udp_whenTcpIsAcceptedButNothingComes() {
        val h = live()
        h.h264()
        h.rtsp.tcpSilent = true
        h.start()
        h.awaitFrames(5)
        assertEquals(listOf("TCP", "UDP"), h.rtsp.transports)
        assertTrue(Live.log().toString(), Live.log().any { it.endsWith("av4 TCP: no picture within 1 s (no data)") })
        assertEquals("UDP", h.last()!!.transport)
        assertEquals("the silent TCP session was ended before UDP was tried", 1, h.rtsp.requests("TEARDOWN").size)
    }

    @Test(timeout = 15_000)
    fun udp_packetsFromAnotherAddress_areNotTakenForTheCamera() {
        val h = live()
        h.h264()
        h.rtsp.tcp = false
        h.rtsp.udpSource = "127.0.0.2"
        h.start()
        h.awaitPhase(Live.Phase.UNAVAILABLE)
        assertTrue("packets were sent, from elsewhere", h.rtsp.packetsSent.get() > 10)
        assertTrue(h.sink.frames.isEmpty())
        assertTrue(h.last()!!.detail, h.last()!!.detail.startsWith("no live stream worked (av4: no picture"))
    }

    @Test(timeout = 15_000)
    fun keepAlive_comesBeforeTheCameraWouldGiveUp() {
        val h = live()
        h.h264()
        h.rtsp.sessionTimeout = 1                                  // a keep-alive every half second
        h.rtsp.keepAliveMs = 800                                    // and the camera hangs up after 0.8 s without one
        h.start()
        h.awaitPlaying()
        Thread.sleep(1800)
        assertEquals("the camera never had to hang up", 0, h.rtsp.timeouts.get())
        assertEquals("still the first session", 1, h.rtsp.requests("PLAY").size)
        val keepAlives = h.rtsp.requests("GET_PARAMETER")
        assertTrue("${keepAlives.size} keep-alives", keepAlives.size >= 2)
        assertTrue(keepAlives.all { it.headers["session"] != null && it.url == "rtsp://127.0.0.1/liveRTSP/av4" })
        val before = h.sink.frames.size
        Thread.sleep(200)
        assertTrue("pictures still arrive", h.sink.frames.size > before)
    }

    @Test(timeout = 15_000)
    fun keepAlive_withOptions_whenGetParameterIsNotOffered() {
        val h = live()
        h.h264()
        h.rtsp.getParameter = false
        h.rtsp.sessionTimeout = 1
        h.start()
        h.awaitPlaying()
        Thread.sleep(1300)
        assertTrue(h.rtsp.requests("GET_PARAMETER").isEmpty())
        val options = h.rtsp.requests("OPTIONS").drop(1)
        assertTrue("${options.size} keep-alives", options.size >= 2)
        assertTrue(options.all { it.headers["session"] != null })
    }

    @Test(timeout = 15_000)
    fun keepAlive_theCamerasOwnSettingCountsToo() {
        val h = live("Camera.Preview.RTSP.keepalive=1\n")
        h.h264()
        h.start()
        h.awaitPlaying()
        Thread.sleep(1300)
        assertTrue("a keep-alive every half second: ${h.rtsp.requests("GET_PARAMETER").size}", h.rtsp.requests("GET_PARAMETER").size >= 2)
    }

    @Test(timeout = 15_000)
    fun stop_sendsTeardown_andReturnsWithin500ms() {
        val h = live()
        h.h264()
        h.start()
        h.awaitFrames(5)
        val took = h.stop()
        assertTrue("stopped in $took ms", took < 500)
        val teardown = h.rtsp.requests.last()
        assertEquals("TEARDOWN", teardown.method)
        assertEquals(h.rtsp.requests("PLAY").single().headers["session"], teardown.headers["session"])
        assertEquals(Live.Phase.STOPPED, h.last()!!.phase)
    }

    @Test(timeout = 15_000)
    fun aCameraThatOnlyPlaysWithEveryTrack_getsItsAudioTrackSetUpToo() {
        val h = live()
        val s = h.h264()
        s.audio = true
        h.rtsp.allTracksForPlay = true
        h.start()
        h.awaitFrames(10)
        val setups = h.rtsp.requests("SETUP")
        assertEquals(listOf("rtsp://127.0.0.1/liveRTSP/av4/trackID=1", "rtsp://127.0.0.1/liveRTSP/av4/trackID=2"), setups.map { it.url })
        assertEquals("RTP/AVP/TCP;unicast;interleaved=2-3", setups[1].headers["transport"])
        assertEquals(setups[1].headers["session"], h.rtsp.requests("PLAY").last().headers["session"])
        assertEquals(2, h.rtsp.requests("PLAY").size)
    }

    @Test(timeout = 15_000)
    fun theAudioTrack_isNotSetUpWhenNotNeeded() {
        val h = live()
        h.h264().audio = true
        h.start()
        h.awaitFrames(5)
        assertEquals(listOf("rtsp://127.0.0.1/liveRTSP/av4/trackID=1"), h.rtsp.requests("SETUP").map { it.url })
    }

    @Test(timeout = 20_000)
    fun hostileControlUrls_areNeverSetUp() {
        for (control in listOf(
            "rtsp://10.0.0.1/liveRTSP/av4/trackID=1",                   // another host
            "rtsp://127.0.0.1:8554/liveRTSP/av4/trackID=1",              // another port
            "rtsp://127.0.0.1/liveRTSP/av2/trackID=1",                   // another stream
            "rtsp://127.0.0.1/cgi-bin/Config.cgi?action=del&property=x",
            "../../cgi-bin/Config.cgi",
            "trackID=1?action=set",
            "track 1",
        )) {
            val h = LiveHarness()
            try {
                h.h264().control = control
                h.start()
                h.awaitPhase(Live.Phase.UNAVAILABLE)
                assertTrue("$control was set up", h.rtsp.requests("SETUP").isEmpty())
                assertTrue(h.last()!!.detail, h.last()!!.detail.startsWith("no live stream worked (av4: refused to SETUP there, av2: 404"))
            } finally {
                h.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun contentBase_isTheBaseForRelativeControls_unlessItIsAnotherCamera() {
        val h = live()
        val s = h.h264()
        s.contentBase = "rtsp://127.0.0.1/liveRTSP/av4/"
        s.control = "track1"
        h.start()
        h.awaitFrames(3)
        assertEquals("rtsp://127.0.0.1/liveRTSP/av4/track1", h.rtsp.requests("SETUP").single().url)
        assertEquals("rtsp://127.0.0.1/liveRTSP/av4/", h.rtsp.requests("PLAY").single().url)
        h.stop()

        val other = LiveHarness()
        try {
            val s2 = other.h264()
            s2.contentBase = "rtsp://10.9.9.9/liveRTSP/av4/"
            s2.control = "track1"
            other.start()
            other.awaitFrames(3)
            assertEquals("rtsp://127.0.0.1/liveRTSP/av4/track1", other.rtsp.requests("SETUP").single().url)
            assertTrue(Live.log().any { it.contains("content-base is not this stream, ignored") })
        } finally {
            other.close()
        }
    }

    @Test(timeout = 15_000)
    fun absoluteControl_onTheSameHost_isUsedAsItIs() {
        val h = live()
        h.h264().control = "rtsp://127.0.0.1:554/liveRTSP/av4/trackID=1"
        h.start()
        h.awaitFrames(3)
        assertEquals("rtsp://127.0.0.1:554/liveRTSP/av4/trackID=1", h.rtsp.requests("SETUP").single().url)
    }

    @Test(timeout = 15_000)
    fun noParameterSetsInTheSdp_theyComeInBand() {
        val h = live()
        h.h264().sprop = false
        h.start()
        h.awaitFrames(3)
        val f = h.sink.formats.single() as VideoFormat.H264
        assertNull(f.sps)
        assertNull(f.pps)
        assertEquals("the size from the SPS in the first picture", 640, f.width)
        assertEquals(360, f.height)
        assertEquals(0x67, h.sink.frames.first().data[4].toInt())
    }

    @Test(timeout = 15_000)
    fun mjpeg_staticPayloadType_endToEnd() {
        val h = live()
        val scan = ByteArray(5000) { (it % 251).toByte() }
        val s = FakeRtsp.Stream("JPEG") {
            var n = 0L
            FakeRtsp.Source { FakeRtsp.JpegFrame(scan, 1, 60, 320, 240, 3000 * n++) }
        }
        s.rtpmap = false
        h.rtsp.streams["/liveRTSP/av4"] = s
        h.start()
        h.awaitFrames(5)
        assertEquals(320, (h.sink.formats.single() as VideoFormat.Jpeg).width)
        assertEquals("JPEG 320x240", h.statuses.first { it.phase == Live.Phase.PLAYING }.detail)
        for (f in h.sink.frames.take(5)) {
            assertTrue(f.key)
            assertEquals(0xd8, f.data[1].toInt() and 0xff)
            assertEquals(0xd9, f.data[f.data.size - 1].toInt() and 0xff)
            assertArrayEquals(scan, f.data.copyOfRange(f.data.size - 2 - scan.size, f.data.size - 2))
        }
    }

    @Test(timeout = 15_000)
    fun lostPacketsOverUdp_onlyWholePicturesGetThrough_fromTheNextKeyFrame() {
        val h = live()
        val sps = FakeRtsp.sps(640, 360)
        h.h264()
        h.rtsp.tcp = false
        h.rtsp.drop = { it == 40 || it == 41 }                    // in the middle of the stream
        h.start()
        h.awaitFrames(40)
        h.stop()
        // What each picture should be is known from its time: compare every one that got through.
        val source = FakeRtsp.h264(sps = sps)()
        val expected = (0 until 200).map { (source.next() as FakeRtsp.AccessUnit).nals }
        val indices = h.sink.frames.map { (it.ptsUs / 20_000).toInt() }
        for ((i, f) in h.sink.frames.withIndex()) {
            val want = ByteArrayOutputStream()
            for (n in expected[indices[i]]) {
                want.write(byteArrayOf(0, 0, 0, 1))
                want.write(n)
            }
            assertArrayEquals("picture ${indices[i]}", want.toByteArray(), f.data)
        }
        val missing = (0..indices.last()).filter { it !in indices }
        assertTrue("some pictures were lost: $missing", missing.isNotEmpty())
        assertEquals("pictures come back at an IDR", 0, (missing.last() + 1) % 10)
    }

    @Test(timeout = 15_000)
    fun everythingSent_isAReadToAStreamPath() {
        // Several streams missing, one refused transport, keep-alives, a stop: then look at what the camera got.
        val h = live("Camera.Preview.RTSP.av=3\n")
        h.h264("/liveRTSP/v2")
        h.rtsp.tcp = false
        h.rtsp.sessionTimeout = 1
        h.start()
        h.awaitFrames(80)
        h.stop()
        assertTrue("keep-alives were sent too", h.rtsp.requests("GET_PARAMETER").size >= 2)
        for (r in h.rtsp.requests) {
            assertTrue(r.toString(), r.method in setOf("OPTIONS", "DESCRIBE", "SETUP", "PLAY", "GET_PARAMETER", "TEARDOWN"))
            assertTrue(r.toString(), r.path.matches(Regex("/liveRTSP/(av|v)[1-9](/trackID=1)?")))
        }
        assertEquals(
            listOf("av3", "av4", "av2", "v1", "v2"),
            h.rtsp.requests("DESCRIBE").map { it.path.substringAfterLast('/') },
        )
        assertTrue(h.rtsp.connects.all { it == "127.0.0.1:554" })
    }
}
