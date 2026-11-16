package com.dompka.dashlink

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * How long the live view waits for things. These are the values the app runs with; tests shorten some of them, so
 * that a back-off of half a minute does not take half a minute.
 */
internal object LiveTiming {
    /** The first picture must arrive this soon after PLAY was answered, or the stream counts as not working. */
    @Volatile
    var firstPictureMs = 6_000L

    /** No packet at all for this long ends a stream. */
    @Volatile
    var silenceMs = 5_000L

    /** The camera's answer to each request must arrive within this. */
    @Volatile
    var replyMs = 5_000L

    /** Waits after rounds in which no stream worked: 5, 10, 20 s, then 30 s every time. */
    @Volatile
    var backOffMs = longArrayOf(5_000, 10_000, 20_000, 30_000)

    /** How long to wait before asking a busy camera again. */
    @Volatile
    var busyMs = 1_000L

    /** How long to leave the camera alone once whatever wanted it has it. */
    @Volatile
    var afterOthersMs = 1_000L

    /** How often to look whether the camera's address is known yet. */
    @Volatile
    var hostMs = 500L

    /** A stream that played this long has shown that it works: when it ends, the next try is straight away. */
    @Volatile
    var steadyMs = 10_000L
}

/** A Camera.Preview.* setting, whatever the case of its name; null if the camera does not have it. */
internal fun previewProp(props: Map<String, String>, key: String): String? =
    props[key] ?: props.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

/** The last lines about live-view attempts, for the diagnostics report. */
internal object LiveLog {
    private const val MAX = 60
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        val stamped = String.format(Locale.US, "%tT ", System.currentTimeMillis()) + line.take(300)
        synchronized(lines) {
            lines.addLast(stamped)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun lines(): List<String> = synchronized(lines) { lines.toList() }
}

/**
 * One call of [Live.run]: find a stream that works, play it into the sink, give the camera up as soon as something
 * else needs it, and come back when it is free again.
 *
 * The camera is only ever taken with Cam.session(0): a zero wait never queues, so the live view never makes anything
 * else wait for its turn. While it holds the camera it asks [Cam.othersWaiting] (and [stop]) at least every 50 ms,
 * and gives the camera up the moment someone queues for it: TEARDOWN, close, and out of the session.
 *
 * Everything runs on the caller's thread, the "streaming thread": [sink] and [status] are only ever called on it.
 * Only the TCP connect, which can take seconds, runs on a helper thread, so that stop() is heard meanwhile.
 */
internal class LiveRun(
    private val sink: VideoSink,
    private val stop: () -> Boolean,
    private val status: (Live.Status) -> Unit,
) {
    /** Camera.Preview.*, read once per run. */
    private var props: Map<String, String>? = null

    /** Rounds in a row in which nothing played; picks the next back-off. */
    private var failures = 0

    /** A stream that ends by itself may be retried at once - once, until a stream shows it can play for a while. */
    private var quickRetry = true

    /** The path and transport (true for TCP) that played last: tried first next time. */
    private var preferred: Pair<String, Boolean>? = null

    /** The stream being tried or played ("av4"), for the log. */
    private var current = ""
    private var last: Live.Status? = null
    private var lastAt = 0L

    /** News within the same phase that came too soon after the last status; it goes out as soon as it may. */
    private var pending: Live.Status? = null

    /** Thrown to end what is being done at once: stop() said so, or the camera is needed elsewhere. */
    private class Leave(val stopping: Boolean) : Exception()

    /** Asked between any two waits while the camera is held. */
    private val turn: () -> Unit = {
        flush()
        if (stopping()) throw Leave(true)
        if (Cam.othersWaiting()) throw Leave(false)
    }

    fun run() {
        try {
            loop()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            report(Live.Phase.STOPPED, "stopped")
        }
    }

    private fun stopping(): Boolean = stop() || Thread.currentThread().isInterrupted

    private fun loop() {
        while (!stopping()) {
            val host = Live.host()
            if (host == null) {
                report(Live.Phase.STARTING, "waiting for the camera's address")
                pause(LiveTiming.hostMs)
                continue
            }
            val outcome = try {
                Cam.session(0) { round(host) }
            } catch (e: Leave) {
                if (e.stopping) return
                Outcome.Yielded
            } catch (e: Cam.BusyException) {
                report(Live.Phase.PAUSED_BUSY, BUSY)
                pause(LiveTiming.busyMs)
                continue
            } catch (e: IOException) {
                // Cam.session only throws this when the thread was interrupted while asking for the camera.
                if (stopping()) return
                Outcome.Failed("could not ask for the camera: ${Cam.describe(e)}")
            }
            when (outcome) {
                Outcome.Yielded -> giveWay()
                is Outcome.Ended -> {
                    if (outcome.playedMs >= LiveTiming.steadyMs) quickRetry = true
                    if (quickRetry) {
                        quickRetry = false
                        report(Live.Phase.STARTING, "the stream stopped (${outcome.why}), starting again")
                    } else {
                        val wait = backOff()
                        report(Live.Phase.STARTING, "the stream stopped (${outcome.why}), trying again in ${seconds(wait)}")
                        pause(wait)
                    }
                }
                is Outcome.Failed -> {
                    val wait = backOff()
                    LiveLog.add("${outcome.line}; trying again in ${seconds(wait)}")
                    report(Live.Phase.UNAVAILABLE, outcome.reason)
                    pause(wait)
                }
            }
        }
    }

    private sealed class Outcome {
        /** Something else needed the camera, and got it. */
        object Yielded : Outcome()

        /** A stream played, then ended by itself. */
        class Ended(val why: String, val playedMs: Long) : Outcome()

        /**
         * Nothing played; [reason] says why, in words for the screen. [line] says it for the log, in words the
         * diagnostics report does not take for a password and leave out.
         */
        class Failed(val reason: String, val line: String = reason) : Outcome()
    }

    private fun backOff(): Long {
        val steps = LiveTiming.backOffMs
        return steps[minOf(failures++, steps.size - 1)]
    }

    private fun seconds(ms: Long) = if (ms < 1000) "$ms ms" else "${ms / 1000} s"

    /** Waits [ms], or less when stop() says so. */
    private fun pause(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (!stopping()) {
            flush()
            val left = until - System.currentTimeMillis()
            if (left <= 0) return
            Thread.sleep(minOf(left, RtspConnection.POLL_MS))
        }
    }

    /** The camera was given up because someone queued for it: wait until they have it, and a little longer. */
    private fun giveWay() {
        LiveLog.add("$current: paused, camera needed elsewhere")
        report(Live.Phase.PAUSED_BUSY, BUSY)
        while (Cam.othersWaiting() && !stopping()) pause(RtspConnection.POLL_MS)
        pause(LiveTiming.afterOthersMs)
    }

    private fun report(
        phase: Live.Phase,
        detail: String,
        stream: String? = null,
        transport: String? = null,
        fps: Float = 0f,
        kbps: Int = 0,
        lagging: Boolean = false,
    ) {
        val l = last
        if (phase != Live.Phase.PLAYING && l != null && l.phase == phase && l.detail == detail) {
            pending = null
            return
        }
        val s = Live.Status(phase, detail, stream, transport, fps, kbps, lagging)
        // A new phase goes out at once; news within the same phase at most twice a second, the latest one kept.
        if (l != null && l.phase == phase && System.currentTimeMillis() - lastAt < STATUS_MS) {
            pending = s
            return
        }
        send(s)
    }

    private fun send(s: Live.Status) {
        pending = null
        last = s
        lastAt = System.currentTimeMillis()
        status(s)
    }

    /** Sends the held-back status once it may go out; every wait calls this. */
    private fun flush() {
        val p = pending ?: return
        if (System.currentTimeMillis() - lastAt >= STATUS_MS) send(p)
    }

    // ---------------------------------------------------------------- one round: every stream until one plays

    /** Runs inside the camera's session. */
    private fun round(host: String): Outcome {
        val settings = props ?: readProps().also { props = it }
        val paths = Live.candidates(settings)
        val first = preferred?.takeIf { it.first in paths }
        val order = if (first == null) paths else listOf(first.first) + (paths - first.first)
        val failed = ArrayList<Pair<String, Result>>()
        for (path in order) {
            val tcpFirst = first?.takeIf { it.first == path }?.second ?: true
            var r = attempt(host, path, tcpFirst, settings)
            if (r.kind.otherTransport && !r.triedBoth) {
                val again = attempt(host, path, !tcpFirst, settings)
                r = if (again.kind == Kind.PLAYED || again.kind == Kind.NO_CONNECT || again.short == r.short) {
                    again
                } else {
                    Result(again.kind, "${transport(tcpFirst)} ${r.short}, ${transport(!tcpFirst)} ${again.short}")
                }
            }
            when (r.kind) {
                Kind.PLAYED -> return Outcome.Ended(r.short, r.playedMs)
                Kind.NO_CONNECT -> return Outcome.Failed(r.short)       // no other path will answer either
                else -> failed.add(path to r)
            }
        }
        return summary(failed)
    }

    private fun readProps(): Map<String, String> = try {
        Cam.previewProps().also { p ->
            // Only the preview settings: a camera that answers with everything it has (as the fake one does)
            // must not put its Wi-Fi key into the log, even though the report redacts it again.
            val preview = p.entries.filter { it.key.startsWith("Camera.Preview.", ignoreCase = true) }
            LiveLog.add("live view settings: " + preview.joinToString { "${it.key.substring("Camera.Preview.".length)}=${it.value}" }.ifEmpty { "none" })
        }
    } catch (e: IOException) {
        LiveLog.add("live view settings could not be read: ${Cam.describe(e)}")
        emptyMap()
    } catch (e: RuntimeException) {
        LiveLog.add("live view settings could not be read: ${Cam.describe(e)}")
        emptyMap()
    }

    /** The most useful thing to say when every stream failed. */
    private fun summary(failed: List<Pair<String, Result>>): Outcome.Failed {
        val list = failed.joinToString(", ") { "${it.first.substringAfterLast('/')}: ${it.second.short}" }
        if (failed.any { it.second.kind == Kind.LOGIN }) {
            return Outcome.Failed("the camera wants a password for live view", "the camera asks for a login for live view ($list)")
        }
        return Outcome.Failed(if (failed.all { it.second.kind == Kind.MISSING }) "no live stream found ($list)" else "no live stream worked ($list)")
    }

    private fun attempt(host: String, path: String, tcp: Boolean, settings: Map<String, String>): Result {
        val a = Attempt(host, path, tcp, settings)
        try {
            return a.run()
        } finally {
            a.close()
        }
    }

    private fun transport(tcp: Boolean) = if (tcp) "TCP" else "UDP"

    /** How an attempt ended. [otherTransport]: the same path may still work over the other transport. */
    private enum class Kind(val otherTransport: Boolean) {
        PLAYED(false),
        NO_CONNECT(false),
        MISSING(false),
        LOGIN(false),
        REFUSED(false),
        NO_PICTURE(true),
        BROKEN(true),
    }

    /** [short] goes into the summary (or is the reason, for NO_CONNECT; why it ended, for PLAYED). */
    private class Result(val kind: Kind, val short: String, val playedMs: Long = 0, val triedBoth: Boolean = false)

    // ---------------------------------------------------------------- one stream path over one transport

    private inner class Attempt(
        private val host: String,
        private val path: String,
        private var tcp: Boolean,
        private val settings: Map<String, String>,
    ) {
        private val name = path.substringAfterLast('/')
        private var conn: RtspConnection? = null
        private val udp = ArrayList<DatagramSocket>()
        private var aggregate = Rtsp.url(host, path)
        private var keepAlive = "OPTIONS"
        private var keepAliveMs = 30_000L
        private var keepAliveCseq = -1
        private var rtpChannel = 0
        private var depacketizer: RtpDepacketizer? = null
        private var format: VideoFormat? = null
        private var meter = Meter(0)
        private var started = 0L
        private var lastPacket = 0L
        private var triedBoth = false
        private val tag get() = "$name ${transport(tcp)}"

        fun run(): Result {
            current = name
            report(Live.Phase.STARTING, "trying $name over ${transport(tcp)}")
            val url = Rtsp.url(host, path)
            val c = try {
                connect()
            } catch (e: IOException) {
                LiveLog.add("$tag: no connection (${Cam.describe(e)})")
                val why = if (e is SocketException || e is SocketTimeoutException) "the camera did not answer on port ${Rtsp.PORT}" else e.message ?: Cam.describe(e)
                return Result(Kind.NO_CONNECT, why)
            }
            conn = c
            var step = "OPTIONS"
            try {
                val options = c.call("OPTIONS", url, emptyList(), LiveTiming.replyMs, turn)
                if (options.header("public")?.split(',')?.any { it.trim().equals("GET_PARAMETER", ignoreCase = true) } == true) {
                    keepAlive = "GET_PARAMETER"
                }
                step = "DESCRIBE"
                val describe = c.call("DESCRIBE", url, listOf("Accept" to "application/sdp"), LiveTiming.replyMs, turn)
                when (describe.status) {
                    200 -> Unit
                    401, 403 -> return result(Kind.LOGIN, "DESCRIBE ${describe.status}, the camera asks for a login", "${describe.status}")
                    else -> return result(Kind.MISSING, "DESCRIBE ${describe.status}", "${describe.status}")
                }
                val sdp = Sdp.parse(String(describe.body, Charsets.UTF_8))
                val video = sdp.video() ?: return result(
                    Kind.MISSING,
                    "no H.264 or JPEG video in the SDP (${sdp.media.joinToString { it.type + " " + it.rtpmap.values.joinToString("/") }.take(120)})",
                    "no H.264 or JPEG video",
                )
                val base = base(describe, url)
                aggregate = sdp.sessionControl?.let { Rtsp.resolve(base, it) }?.takeIf { Rtsp.allowed(it, host, path) } ?: base
                step = "SETUP"
                val setup = setUpVideo(c, Rtsp.resolve(base, video.media.control))
                if (setup.status != 200) return result(Kind.BROKEN, "SETUP ${setup.status}", "SETUP ${setup.status}")
                if (!session(c, setup)) return result(Kind.BROKEN, "SETUP answered without a usable session", "no session")
                depacketizer = when (video.codec) {
                    Sdp.Codec.H264 -> H264Depacketizer(video.payloadType, video.clockRate, video.sps, video.pps)
                    Sdp.Codec.JPEG -> JpegDepacketizer(video.payloadType, video.clockRate)
                }.also { it.out = RtpDepacketizer.Out(::picture) }
                step = "PLAY"
                val range = listOf("Range" to "npt=0.000-")
                var play = c.call("PLAY", aggregate, range, LiveTiming.replyMs, turn, ::packet)
                if ((play.status == 455 || play.status == 459) && setUpOthers(c, sdp, base, video.media)) {
                    LiveLog.add("$tag: PLAY ${play.status}, set up the other tracks too")
                    play = c.call("PLAY", aggregate, range, LiveTiming.replyMs, turn, ::packet)
                }
                if (play.status != 200) return result(Kind.BROKEN, "PLAY ${play.status}", "PLAY ${play.status}")
                step = "the stream"
                return stream(c)
            } catch (e: Cam.RefusedException) {
                return result(Kind.REFUSED, "$step ${e.message}", "refused to $step there")
            } catch (e: IOException) {
                return failure(step, e)
            } catch (e: RuntimeException) {
                // Something in the camera's data that the parsers did not expect, or the sink failing on a picture.
                return failure(step, e)
            }
        }

        private fun failure(step: String, e: Exception): Result {
            if (started > 0) return ended(Cam.describe(e))
            return result(Kind.BROKEN, "$step: ${Cam.describe(e)}", "$step failed")
        }

        private fun result(kind: Kind, detail: String, short: String): Result {
            LiveLog.add("$tag: $detail")
            return Result(kind, short, triedBoth = triedBoth)
        }

        private fun ended(why: String): Result {
            if (started == 0L) return result(Kind.BROKEN, why, why)
            val played = System.currentTimeMillis() - started
            LiveLog.add("$tag: stream ended after ${seconds(played)}: $why")
            return Result(Kind.PLAYED, why, played)
        }

        /** Where relative control URLs start: Content-Base, else Content-Location, else the URL asked for. */
        private fun base(r: RtspResponse, url: String): String {
            for (name in listOf("content-base", "content-location")) {
                val v = r.header(name)?.trim() ?: continue
                if (Rtsp.allowed(v, host, path)) return v
                LiveLog.add("$tag: $name is not this stream, ignored: ${v.take(120)}")
            }
            return url
        }

        /**
         * SETUP of the video track. A camera that refuses the transport asked for - or answers with another one - is
         * asked again on the same connection with the other transport.
         */
        private fun setUpVideo(c: RtspConnection, control: String): RtspResponse {
            val r = setUp(c, control, 0)
            val wrong = r.status == 200 && !transportMatches(r)
            if (r.status !in REFUSED_TRANSPORT && !wrong) return r
            if (wrong) {
                // It set something up, just not what was asked for: end that before asking again.
                if (session(c, r)) teardown(c)
                c.session = null
            }
            LiveLog.add("$tag: SETUP ${r.status}${if (wrong) " with another transport" else ""}, trying ${transport(!tcp)}")
            tcp = !tcp
            triedBoth = true
            return setUp(c, control, 0)
        }

        private fun setUp(c: RtspConnection, control: String, channel: Int): RtspResponse {
            val transport = if (tcp) {
                "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"
            } else {
                val rtp = udpSocket()
                val rtcp = udpSocket()
                "RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}"
            }
            return c.call("SETUP", control, listOf("Transport" to transport), LiveTiming.replyMs, turn)
        }

        private fun udpSocket(): DatagramSocket {
            val s = Live.sockets.udp()
            udp.add(s)
            s.soTimeout = RtspConnection.POLL_MS.toInt()
            try {
                s.receiveBufferSize = 1024 * 1024                 // a key frame arrives as a burst of packets
            } catch (e: SocketException) {
                // the system's default buffer will have to do
            }
            return s
        }

        private fun transportMatches(r: RtspResponse): Boolean {
            val t = r.header("transport") ?: return true
            val interleaved = t.contains("/TCP", ignoreCase = true) || t.contains("interleaved", ignoreCase = true)
            return interleaved == tcp
        }

        /** Takes the session id, keep-alive interval and interleaved channel from SETUP's answer. */
        private fun session(c: RtspConnection, setup: RtspResponse): Boolean {
            val header = setup.header("session") ?: return false
            val id = header.substringBefore(';').trim()
            if (id.isEmpty() || id.length > 256 || id.any { it.code !in 0x21..0x7e || it == ',' }) return false
            c.session = id
            val timeout = header.split(';').drop(1).map { it.trim() }
                .firstOrNull { it.startsWith("timeout=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val prop = previewProp(settings, "Camera.Preview.RTSP.keepalive")?.toIntOrNull()?.takeIf { it > 0 }
            keepAliveMs = maxOf(500L, minOf(timeout ?: 60, prop ?: 60, 60) * 500L)
            if (tcp) {
                rtpChannel = setup.header("transport")?.split(';')?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("interleaved=", ignoreCase = true) }
                    ?.substringAfter('=')?.substringBefore('-')?.trim()?.toIntOrNull() ?: 0
            }
            return true
        }

        /** For a camera that only plays once every track is set up: the others too (their data is ignored). */
        private fun setUpOthers(c: RtspConnection, sdp: Sdp, base: String, video: Sdp.Media): Boolean {
            var any = false
            var channel = 2
            for (m in sdp.media) {
                if (m === video) continue
                try {
                    if (setUp(c, Rtsp.resolve(base, m.control), channel).status == 200) any = true
                } catch (e: Cam.RefusedException) {
                    LiveLog.add("$tag: ${e.message}")
                }
                channel += 2
            }
            return any
        }

        /** RTP from the camera, interleaved or by UDP: only the video channel counts. */
        private fun packet(channel: Int, buf: ByteArray, offset: Int, length: Int) {
            if (channel != rtpChannel) return
            val now = System.currentTimeMillis()
            lastPacket = now
            meter.bytes(length, now)
            depacketizer?.push(buf, offset, length)
        }

        /** A whole picture from the depacketizer. */
        private fun picture(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean) {
            val first = started == 0L
            if (first) {
                val f = depacketizer!!.format()
                format = f
                sink.onFormat(f)
            }
            val t0 = System.nanoTime()
            sink.onFrame(data, length, ptsUs, keyFrame)
            val took = System.nanoTime() - t0
            val now = System.currentTimeMillis()
            if (first) {
                started = now
                meter = Meter(now)
                failures = 0
                preferred = path to tcp
                val what = describe(format!!)
                LiveLog.add("$tag: $what, playing")
                report(Live.Phase.PLAYING, what, path, transport(tcp))
            }
            meter.picture(ptsUs, took, now)
        }

        private fun stream(c: RtspConnection): Result {
            val d = depacketizer!!
            val playAt = System.currentTimeMillis()
            lastPacket = playAt
            var keepAliveAt = playAt + keepAliveMs
            var controlAt = playAt
            var statusAt = 0L
            val camera: InetAddress? = c.remoteAddress
            val datagram = DatagramPacket(ByteArray(65536), 65536)
            while (true) {
                turn()
                var now = System.currentTimeMillis()
                if (now >= keepAliveAt) {
                    keepAliveCseq = c.send(keepAlive, aggregate)
                    keepAliveAt = now + keepAliveMs
                }
                if (tcp) {
                    when (c.read(now + RtspConnection.POLL_MS, turn)) {
                        RtspConnection.Kind.DATA -> packet(c.channel, c.buffer, c.dataOffset, c.dataLength)
                        RtspConnection.Kind.RESPONSE -> reply(c.response!!)?.let { return it }
                        else -> Unit
                    }
                } else {
                    if (now >= controlAt) {
                        // Replies to keep-alives, or the camera closing the control connection (which ends the stream).
                        // A camera that sends the pictures on it after all, as '$' frames, is taken at its word.
                        while (true) {
                            when (c.read(now + 1, turn)) {
                                RtspConnection.Kind.NONE -> break
                                RtspConnection.Kind.DATA -> packet(c.channel, c.buffer, c.dataOffset, c.dataLength)
                                RtspConnection.Kind.RESPONSE -> reply(c.response!!)?.let { return it }
                                RtspConnection.Kind.OTHER -> Unit
                            }
                        }
                        controlAt = now + CONTROL_MS
                    }
                    receive(datagram, camera)
                }
                now = System.currentTimeMillis()
                if (started == 0L) {
                    if (now - playAt > LiveTiming.firstPictureMs) {
                        val what = if (d.packets == 0L) "no data" else "${d.packets} packets but no complete key frame"
                        return result(Kind.NO_PICTURE, "no picture within ${seconds(LiveTiming.firstPictureMs)} ($what)", "no picture")
                    }
                } else if (now >= maxOf(statusAt, started + STATUS_MS)) {
                    statusAt = now + STATUS_MS
                    report(Live.Phase.PLAYING, describe(format!!), path, transport(tcp), meter.fps(now), meter.kbps(now), meter.lagging(now))
                }
                if (now - lastPacket > LiveTiming.silenceMs) {
                    if (started == 0L) return result(Kind.NO_PICTURE, "no data for ${seconds(LiveTiming.silenceMs)}", "no picture")
                    return ended("no data for ${seconds(LiveTiming.silenceMs)}")
                }
            }
        }

        private fun receive(datagram: DatagramPacket, camera: InetAddress?) {
            datagram.length = datagram.data.size
            try {
                udp[0].receive(datagram)
            } catch (e: SocketTimeoutException) {
                return
            }
            if (camera != null && datagram.address != camera) return         // not from the camera: not ours
            packet(rtpChannel, datagram.data, datagram.offset, datagram.length)
        }

        /** A reply that arrived while streaming. Only the answer to the latest keep-alive matters. */
        private fun reply(r: RtspResponse): Result? {
            if (r.cseq != keepAliveCseq) return null
            when (r.status) {
                454 -> return ended("the camera ended the session")
                405, 501, 551 -> if (keepAlive == "GET_PARAMETER") {
                    LiveLog.add("$tag: GET_PARAMETER ${r.status}, keeping the session alive with OPTIONS")
                    keepAlive = "OPTIONS"
                }
            }
            return null
        }

        /**
         * Opens the TCP connection on a helper thread, so that stop() and other requests are still heard while the
         * camera takes its time (up to 3 s). A connection that arrives after nobody wants it any more is closed.
         */
        private fun connect(): RtspConnection {
            val pending = CompletableFuture<Socket>()
            val t = Thread({
                try {
                    val s = Live.sockets.tcp(host, Rtsp.PORT, CONNECT_MS)
                    if (!pending.complete(s)) close(s)
                } catch (e: Exception) {
                    pending.completeExceptionally(e)
                }
            }, "live-connect")
            t.isDaemon = true
            t.start()
            val socket = try {
                waitFor(pending)
            } catch (e: Throwable) {
                if (!pending.cancel(false) && !pending.isCompletedExceptionally) pending.getNow(null)?.let { close(it) }
                throw e
            }
            try {
                socket.tcpNoDelay = true
            } catch (e: SocketException) {
                // only a matter of speed
            }
            return RtspConnection(socket, host, path)
        }

        private fun waitFor(pending: CompletableFuture<Socket>): Socket {
            while (true) {
                turn()
                try {
                    return pending.get(RtspConnection.POLL_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    // not yet
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    throw cause as? IOException ?: IOException(cause?.message ?: "could not connect", cause)
                }
            }
        }

        private fun close(s: Socket) {
            try {
                s.close()
            } catch (e: IOException) {
                // closing anyway
            }
        }

        private fun teardown(c: RtspConnection) {
            try {
                c.call("TEARDOWN", aggregate, emptyList(), TEARDOWN_MS, {})
            } catch (e: IOException) {
                // best effort: the session times out on the camera by itself
            } catch (e: RuntimeException) {
                // the same
            }
        }

        /** Ends the session and frees everything, whatever happened before: TEARDOWN first if there is a session. */
        fun close() {
            conn?.let { c ->
                if (c.session != null) teardown(c)
                c.close()
            }
            for (s in udp) s.close()
        }
    }

    private fun describe(f: VideoFormat): String = when (f) {
        is VideoFormat.H264 -> if (f.width > 0) "H.264 ${f.width}x${f.height}" else "H.264"
        is VideoFormat.Jpeg -> "JPEG ${f.width}x${f.height}"
    }

    /** Frames per second, kbit/s and whether pictures pile up, over the last two seconds in eight slices of 250 ms. */
    private class Meter(private val start: Long) {
        private val pictures = IntArray(SLICES)
        private val slow = IntArray(SLICES)
        private val bytes = LongArray(SLICES)
        private val streamUs = LongArray(SLICES)
        private var slice = 0
        private var sliceStart = start
        private var lastPts = -1L

        fun bytes(n: Int, now: Long) {
            move(now)
            bytes[slice] += n.toLong()
        }

        /** A picture went out: [sinkNs] is how long the sink took with it. */
        fun picture(ptsUs: Long, sinkNs: Long, now: Long) {
            move(now)
            pictures[slice]++
            if (lastPts >= 0) {
                val step = ptsUs - lastPts
                if (step in 1..1_000_000) {
                    streamUs[slice] += step
                    if (sinkNs / 1000 > step) slow[slice]++
                }
            }
            lastPts = ptsUs
        }

        fun fps(now: Long): Float {
            move(now)
            return pictures.sum() * 1000f / span(now)
        }

        fun kbps(now: Long): Int {
            move(now)
            return (bytes.sum() * 8 / span(now)).toInt()
        }

        /**
         * True when pictures arrive more than 1.5 times faster than the time they cover (a backlog being caught
         * up), or when the sink took longer than a picture lasts for at least a quarter of them (and three or more).
         */
        fun lagging(now: Long): Boolean {
            move(now)
            val span = span(now)
            val n = pictures.sum()
            val s = slow.sum()
            return (span >= 500 && streamUs.sum() / 1000 > span * 3 / 2) || (s >= 3 && s * 4 >= n)
        }

        /** The time the slices cover, in ms. */
        private fun span(now: Long): Long = minOf(now - start, (SLICES - 1) * SLICE_MS + (now - sliceStart)).coerceAtLeast(1)

        private fun move(now: Long) {
            if (now - sliceStart >= SLICES * SLICE_MS) {
                pictures.fill(0)
                slow.fill(0)
                bytes.fill(0)
                streamUs.fill(0)
                sliceStart = now
                return
            }
            while (now - sliceStart >= SLICE_MS) {
                slice = (slice + 1) % SLICES
                pictures[slice] = 0
                slow[slice] = 0
                bytes[slice] = 0
                streamUs[slice] = 0
                sliceStart += SLICE_MS
            }
        }
    }

    private companion object {
        const val BUSY = "camera busy with another request"
        const val CONNECT_MS = 3000
        const val TEARDOWN_MS = 250L
        const val STATUS_MS = 500L
        const val CONTROL_MS = 200L
        const val SLICES = 8
        const val SLICE_MS = 250L

        /** Answers to SETUP that mean "not with that transport" (or a camera that does not say it better). */
        val REFUSED_TRANSPORT = setOf(461, 400, 454, 551)
    }
}
