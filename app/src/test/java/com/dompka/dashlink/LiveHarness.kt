package com.dompka.dashlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** A sink that keeps everything it is given, and can be made slow or made to fail. */
internal class RecordingSink : VideoSink {
    class Frame(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    val formats = CopyOnWriteArrayList<VideoFormat>()
    val frames = CopyOnWriteArrayList<Frame>()

    /** "format" and "frame", in the order they were called. */
    val calls = CopyOnWriteArrayList<String>()

    /** The thread each call came on. */
    val threads = CopyOnWriteArrayList<Thread>()

    @Volatile
    var delayMs = 0L

    /** Throw on the frame with this number (counting from 1); 0 never. */
    @Volatile
    var failAt = 0

    override fun onFormat(format: VideoFormat) {
        threads.add(Thread.currentThread())
        calls.add("format")
        formats.add(format)
    }

    override fun onFrame(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean) {
        threads.add(Thread.currentThread())
        calls.add("frame")
        frames.add(Frame(data.copyOf(length), ptsUs, keyFrame))
        if (frames.size == failAt) throw IllegalStateException("the decoder gave up")
        if (delayMs > 0) Thread.sleep(delayMs)
    }
}

/**
 * Runs [Live.run] on a thread of its own against [rtsp] (and [cam] for the camera's settings, when given), records
 * what it reports, and puts every global it changed back when closed. Waits are short, so that a back-off or a
 * missing picture takes a fraction of a second here.
 */
internal class LiveHarness(props: String? = null) : Closeable {
    val rtsp = FakeRtsp()
    val cam: FakeCam? = props?.let { p -> FakeCam().also { it.props = "0\nOK\n$p" } }
    val sink = RecordingSink()
    val statuses = CopyOnWriteArrayList<Live.Status>()

    /** The thread each status came on, and when. */
    val statusThreads = CopyOnWriteArrayList<Thread>()
    val statusTimes = CopyOnWriteArrayList<Long>()

    @Volatile
    var host: String? = "127.0.0.1"

    @Volatile
    var stop = false

    @Volatile
    var error: Throwable? = null
        private set

    var thread: Thread? = null
        private set

    private val oldSockets = Live.sockets
    private val oldHost = Live.host
    private val oldOpener = Cam.opener
    private val oldTimes = listOf(
        LiveTiming.firstPictureMs, LiveTiming.silenceMs, LiveTiming.replyMs, LiveTiming.busyMs,
        LiveTiming.afterOthersMs, LiveTiming.hostMs, LiveTiming.steadyMs,
    )
    private val oldBackOff = LiveTiming.backOffMs

    init {
        Live.sockets = rtsp.sockets()
        Live.host = { host }
        Cam.opener = cam?.opener() ?: Cam.Opener { throw IOException("no camera settings in this test") }
        LiveTiming.firstPictureMs = 1000
        LiveTiming.silenceMs = 1000
        LiveTiming.replyMs = 1000
        LiveTiming.busyMs = 100
        LiveTiming.afterOthersMs = 200
        LiveTiming.hostMs = 50
        LiveTiming.backOffMs = longArrayOf(150, 300, 450, 600)
    }

    /** An H.264 stream at [path]: 50 pictures a second, an IDR every ten, parameter sets in the SDP and in-band. */
    fun h264(path: String = "/liveRTSP/av4", frames: Int = Int.MAX_VALUE): FakeRtsp.Stream {
        val sps = FakeRtsp.sps(640, 360)
        val s = FakeRtsp.Stream("H264", FakeRtsp.h264(sps = sps, frames = frames))
        s.sps = sps
        s.pps = FakeRtsp.PPS
        rtsp.streams[path] = s
        return s
    }

    fun start(): LiveHarness {
        val t = Thread({
            try {
                Live.run(sink, { stop }) {
                    statusThreads.add(Thread.currentThread())
                    statusTimes.add(System.currentTimeMillis())
                    statuses.add(it)
                }
            } catch (e: Throwable) {
                error = e
            }
        }, "live-under-test")
        thread = t
        t.start()
        return this
    }

    fun phases(): List<Live.Phase> = statuses.map { it.phase }

    fun last(): Live.Status? = statuses.lastOrNull()

    /** Waits up to [ms] for [cond]; fails with what happened so far if it does not come true. */
    fun await(what: String, ms: Long = 5000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > until) {
                fail("$what did not happen in $ms ms.\nstatuses: ${statuses.map { "${it.phase} ${it.detail}" }}\nrequests: ${rtsp.requests}\nlog: ${Live.log().takeLast(15)}")
            }
            Thread.sleep(5)
        }
    }

    fun awaitPlaying(ms: Long = 5000) = await("PLAYING", ms) { statuses.any { it.phase == Live.Phase.PLAYING } }

    fun awaitFrames(n: Int, ms: Long = 5000) = await("$n frames", ms) { sink.frames.size >= n }

    fun awaitPhase(phase: Live.Phase, ms: Long = 5000) = await("$phase", ms) { statuses.lastOrNull()?.phase == phase }

    /** Asks run() to stop; returns how long it took to return. */
    fun stop(): Long {
        val t0 = System.currentTimeMillis()
        stop = true
        thread?.join(5000)
        val took = System.currentTimeMillis() - t0
        assertFalse("run() did not return", thread?.isAlive == true)
        return took
    }

    override fun close() {
        stop = true
        thread?.join(5000)
        val alive = thread?.isAlive == true
        rtsp.close()
        cam?.close()
        Live.sockets = oldSockets
        Live.host = oldHost
        Cam.opener = oldOpener
        LiveTiming.firstPictureMs = oldTimes[0]
        LiveTiming.silenceMs = oldTimes[1]
        LiveTiming.replyMs = oldTimes[2]
        LiveTiming.busyMs = oldTimes[3]
        LiveTiming.afterOthersMs = oldTimes[4]
        LiveTiming.hostMs = oldTimes[5]
        LiveTiming.steadyMs = oldTimes[6]
        LiveTiming.backOffMs = oldBackOff
        assertFalse("run() was still running", alive)
        assertNull("run() threw", error)
        assertTrue("requests a read-only client must never send: ${rtsp.violations}", rtsp.violations.isEmpty())
        cam?.let { assertTrue("not a read: ${it.violations.map { v -> v.target }}", it.violations.isEmpty()) }
    }
}
