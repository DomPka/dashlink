package com.dompka.dashlink

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Random
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the camera's RTSP server: a small server on 127.0.0.1 that answers OPTIONS, DESCRIBE, SETUP, PLAY,
 * GET_PARAMETER and TEARDOWN and sends pictures as RTP - interleaved on the RTSP connection, or over UDP - packed the
 * way RFC 6184 (H.264) and RFC 2435 (JPEG) describe. It can be told to misbehave: refuse TCP or UDP, leave the
 * parameter sets out of the SDP, forget marker bits, drop packets, insist on keep-alives, hang up mid-stream. Every
 * request is recorded. Plain java.net, so the JVM tests and the emulator tests both use it; where the pictures come
 * from is up to the test ([Stream.pictures]), down to real H.264 from MediaCodec through [queue].
 *
 * It is not the camera: it only shows how the app reacts to what it is sent.
 */
internal class FakeRtsp : Closeable {

    /** One picture to send. [time] is its RTP time in 90 kHz ticks, counted from the start of the stream. */
    sealed class Picture(val time: Long)

    /** One H.264 access unit: its NAL units, without start codes. */
    class AccessUnit(val nals: List<ByteArray>, time: Long) : Picture(time)

    /**
     * One JPEG picture the way RFC 2435 carries it: the entropy-coded scan and the main header's fields. [width] and
     * [height] are in pixels (multiples of 8). For [q] >= 128, [tables] go into the first packet's quantization table
     * header (in DQT order, [precision] bit i set for a 16-bit table i); null sends that header with length 0.
     */
    class JpegFrame(
        val scan: ByteArray,
        val type: Int,
        val q: Int,
        val width: Int,
        val height: Int,
        time: Long,
        val tables: ByteArray? = null,
        val precision: Int = 0,
        val restartInterval: Int = 0,
    ) : Picture(time)

    /** Where a stream's pictures come from: asked for the next one when it is due; null ends the stream. May block. */
    fun interface Source {
        fun next(): Picture?
    }

    /** One stream path the camera has. [pictures] gives a fresh [Source] for every PLAY. */
    class Stream(val codec: String, val pictures: () -> Source) {
        @Volatile
        var sps: ByteArray? = null

        @Volatile
        var pps: ByteArray? = null

        /** Put sprop-parameter-sets into the SDP (when [sps] and [pps] are set). */
        @Volatile
        var sprop = true

        @Volatile
        var payloadType = if (codec == "JPEG") 26 else 96

        /** Leave out a=rtpmap (only sensible with the static payload type 26). */
        @Volatile
        var rtpmap = true

        /** The video's a=control: relative, absolute, or anything at all. Null leaves the attribute out. */
        @Volatile
        var control: String? = "trackID=1"

        /** Sent as Content-Base with the SDP, when set. */
        @Volatile
        var contentBase: String? = null

        @Volatile
        var sessionControl: String? = "*"

        /** An audio track, listed before the video. */
        @Volatile
        var audio = false

        @Volatile
        var audioControl = "trackID=2"

        /** Time between two pictures. */
        @Volatile
        var intervalMs = 20L
    }

    /** A request as received. Header names are in lower case. */
    class Request(val method: String, val url: String, val headers: Map<String, String>) {
        /** The path of [url] ("/liveRTSP/av4/trackID=1"). */
        val path: String
            get() {
                val rest = url.substringAfter("://", "")
                val slash = rest.indexOf('/')
                return if (slash < 0) "" else rest.substring(slash)
            }

        override fun toString() = "$method $url"
    }

    /** Path ("/liveRTSP/av4") -> the stream. A path that is missing here answers 404. */
    val streams = ConcurrentHashMap<String, Stream>()

    /** Every request received, in order. */
    val requests = CopyOnWriteArrayList<Request>()

    /** Requests a read-only client must never send: other methods, or URLs that are not one of the camera's streams. */
    val violations = CopyOnWriteArrayList<Request>()

    /** "TCP" or "UDP" for each video SETUP that was answered 200. */
    val transports = CopyOnWriteArrayList<String>()

    /** The client_port value of every UDP SETUP, as sent ("5000-5001"). */
    val clientPorts = CopyOnWriteArrayList<String>()

    /** host:port of every TCP connection the app asked [sockets] for. */
    val connects = CopyOnWriteArrayList<String>()

    /** Accept RTP over the RTSP connection; false answers such a SETUP with 461. */
    @Volatile
    var tcp = true

    /** Hang up instead of answering a SETUP that asks for TCP. */
    @Volatile
    var closeOnTcpSetup = false

    /** Say yes to RTP over TCP, then send nothing on it. */
    @Volatile
    var tcpSilent = false

    /** Accept RTP over UDP; false answers such a SETUP with 461. */
    @Volatile
    var udp = true

    /** UDP packets are sent from this address (another loopback address shows packets from a stranger). */
    @Volatile
    var udpSource = "127.0.0.1"

    /** DESCRIBE answers 401. */
    @Volatile
    var auth = false

    /** GET_PARAMETER is listed in OPTIONS and answered; false leaves it out and answers it with 405. */
    @Volatile
    var getParameter = true

    /** Seconds for the Session header's ";timeout="; 0 leaves it out. */
    @Volatile
    var sessionTimeout = 60

    /** When > 0: a playing session that gets no request for this long is ended by hanging up. */
    @Volatile
    var keepAliveMs = 0L

    /** How often [keepAliveMs] ran out. */
    val timeouts = AtomicInteger()

    /** Wait this long before answering any request (a slow camera). */
    @Volatile
    var replyDelayMs = 0L

    /** PLAY answers 459 until every track is set up. */
    @Volatile
    var allTracksForPlay = false

    @Volatile
    var mtu = 1400

    /** Pack small NAL units together into STAP-A packets. */
    @Volatile
    var aggregate = true

    /** Set the marker bit on the last packet of each picture. */
    @Volatile
    var markers = true

    /** Packet number (from PLAY, counting from 0) -> leave it out. Its sequence number is used up all the same. */
    @Volatile
    var drop: (Int) -> Boolean = { false }

    /** Hang up after this many packets; -1 never. */
    @Volatile
    var closeAfter = -1

    /** Close the connection (politely) when the pictures run out; false stays silent instead. */
    @Volatile
    var closeAtEnd = true

    /** Near the wrap-around on purpose, so that every test crosses it. */
    @Volatile
    var firstSequence = 65_500

    @Volatile
    var firstTimestamp = 0xFFFF_0000L

    /** RTP packets sent so far. */
    val packetsSent = AtomicInteger()

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val conns = CopyOnWriteArrayList<Conn>()

    val port: Int get() = server.localPort

    init {
        val t = Thread({ acceptLoop() }, "fakertsp-accept")
        t.isDaemon = true
        t.start()
    }

    /** Sockets as the app makes them on the camera's link, aimed at this server: every host's port 554 is this one. */
    fun sockets(): LiveSockets = object : LiveSockets {
        override fun tcp(host: String, port: Int, timeoutMs: Int): Socket {
            connects.add("$host:$port")
            val s = Socket(Proxy.NO_PROXY)
            try {
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), this@FakeRtsp.port), timeoutMs)
            } catch (e: IOException) {
                s.close()
                throw e
            }
            return s
        }

        override fun udp(port: Int): DatagramSocket = DatagramSocket(port, InetAddress.getByName("127.0.0.1"))
    }

    /** Requests with this method, in order. */
    fun requests(method: String): List<Request> = requests.filter { it.method == method }

    /** Drops every connection at once, the way a vanished Wi-Fi link does. */
    fun hangUp() {
        for (c in conns) c.hangUp()
    }

    override fun close() {
        try {
            server.close()
        } catch (e: IOException) {
            // closing
        }
        for (c in conns) c.close()
    }

    // ---------------------------------------------------------------- server

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            val c = Conn(s)
            conns.add(c)
            val t = Thread({ c.serve() }, "fakertsp-conn")
            t.isDaemon = true
            t.start()
        }
    }

    private val streamPath = Regex("/liveRTSP/(av|v)[1-9](/[A-Za-z0-9=_.-]+)*/?")
    private val methods = setOf("OPTIONS", "DESCRIBE", "SETUP", "PLAY", "GET_PARAMETER", "TEARDOWN")

    private fun isViolation(r: Request): Boolean =
        r.method !in methods || !r.url.startsWith("rtsp://") || !streamPath.matches(r.path)

    /** The stream [url] is about: the longest known path it starts with. */
    private fun streamOf(url: String): Pair<String, Stream>? {
        val path = Request("", url, emptyMap()).path
        return streams.entries.filter { path == it.key || path.startsWith(it.key + "/") }
            .maxByOrNull { it.key.length }?.let { it.key to it.value }
    }

    private inner class Conn(private val s: Socket) {
        private val input = BufferedInputStream(s.getInputStream())
        private val out: OutputStream = s.getOutputStream()
        private val session = "%08X".format(Random().nextInt())
        private var hasSession = false

        @Volatile
        private var lastRequest = System.currentTimeMillis()
        private var stream: Stream? = null
        private var streamTcp = true
        private var videoChannel = 0
        private var audioSetUp = false
        private var udpOut: DatagramSocket? = null
        private var udpTo: InetSocketAddress? = null

        @Volatile
        private var player: Thread? = null

        @Volatile
        private var closed = false

        fun serve() {
            try {
                while (!closed) {
                    val req = read(input) ?: break
                    lastRequest = System.currentTimeMillis()
                    requests.add(req)
                    if (isViolation(req)) violations.add(req)
                    handle(req)
                }
            } catch (e: IOException) {
                // the client went away
            } finally {
                close()
            }
        }

        private fun handle(req: Request) {
            when (req.method) {
                "OPTIONS" -> reply(req, 200, listOf("Public" to (if (getParameter) "OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, GET_PARAMETER" else "OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY")))
                "DESCRIBE" -> describe(req)
                "SETUP" -> setup(req)
                "PLAY" -> play(req)
                "GET_PARAMETER" -> if (getParameter) reply(req, 200, sessionHeader()) else reply(req, 405)
                "TEARDOWN" -> {
                    stopPlaying()
                    hasSession = false
                    reply(req, 200)
                }
                else -> reply(req, 405)
            }
        }

        private fun describe(req: Request) {
            val (path, st) = streamOf(req.url)?.takeIf { req.path.trimEnd('/') == it.first } ?: return reply(req, 404)
            if (auth) return reply(req, 401, listOf("WWW-Authenticate" to "Digest realm=\"camera\", nonce=\"1\""))
            val sdp = sdp(st, path).toByteArray(Charsets.UTF_8)
            val headers = ArrayList<Pair<String, String>>()
            headers.add("Content-Type" to "application/sdp")
            st.contentBase?.let { headers.add("Content-Base" to it) }
            reply(req, 200, headers, sdp)
        }

        private fun sdp(st: Stream, path: String): String {
            val sb = StringBuilder()
            sb.append("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=$path\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n")
            st.sessionControl?.let { sb.append("a=control:").append(it).append("\r\n") }
            if (st.audio) {
                sb.append("m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000/1\r\n")
                sb.append("a=control:").append(st.audioControl).append("\r\n")
            }
            val pt = st.payloadType
            sb.append("m=video 0 RTP/AVP ").append(pt).append("\r\n")
            if (st.rtpmap) sb.append("a=rtpmap:").append(pt).append(if (st.codec == "JPEG") " JPEG/90000" else " H264/90000").append("\r\n")
            if (st.codec == "H264") {
                sb.append("a=fmtp:").append(pt).append(" packetization-mode=1;profile-level-id=42C01F")
                val sps = st.sps
                val pps = st.pps
                if (st.sprop && sps != null && pps != null) {
                    val b64 = Base64.getEncoder()
                    sb.append(";sprop-parameter-sets=").append(b64.encodeToString(sps)).append(',').append(b64.encodeToString(pps))
                }
                sb.append("\r\n")
            }
            st.control?.let { sb.append("a=control:").append(it).append("\r\n") }
            return sb.toString()
        }

        private fun setup(req: Request) {
            val (_, st) = streamOf(req.url) ?: return reply(req, 404)
            val video = st.control.let { c -> c == null || req.url == c || req.url.endsWith("/$c") }
            val audio = !video && st.audio && req.url.endsWith("/${st.audioControl}")
            if (!video && !audio) return reply(req, 404)
            if (req.headers["session"]?.let { it != session || !hasSession } == true) return reply(req, 454)
            val t = req.headers["transport"] ?: return reply(req, 461)
            val wantsTcp = t.contains("/TCP")
            val answer: String
            if (wantsTcp) {
                if (closeOnTcpSetup) return close()
                if (!tcp) return reply(req, 461)
                val channels = t.substringAfter("interleaved=", "0-1").substringBefore(';')
                if (video) videoChannel = channels.substringBefore('-').toIntOrNull() ?: 0
                answer = "RTP/AVP/TCP;unicast;interleaved=$channels"
            } else {
                if (!udp) return reply(req, 461)
                val ports = t.substringAfter("client_port=", "").substringBefore(';')
                clientPorts.add(ports)
                val rtpPort = ports.substringBefore('-').toIntOrNull() ?: return reply(req, 461)
                val o = udpOut ?: DatagramSocket(0, InetAddress.getByName(udpSource)).also { udpOut = it }
                if (video) udpTo = InetSocketAddress(s.inetAddress, rtpPort)
                answer = "RTP/AVP;unicast;client_port=$ports;server_port=${o.localPort}-${o.localPort + 1}"
            }
            hasSession = true
            if (video) {
                stream = st
                streamTcp = wantsTcp
                transports.add(if (wantsTcp) "TCP" else "UDP")
            } else {
                audioSetUp = true
            }
            reply(req, 200, listOf("Transport" to answer) + sessionHeader())
        }

        private fun sessionHeader() = listOf("Session" to (if (sessionTimeout > 0) "$session;timeout=$sessionTimeout" else session))

        private fun play(req: Request) {
            val st = stream
            if (!hasSession || st == null || req.headers["session"] != session) return reply(req, 454)
            if (allTracksForPlay && st.audio && !audioSetUp) return reply(req, 459)
            reply(req, 200, listOf("Range" to "npt=0.000-") + sessionHeader())
            stopPlaying()
            val source = st.pictures()
            val t = Thread({ send(st, source) }, "fakertsp-play")
            t.isDaemon = true
            player = t
            t.start()
        }

        private fun stopPlaying() {
            val t = player ?: return
            player = null
            t.interrupt()
        }

        /** Sends pictures from [source] until it runs dry, the session ends or the client stops asking. */
        private fun send(st: Stream, source: Source) {
            val me = Thread.currentThread()
            var seq = firstSequence
            val ssrc = 0x5EED0001
            var index = 0
            try {
                while (player === me && !closed) {
                    val pic = source.next() ?: break
                    val payloads = when (pic) {
                        is AccessUnit -> h264Payloads(pic.nals, mtu, aggregate)
                        is JpegFrame -> jpegPayloads(pic, mtu)
                    }
                    for ((i, payload) in payloads.withIndex()) {
                        if (closeAfter in 0..index) {
                            hangUp()
                            return
                        }
                        val marker = markers && i == payloads.size - 1
                        val packet = rtp(st.payloadType, seq, (firstTimestamp + pic.time) and 0xFFFF_FFFFL, ssrc, marker, payload)
                        seq = (seq + 1) and 0xffff
                        if (!drop(index)) sendPacket(packet)
                        index++
                    }
                    val until = System.currentTimeMillis() + st.intervalMs
                    while (player === me && !closed) {
                        if (keepAliveMs > 0 && System.currentTimeMillis() - lastRequest > keepAliveMs) {
                            timeouts.incrementAndGet()
                            hangUp()
                            return
                        }
                        val left = until - System.currentTimeMillis()
                        if (left <= 0) break
                        Thread.sleep(minOf(left, 5L))
                    }
                }
                // A polite close: what was sent still arrives (a reset would throw away what the client has not read).
                if (player === me && closeAtEnd) close()
            } catch (e: InterruptedException) {
                // TEARDOWN, or a new PLAY
            } catch (e: IOException) {
                // the client went away
            }
        }

        private fun sendPacket(packet: ByteArray) {
            if (streamTcp) {
                if (tcpSilent) return
                val frame = ByteArray(4 + packet.size)
                frame[0] = '$'.code.toByte()
                frame[1] = videoChannel.toByte()
                frame[2] = (packet.size ushr 8).toByte()
                frame[3] = packet.size.toByte()
                System.arraycopy(packet, 0, frame, 4, packet.size)
                synchronized(out) {
                    out.write(frame)
                    out.flush()
                }
            } else {
                val to = udpTo ?: return
                udpOut?.send(DatagramPacket(packet, packet.size, to))
            }
            packetsSent.incrementAndGet()
        }

        private fun reply(req: Request, status: Int, headers: List<Pair<String, String>> = emptyList(), body: ByteArray = ByteArray(0)) {
            if (replyDelayMs > 0) Thread.sleep(replyDelayMs)
            val sb = StringBuilder("RTSP/1.0 $status ${reason(status)}\r\n")
            sb.append("CSeq: ").append(req.headers["cseq"] ?: "0").append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            if (body.isNotEmpty()) sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("\r\n")
            synchronized(out) {
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        }

        /** Like a link that vanished: the connection is reset, not closed politely. */
        fun hangUp() {
            try {
                s.setSoLinger(true, 0)
            } catch (e: IOException) {
                // closing anyway
            }
            close()
        }

        fun close() {
            if (closed) return
            closed = true
            stopPlaying()
            try {
                s.close()
            } catch (e: IOException) {
                // closing
            }
            udpOut?.close()
            conns.remove(this)
        }
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b == (if (matched % 2 == 0) '\r' else '\n').code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 3) throw IOException("not a request: ${lines[0]}")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        repeat(length) { if (input.read() < 0) return null }
        return Request(first[0], first[1], headers)
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        454 -> "Session Not Found"
        459 -> "Aggregate Operation Not Allowed"
        461 -> "Unsupported Transport"
        else -> "Status"
    }

    companion object {
        /** One RTP packet (RFC 3550): version 2, no padding, extension or CSRCs. */
        fun rtp(payloadType: Int, sequence: Int, timestamp: Long, ssrc: Int, marker: Boolean, payload: ByteArray): ByteArray {
            val p = ByteArray(12 + payload.size)
            p[0] = 0x80.toByte()
            p[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
            p[2] = (sequence ushr 8).toByte()
            p[3] = sequence.toByte()
            for (i in 0 until 4) p[4 + i] = (timestamp ushr (24 - 8 * i)).toByte()
            for (i in 0 until 4) p[8 + i] = (ssrc ushr (24 - 8 * i)).toByte()
            System.arraycopy(payload, 0, p, 12, payload.size)
            return p
        }

        /**
         * RTP payloads for one access unit (RFC 6184): a NAL unit too big for one packet is cut into FU-A fragments,
         * small ones next to each other go into one STAP-A (when [aggregate]), the rest as single NAL unit packets.
         */
        fun h264Payloads(nals: List<ByteArray>, mtu: Int, aggregate: Boolean): List<ByteArray> {
            val room = mtu - 12
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i < nals.size) {
                val nal = nals[i]
                if (nal.size > room) {
                    out.addAll(fuA(nal, room))
                    i++
                    continue
                }
                if (aggregate) {
                    var j = i
                    var size = 1
                    while (j < nals.size && size + 2 + nals[j].size <= room) {
                        size += 2 + nals[j].size
                        j++
                    }
                    if (j - i >= 2) {
                        out.add(stapA(nals.subList(i, j)))
                        i = j
                        continue
                    }
                }
                out.add(nal.copyOf())
                i++
            }
            return out
        }

        fun fuA(nal: ByteArray, room: Int): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            val piece = room - 2
            var at = 1
            while (at < nal.size) {
                val n = minOf(piece, nal.size - at)
                val p = ByteArray(2 + n)
                p[0] = ((nal[0].toInt() and 0xe0) or 28).toByte()
                p[1] = ((if (at == 1) 0x80 else 0) or (if (at + n == nal.size) 0x40 else 0) or (nal[0].toInt() and 0x1f)).toByte()
                System.arraycopy(nal, at, p, 2, n)
                out.add(p)
                at += n
            }
            return out
        }

        fun stapA(nals: List<ByteArray>): ByteArray {
            val b = ByteArrayOutputStream()
            val nri = nals.maxOf { it[0].toInt() and 0x60 }
            val f = nals.fold(0) { acc, n -> acc or (n[0].toInt() and 0x80) }
            b.write(f or nri or 24)
            for (n in nals) {
                b.write(n.size ushr 8)
                b.write(n.size and 0xff)
                b.write(n)
            }
            return b.toByteArray()
        }

        /** RTP payloads for one JPEG picture (RFC 2435), cut to fit [mtu]. */
        fun jpegPayloads(f: JpegFrame, mtu: Int): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            var offset = 0
            do {
                val p = ByteArrayOutputStream()
                p.write(0)
                p.write(offset ushr 16)
                p.write(offset ushr 8)
                p.write(offset)
                p.write(f.type)
                p.write(f.q)
                p.write(f.width / 8)
                p.write(f.height / 8)
                if (f.type in 64..127) {
                    p.write(f.restartInterval ushr 8)
                    p.write(f.restartInterval)
                    p.write(0xff)                                  // F=1, L=1, count 0x3FFF: not cut at restart markers
                    p.write(0xff)
                }
                if (f.q >= 128 && offset == 0) {
                    val t = f.tables ?: ByteArray(0)
                    p.write(0)
                    p.write(f.precision)
                    p.write(t.size ushr 8)
                    p.write(t.size)
                    p.write(t)
                }
                val n = minOf(mtu - 12 - p.size(), f.scan.size - offset)
                p.write(f.scan, offset, n)
                out.add(p.toByteArray())
                offset += n
            } while (offset < f.scan.size)
            return out
        }

        /** A Baseline-profile SPS for a [width] x [height] picture, cropped when the size is not a multiple of 16. */
        fun sps(width: Int, height: Int): ByteArray {
            val w = Bits()
            w.bits(66, 8)                                          // profile_idc: Baseline
            w.bits(0xC0, 8)                                        // constraint_set0 and 1
            w.bits(31, 8)                                          // level 3.1
            w.ue(0)                                                // seq_parameter_set_id
            w.ue(0)                                                // log2_max_frame_num_minus4
            w.ue(2)                                                // pic_order_cnt_type
            w.ue(1)                                                // max_num_ref_frames
            w.bits(0, 1)                                           // gaps_in_frame_num_value_allowed_flag
            val mbW = (width + 15) / 16
            val mbH = (height + 15) / 16
            w.ue(mbW - 1)
            w.ue(mbH - 1)
            w.bits(1, 1)                                           // frame_mbs_only_flag
            w.bits(1, 1)                                           // direct_8x8_inference_flag
            val crop = mbW * 16 != width || mbH * 16 != height
            w.bits(if (crop) 1 else 0, 1)
            if (crop) {
                w.ue(0)
                w.ue((mbW * 16 - width) / 2)
                w.ue(0)
                w.ue((mbH * 16 - height) / 2)
            }
            w.bits(0, 1)                                           // vui_parameters_present_flag
            return nal(0x67, w.trailing())
        }

        val PPS: ByteArray = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())

        /** A stand-in slice of [size] bytes: the NAL header and filler. Nothing here decodes it. */
        fun slice(idr: Boolean, size: Int, seed: Int): ByteArray {
            val b = ByteArray(size)
            Random(seed.toLong()).nextBytes(b)
            b[0] = (if (idr) 0x65 else 0x41).toByte()
            // first_mb_in_slice = 0, as in the first (or only) slice of a picture: the receiver checks it.
            if (size > 1) b[1] = (b[1].toInt() or 0x80).toByte()
            return b
        }

        /**
         * Pictures at [fps]: an IDR every [gop] pictures, with the SPS and PPS in the same access unit when [inBand],
         * and P slices in between; [frames] of them, then the stream ends.
         */
        fun h264(
            sps: ByteArray = sps(640, 360),
            frames: Int = Int.MAX_VALUE,
            gop: Int = 10,
            fps: Int = 50,
            idrSize: Int = 4000,
            pSize: Int = 800,
            inBand: Boolean = true,
        ): () -> Source = {
            var n = 0
            Source {
                if (n >= frames) {
                    null
                } else {
                    val i = n++
                    val nals = if (i % gop == 0) {
                        (if (inBand) listOf(sps, PPS) else emptyList()) + slice(true, idrSize, i)
                    } else {
                        listOf(slice(false, pSize, i))
                    }
                    AccessUnit(nals, i * 90_000L / fps)
                }
            }
        }

        /** Pictures taken from [queue] as they come; nothing for [waitMs] ends the stream. */
        fun queue(queue: BlockingQueue<Picture>, waitMs: Long = 5000): () -> Source = {
            Source { queue.poll(waitMs, TimeUnit.MILLISECONDS) }
        }

        /** [rbsp] with emulation prevention bytes (00 00 03) where the payload would otherwise look like a start code. */
        fun nal(header: Int, rbsp: ByteArray): ByteArray {
            val b = ByteArrayOutputStream()
            b.write(header)
            var zeros = 0
            for (x in rbsp) {
                val v = x.toInt() and 0xff
                if (zeros >= 2 && v <= 3) {
                    b.write(3)
                    zeros = 0
                }
                b.write(v)
                zeros = if (v == 0) zeros + 1 else 0
            }
            return b.toByteArray()
        }
    }

    /** Writes bits and Exp-Golomb codes, for parameter sets made to measure. */
    class Bits {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0

        fun bits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1)
                if (++n == 8) {
                    out.write(cur)
                    cur = 0
                    n = 0
                }
            }
        }

        fun ue(value: Int) {
            val x = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(x)
            bits(0, length - 1)
            bits(x, length)
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        /** The RBSP stop bit and zero bits up to the next byte. */
        fun trailing(): ByteArray {
            bits(1, 1)
            while (n != 0) bits(0, 1)
            return out.toByteArray()
        }
    }
}
