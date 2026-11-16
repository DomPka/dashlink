package com.dompka.dashlink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Live.run as a whole: which streams it tries and in which order, what it says when none works, how it backs off
 * and comes back, how it shares the camera with downloads and lists, and how quickly it stops.
 */
class LiveTest {
    private val harnesses = ArrayList<LiveHarness>()

    @After
    fun tearDown() {
        for (h in harnesses.asReversed()) h.close()
    }

    private fun live(props: String? = null) = LiveHarness(props).also { harnesses.add(it) }

    private fun busy(h: LiveHarness) = h.statuses.any { it.phase == Live.Phase.PAUSED_BUSY && it.detail == "camera busy with another request" }

    // ---------------------------------------------------------------- which stream

    @Test(timeout = 15_000)
    fun candidateOrder_followsTheCamerasOwnSetting() {
        val h = live("Camera.Preview.RTSP.av=2\nCamera.Preview.RTSP.keepalive=60\n")
        h.h264("/liveRTSP/av2")
        h.h264("/liveRTSP/av4")
        h.start()
        h.awaitPlaying()
        assertEquals("/liveRTSP/av2", h.rtsp.requests("DESCRIBE").first().path)
        assertEquals("/liveRTSP/av2", h.statuses.first { it.phase == Live.Phase.PLAYING }.stream)
        assertTrue(Live.log().any { it.endsWith("live view settings: RTSP.av=2, RTSP.keepalive=60") })
    }

    @Test(timeout = 15_000)
    fun withoutSettings_av4First_andTheLogSaysWhy() {
        val h = live()
        h.h264("/liveRTSP/v1")
        h.start()
        h.awaitPlaying()
        assertEquals(listOf("av4", "av2", "v1"), h.rtsp.requests("DESCRIBE").map { it.path.substringAfterLast('/') })
        assertTrue(Live.log().any { it.contains("live view settings could not be read") })
        assertTrue(Live.log().any { it.endsWith("av4 TCP: DESCRIBE 404") })
    }

    @Test(timeout = 15_000)
    fun nothingThere_saysWhat_thenPlaysOnceTheStreamAppears() {
        val h = live()
        h.start()
        h.awaitPhase(Live.Phase.UNAVAILABLE)
        assertEquals(
            "no live stream found (av4: 404, av2: 404, v1: 404, v2: 404, v3: 404, av1: 404, av3: 404)",
            h.last()!!.detail,
        )
        // It keeps trying, a little more slowly each time.
        h.await("four rounds", 5000) { h.rtsp.requests("DESCRIBE").count { it.path == "/liveRTSP/av4" } >= 4 }
        h.h264()
        h.awaitPlaying()
        val rounds = h.rtsp.requests("DESCRIBE").count { it.path == "/liveRTSP/av4" }
        assertTrue("$rounds rounds", rounds in 4..8)
        assertTrue(Live.log().any { it.endsWith("no live stream found (av4: 404, av2: 404, v1: 404, v2: 404, v3: 404, av1: 404, av3: 404); trying again in 300 ms") })
    }

    @Test(timeout = 15_000)
    fun noAnswerOnPort554_isSaidSo_andNotAskedOncePerStream() {
        val h = live()
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        Live.sockets = object : LiveSockets {
            override fun tcp(host: String, port: Int, timeoutMs: Int): Socket {
                asked.add("$host:$port in $timeoutMs ms")
                throw ConnectException("Connection refused")
            }

            override fun udp(): DatagramSocket = throw IOException("not here")
        }
        h.start()
        h.awaitPhase(Live.Phase.UNAVAILABLE)
        assertEquals("the camera did not answer on port 554", h.last()!!.detail)
        assertEquals("one connection per round: no other stream would answer either", listOf("127.0.0.1:554 in 3000 ms"), asked)
    }

    @Test(timeout = 15_000)
    fun aCameraThatWantsALogin_isSaidSo() {
        val h = live()
        h.h264()
        h.rtsp.auth = true
        h.start()
        h.awaitPhase(Live.Phase.UNAVAILABLE)
        assertEquals("the camera wants a password for live view", h.last()!!.detail)
        assertTrue(h.rtsp.requests("SETUP").isEmpty())
        // The diagnostics report leaves out every line that looks like it is about a password: these must stay in.
        val log = Live.log()
        assertTrue(log.any { it.contains("the camera asks for a login for live view (av4: 401, av2: 404") })
        assertTrue(log.filter { "login" in it }.all { Diag.redact(it) == it })
    }

    @Test(timeout = 15_000)
    fun waitsForTheCamerasAddress() {
        val h = live()
        h.h264()
        h.host = null
        h.start()
        h.await("waiting") { h.last()?.detail == "waiting for the camera's address" }
        Thread.sleep(200)
        assertTrue(h.rtsp.connects.isEmpty())
        assertEquals(Live.Phase.STARTING, h.last()!!.phase)
        h.host = "127.0.0.1"
        h.awaitPlaying()
    }

    @Test(timeout = 15_000)
    fun whatWorkedLastTime_isTriedFirstTheNextTime() {
        val h = live()
        h.h264("/liveRTSP/v2")
        h.rtsp.tcp = false
        h.start()
        h.awaitFrames(5)
        val before = h.rtsp.requests.size
        Cam.session { Thread.sleep(100) }
        h.await("playing again") { h.rtsp.requests("PLAY").size == 2 }
        val again = h.rtsp.requests.drop(before)
        assertEquals("/liveRTSP/v2", again.first { it.method == "DESCRIBE" }.path)
        val setups = again.filter { it.method == "SETUP" }
        assertEquals("straight to UDP", 1, setups.size)
        assertTrue(setups[0].headers["transport"]!!.startsWith("RTP/AVP;unicast;client_port="))
    }

    // ---------------------------------------------------------------- sharing the camera

    @Test(timeout = 15_000)
    fun givesTheCameraUp_whenSomethingElseQueues_andComesBackByItself() {
        val h = live("Camera.Preview.RTSP.av=4\n")
        h.h264()
        h.start()
        h.awaitFrames(10)
        val asked = System.currentTimeMillis()
        val waited = AtomicReference<Long>()
        val teardowns = AtomicInteger()
        val other = Thread {
            Cam.session {
                waited.set(System.currentTimeMillis() - asked)
                teardowns.set(h.rtsp.requests("TEARDOWN").size)
                Thread.sleep(300)
            }
        }
        other.start()
        other.join(5000)
        assertTrue("a download had to wait ${waited.get()} ms", waited.get() < 1000)
        assertEquals("the session was ended before the camera was handed over", 1, teardowns.get())
        assertTrue(busy(h))
        h.await("playing again") { h.rtsp.requests("PLAY").size == 2 && h.sink.formats.size == 2 }
        h.awaitPhase(Live.Phase.PLAYING)
        assertTrue(Live.log().any { it.endsWith("av4: paused, camera needed elsewhere") })
        assertEquals("the settings are read once per run", 1, h.cam!!.requests.count { it.target == Cam.PREVIEW })
        assertEquals("onFormat comes again before the new stream's first frame", listOf("format", "frame"), h.sink.calls.drop(h.sink.calls.lastIndexOf("format")).take(2))
    }

    @Test(timeout = 15_000)
    fun neverTakesTheCamera_whileSomethingElseHoldsIt() {
        val h = live()
        h.h264()
        val holding = CountDownLatch(1)
        val queued = AtomicInteger()
        val holder = Thread {
            Cam.session {
                holding.countDown()
                val until = System.currentTimeMillis() + 800
                while (System.currentTimeMillis() < until) {
                    if (Cam.othersWaiting()) queued.incrementAndGet()
                    Thread.sleep(5)
                }
            }
        }
        holder.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        h.start()
        h.await("PAUSED_BUSY") { busy(h) }
        assertTrue("nothing was asked of the camera meanwhile", h.rtsp.connects.isEmpty())
        holder.join(5000)
        assertEquals("the live view never queued up for the camera", 0, queued.get())
        h.awaitPlaying()
    }

    @Test(timeout = 15_000)
    fun aRequestDuringTheSearch_getsTheCameraToo() {
        val h = live()
        h.h264()
        h.rtsp.replyDelayMs = 400                                   // a slow camera: each answer takes 0.4 s
        h.start()
        h.await("the first request") { h.rtsp.requests.isNotEmpty() }
        val asked = System.currentTimeMillis()
        val waited = AtomicReference<Long>()
        Cam.session { waited.set(System.currentTimeMillis() - asked) }
        assertTrue("waited ${waited.get()} ms", waited.get() < 300)
        h.await("PAUSED_BUSY") { busy(h) }
    }

    // ---------------------------------------------------------------- ends and retries

    @Test(timeout = 15_000)
    fun aStreamThatEnds_startsOverAtOnce_thenWithABackOff() {
        val h = live()
        h.h264(frames = 15)                                         // the camera closes the stream after 15 pictures
        h.start()
        h.await("three streams") { h.rtsp.requests("PLAY").size >= 3 }
        val stops = h.statuses.filter { it.phase == Live.Phase.STARTING && it.detail.startsWith("the stream stopped") }.map { it.detail }
        assertTrue(stops.toString(), stops[0].endsWith("starting again"))
        assertTrue(stops.toString(), stops[1].endsWith("trying again in 150 ms"))
        assertTrue(stops[0].contains("the camera closed the connection"))
        assertTrue("every stream starts with onFormat", h.sink.formats.size >= 2)
    }

    @Test(timeout = 15_000)
    fun aSinkThatFails_doesNotEndTheRun() {
        val h = live()
        h.h264()
        h.sink.failAt = 3
        h.start()
        h.awaitFrames(20)
        assertTrue(Live.log().any { it.contains("IllegalStateException: the decoder gave up") })
        assertEquals(2, h.rtsp.requests("PLAY").size)
        h.stop()
        assertNull(h.error)
    }

    // ---------------------------------------------------------------- stopping

    /** Runs [setUp], starts, waits for [ready], then stops: that must take less than half a second. */
    private fun stopsQuickly(what: String, setUp: (LiveHarness) -> Unit = {}, ready: (LiveHarness) -> Boolean) {
        val h = LiveHarness()
        try {
            setUp(h)
            h.start()
            h.await(what) { ready(h) }
            val took = h.stop()
            assertTrue("$what: stopped in $took ms", took < 500)
            assertEquals(Live.Phase.STOPPED, h.last()!!.phase)
        } finally {
            h.close()
        }
    }

    @Test(timeout = 15_000)
    fun stopIsQuick_whateverRunIsDoing() {
        stopsQuickly("waiting for the camera's address", { it.host = null }) { it.last() != null }
        stopsQuickly("backing off", { LiveTiming.backOffMs = longArrayOf(10_000) }) { it.last()?.phase == Live.Phase.UNAVAILABLE }
        stopsQuickly("waiting for a slow camera", { it.h264(); it.rtsp.replyDelayMs = 3000 }) { it.rtsp.requests.isNotEmpty() }
        stopsQuickly("playing", { it.h264() }) { it.sink.frames.size >= 5 }
        // Waiting for the camera to be free.
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            Cam.session {
                holding.countDown()
                release.await()
            }
        }
        holder.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        try {
            stopsQuickly("waiting for the camera to be free") { busy(it) }
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test(timeout = 15_000)
    fun stopIsQuick_evenWhileTheCameraTakesItsTimeToConnect() {
        val h = live()
        h.h264()
        val entered = CountDownLatch(1)
        val late = AtomicReference<Socket>()
        val real = h.rtsp.sockets()
        Live.sockets = object : LiveSockets {
            override fun tcp(host: String, port: Int, timeoutMs: Int): Socket {
                entered.countDown()
                Thread.sleep(600)                                   // a SYN that takes its time
                return real.tcp(host, port, timeoutMs).also { late.set(it) }
            }

            override fun udp(): DatagramSocket = real.udp()
        }
        h.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(h.stop() < 500)
        // The connection that came after nobody wanted it is not left open.
        val until = System.currentTimeMillis() + 3000
        while (late.get() == null && System.currentTimeMillis() < until) Thread.sleep(10)
        Thread.sleep(50)
        assertTrue(late.get()!!.isClosed)
    }

    @Test(timeout = 15_000)
    fun anInterrupt_endsTheRunCleanly() {
        val h = live()
        h.h264()
        h.start()
        h.awaitFrames(5)
        val t0 = System.currentTimeMillis()
        h.thread!!.interrupt()
        h.thread!!.join(5000)
        assertFalse(h.thread!!.isAlive)
        assertTrue(System.currentTimeMillis() - t0 < 500)
        assertEquals(Live.Phase.STOPPED, h.last()!!.phase)
        assertEquals("TEARDOWN", h.rtsp.requests.last().method)
    }

    // ---------------------------------------------------------------- what it reports while playing

    @Test(timeout = 15_000)
    fun whilePlaying_fpsAndKbps_atMostTwiceASecond() {
        val h = live()
        h.h264()
        h.start()
        h.awaitPlaying()
        val from = h.statuses.size
        Thread.sleep(2200)
        val playing = h.statuses.drop(from).filter { it.phase == Live.Phase.PLAYING }
        assertTrue("${playing.size} updates in 2.2 s", playing.size in 3..6)
        val s = playing.last()
        assertEquals("/liveRTSP/av4", s.stream)
        assertEquals("TCP", s.transport)
        assertTrue("fps ${s.fps}", s.fps in 35f..65f)
        assertTrue("kbps ${s.kbps}", s.kbps in 100..3000)
        assertFalse(s.lagging)
    }

    @Test(timeout = 15_000)
    fun statusNews_atMostTwiceASecond_butNotLost() {
        // Every stream before v3 is missing and the camera takes 0.15 s per answer: news of each try piles up.
        val h = live()
        h.h264("/liveRTSP/v3")
        h.rtsp.replyDelayMs = 150
        h.start()
        h.awaitPlaying(10_000)
        val s = h.statuses.toList()
        val t = h.statusTimes.toList()
        for (i in 1 until s.size) {
            if (s[i].phase == s[i - 1].phase) assertTrue("${s[i - 1].detail} -> ${s[i].detail} after ${t[i] - t[i - 1]} ms", t[i] - t[i - 1] >= 450)
        }
        val tries = s.filter { it.phase == Live.Phase.STARTING }.map { it.detail }
        assertEquals("trying av4 over TCP", tries.first())
        assertTrue("later tries were reported too: $tries", tries.size >= 2 && tries.last() != tries.first())
    }

    @Test(timeout = 15_000)
    fun lagging_whenPicturesArriveFasterThanTheyLast() {
        val h = live()
        val s = h.h264()
        h.rtsp.streams["/liveRTSP/av4"] = FakeRtsp.Stream("H264", FakeRtsp.h264(fps = 10)).also {
            it.sps = s.sps
            it.pps = s.pps
            it.intervalMs = 20                                      // five times faster than the timestamps say
        }
        h.start()
        h.await("lagging") { h.statuses.any { it.phase == Live.Phase.PLAYING && it.lagging } }
    }

    @Test(timeout = 15_000)
    fun lagging_whenTheSinkIsSlowerThanThePictures() {
        val h = live()
        h.h264()
        h.sink.delayMs = 40                                         // pictures come every 20 ms
        h.start()
        h.await("lagging") { h.statuses.any { it.phase == Live.Phase.PLAYING && it.lagging } }
    }

    // ---------------------------------------------------------------- the log

    @Test
    fun log_keepsTheLastSixtyLines_fromAnyThread() {
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(200) {
                    LiveLog.add("thread $t line $it")
                    Live.log()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val log = Live.log()
        assertEquals(60, log.size)
        assertTrue(log.all { it.matches(Regex("\\d\\d:\\d\\d:\\d\\d thread \\d line \\d+")) })
        assertTrue("the newest last", log.any { it.endsWith(" line 199") })
        LiveLog.add("x".repeat(1000))
        assertTrue("a line from the camera cannot grow the report without end", Live.log().last().length < 320)
    }
}
