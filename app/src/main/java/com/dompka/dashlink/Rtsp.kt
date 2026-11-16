package com.dompka.dashlink

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale

/*
 * The RTSP side of the live view (RFC 2326), as much of it as asking for a stream needs: requests and replies on one
 * TCP connection, the SDP that describes the stream, and RTP sent back on the same connection as '$' frames.
 * Plain Kotlin without Android classes; RtspTest and LiveTest run it against FakeRtsp.
 */

internal object Rtsp {
    const val PORT = 554

    /** The only methods ever sent. They ask for a stream, keep it going and end it; none of them changes the camera. */
    val METHODS = setOf("OPTIONS", "DESCRIBE", "SETUP", "PLAY", "GET_PARAMETER", "TEARDOWN")

    fun url(host: String, path: String): String = "rtsp://" + (if (':' in host) "[$host]" else host) + path

    /**
     * Throws [Cam.RefusedException] unless [method] is one of [METHODS] and [url] is the stream [stream] on [host]
     * (port 554), or a track under it: "rtsp://host/liveRTSP/av4", ".../av4/" or ".../av4/trackID=1". Every request
     * passes through here before it is sent, whatever the camera's replies said to ask for.
     */
    fun requireRequest(method: String, url: String, host: String, stream: String) {
        Live.requireStreamPath(stream)
        if (method !in METHODS) throw Cam.RefusedException("refused: $method is not a request the live view makes")
        if (!allowed(url, host, stream)) throw Cam.RefusedException("refused: not this camera's stream ${stream.substringAfterLast('/')}: ${url.take(200)}")
    }

    /** True if [url] is [stream] on [host], or a track under it (see [requireRequest]). */
    fun allowed(url: String, host: String, stream: String): Boolean {
        val path = pathOn(url, host) ?: return false
        if (path == stream || path == "$stream/") return true
        if (!path.startsWith("$stream/")) return false
        return path.substring(stream.length + 1).split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "=_-." }
        }
    }

    /** The path of [url] if it is an rtsp:// URL on [host] at port 554 with nothing hidden in it, else null. */
    private fun pathOn(url: String, host: String): String? {
        if (!url.startsWith("rtsp://", ignoreCase = true)) return null
        if (url.any { it.code <= 0x20 || it.code >= 0x7f || it in "?#%\\@;," }) return null
        val rest = url.substring(7)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val authority = rest.substring(0, slash)
        val name: String
        val port: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            name = authority.substring(1, close)
            val after = authority.substring(close + 1)
            port = if (after.isEmpty()) null else if (after.startsWith(":")) after.substring(1) else return null
        } else {
            val colon = authority.indexOf(':')
            name = if (colon < 0) authority else authority.substring(0, colon)
            port = if (colon < 0) null else authority.substring(colon + 1)
        }
        if (port != null && port != PORT.toString()) return null
        if (!name.equals(host.removePrefix("[").removeSuffix("]"), ignoreCase = true)) return null
        return rest.substring(slash)
    }

    /**
     * An SDP control attribute made absolute: an absolute URL stays as it is (whether it may be used is for
     * [requireRequest] to say), "*" or nothing is [base] itself, anything else is added below [base] - the way
     * RTSP servers mean it, also when [base] does not end with a slash.
     */
    fun resolve(base: String, control: String?): String {
        val c = control?.trim().orEmpty()
        if (c.isEmpty() || c == "*") return base
        if (c.contains("://")) return c
        if (c.startsWith("/")) {
            val authorityEnd = base.indexOf('/', base.indexOf("://") + 3)
            return (if (authorityEnd < 0) base else base.substring(0, authorityEnd)) + c
        }
        return (if (base.endsWith("/")) base else "$base/") + c
    }
}

/** One reply. Header names are kept in lower case. */
internal class RtspResponse(val status: Int, private val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    val cseq: Int? get() = header("cseq")?.trim()?.toIntOrNull()
}

/**
 * One RTSP conversation on one TCP connection. Requests go out as text; replies come back as text, and once PLAY is
 * answered RTP may come on the same connection as '$' frames, mixed with the replies to keep-alives. Bytes that are
 * neither (a camera that loses track of its own framing) are skipped until something readable starts.
 *
 * Every wait is cut into slices of [POLL_MS], and between slices [turn] is asked whether to go on: it throws to end
 * the conversation at once (stop, or the camera is needed elsewhere).
 */
internal class RtspConnection(private val socket: Socket, private val host: String, private val stream: String) : Closeable {

    enum class Kind { NONE, DATA, RESPONSE, OTHER }

    fun interface Data {
        fun packet(channel: Int, buf: ByteArray, offset: Int, length: Int)
    }

    /** The session id from SETUP's reply; sent with every later request. */
    var session: String? = null

    /** The last '$' frame [read]: its channel, and where it is in [buffer]. Valid until the next [read]. */
    var channel = 0
        private set
    var dataOffset = 0
        private set
    var dataLength = 0
        private set

    /** The last reply [read]. */
    var response: RtspResponse? = null
        private set

    /** Bytes passed over because they were neither a reply nor a '$' frame. */
    var skipped = 0L
        private set

    /** Where the camera's packets come from: UDP packets from anywhere else are not the camera's. */
    val remoteAddress: InetAddress? get() = socket.inetAddress

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val buf = ByteArray(BUFFER)
    private var pos = 0
    private var lim = 0
    private var cseq = 0
    private var timeout = -1

    /** The buffer [dataOffset] refers to. */
    val buffer: ByteArray get() = buf

    /**
     * Sends a request and returns its CSeq. Checked by [Rtsp.requireRequest] first: nothing else ever reaches the
     * camera. [headers] are this app's own; none of them carries text from the camera except the session id.
     */
    fun send(method: String, url: String, headers: List<Pair<String, String>> = emptyList()): Int {
        Rtsp.requireRequest(method, url, host, stream)
        val n = ++cseq
        val sb = StringBuilder(256)
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(n).append("\r\n")
        sb.append("User-Agent: DashLink\r\n")
        session?.let { sb.append("Session: ").append(it).append("\r\n") }
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
        return n
    }

    /**
     * Sends a request and waits up to [timeoutMs] for its reply. '$' frames that arrive meanwhile go to [data];
     * replies to other requests are passed over. Throws [SocketTimeoutException] when no reply comes in time.
     */
    fun call(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Long,
        turn: () -> Unit,
        data: Data? = null,
    ): RtspResponse {
        val n = send(method, url, headers)
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            when (read(until, turn)) {
                Kind.NONE -> throw SocketTimeoutException("no answer to $method within ${(timeoutMs + 999) / 1000} s")
                Kind.DATA -> data?.packet(channel, buf, dataOffset, dataLength)
                Kind.RESPONSE -> {
                    val r = response!!
                    if (r.cseq == null || r.cseq == n) return r
                }
                Kind.OTHER -> Unit
            }
        }
    }

    /**
     * Reads the next complete message, waiting until [untilMs] at most: DATA (a '$' frame, see [channel],
     * [dataOffset], [dataLength] and [buffer]), RESPONSE (see [response]), OTHER (a request from the camera,
     * passed over) or NONE when nothing complete arrived in time. Throws [EOFException] when the camera closed the
     * connection, and whatever [turn] throws.
     */
    fun read(untilMs: Long, turn: () -> Unit): Kind {
        while (true) {
            val k = parse()
            if (k != null) return k
            turn()
            val left = untilMs - System.currentTimeMillis()
            if (left <= 0) return Kind.NONE
            fill(minOf(left, POLL_MS))
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            // closing anyway
        }
    }

    private fun fill(waitMs: Long) {
        if (buf.size - lim < ROOM) {
            System.arraycopy(buf, pos, buf, 0, lim - pos)
            lim -= pos
            pos = 0
        }
        val t = waitMs.toInt().coerceAtLeast(1)
        if (t != timeout) {
            socket.soTimeout = t
            timeout = t
        }
        val n = try {
            input.read(buf, lim, buf.size - lim)
        } catch (e: SocketTimeoutException) {
            return
        }
        if (n < 0) throw EOFException("the camera closed the connection")
        lim += n
    }

    /** The next complete message in the buffer, or null if more bytes are needed. */
    private fun parse(): Kind? {
        while (pos < lim) {
            val b = buf[pos].toInt() and 0xff
            if (b == '$'.code) {
                if (lim - pos < 4) return null
                val length = ((buf[pos + 2].toInt() and 0xff) shl 8) or (buf[pos + 3].toInt() and 0xff)
                if (lim - pos < 4 + length) return null
                channel = buf[pos + 1].toInt() and 0xff
                dataOffset = pos + 4
                dataLength = length
                pos += 4 + length
                return Kind.DATA
            }
            if (b in 'A'.code..'Z'.code) {
                when (text()) {
                    Text.INCOMPLETE -> return null
                    Text.REPLY -> return Kind.RESPONSE
                    Text.REQUEST -> return Kind.OTHER
                    Text.GARBAGE -> Unit
                }
            }
            pos++
            skipped++
        }
        return null
    }

    private enum class Text { INCOMPLETE, GARBAGE, REPLY, REQUEST }

    /** Reads a reply or a request at [pos], if one is there and complete. */
    private fun text(): Text {
        // The first line decides: "RTSP/1.0 200 OK" or "METHOD url RTSP/1.0". Anything else is not RTSP.
        val lineEnd = indexOf('\n'.code, pos, minOf(lim, pos + MAX_LINE))
        if (lineEnd < 0) return if (lim - pos >= MAX_LINE || !plausibleStart()) Text.GARBAGE else Text.INCOMPLETE
        val first = String(buf, pos, lineEnd - pos, Charsets.ISO_8859_1).trimEnd('\r')
        val isReply = first.startsWith("RTSP/1.")
        if (!isReply && !isRequestLine(first)) return Text.GARBAGE
        val headEnd = headEnd(lineEnd)
        if (headEnd < 0) return if (lim - pos >= MAX_HEAD) Text.GARBAGE else Text.INCOMPLETE
        val headers = HashMap<String, String>()
        for (line in String(buf, lineEnd + 1, headEnd - lineEnd - 1, Charsets.ISO_8859_1).split('\n')) {
            val l = line.trimEnd('\r')
            val colon = l.indexOf(':')
            if (colon <= 0) continue
            val name = l.substring(0, colon).trim().lowercase(Locale.US)
            val value = l.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        val length = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw IOException("the camera sent a reply of $length bytes")
        // Whole messages are never larger than ROOM, and fill() always leaves that much room: the rest will fit.
        if (lim - headEnd < length) return Text.INCOMPLETE
        val body = buf.copyOfRange(headEnd, headEnd + length)
        pos = headEnd + length
        if (!isReply) return Text.REQUEST
        val status = first.split(' ').filter { it.isNotEmpty() }.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("unreadable reply: ${first.take(80)}")
        response = RtspResponse(status, headers, body)
        return Text.REPLY
    }

    /** Whether the bytes at [pos] could still turn into "RTSP/" or an upper-case method name and a space. */
    private fun plausibleStart(): Boolean {
        val prefix = "RTSP/"
        var i = 0
        while (pos + i < lim && i < prefix.length && buf[pos + i].toInt() == prefix[i].code) i++
        if (pos + i == lim || i == prefix.length) return true
        var j = pos
        while (j < lim && (buf[j].toInt() in 'A'.code..'Z'.code || buf[j].toInt() == '_'.code)) j++
        return j == lim || buf[j].toInt() == ' '.code
    }

    private fun isRequestLine(line: String): Boolean {
        val parts = line.split(' ')
        return parts.size == 3 && parts[0].isNotEmpty() && parts[0].all { it in 'A'..'Z' || it == '_' } && parts[2].startsWith("RTSP/1.")
    }

    /** Index just past the empty line that ends the head, or -1. Lines may end with CR LF or with LF alone. */
    private fun headEnd(firstLineEnd: Int): Int {
        var i = firstLineEnd
        val stop = minOf(lim, pos + MAX_HEAD)
        while (i < stop) {
            if (buf[i].toInt() == '\n'.code) {
                if (i + 1 < stop && buf[i + 1].toInt() == '\n'.code) return i + 2
                if (i + 2 < stop && buf[i + 1].toInt() == '\r'.code && buf[i + 2].toInt() == '\n'.code) return i + 3
            }
            i++
        }
        return -1
    }

    private fun indexOf(b: Int, from: Int, to: Int): Int {
        for (i in from until to) if (buf[i].toInt() == b) return i
        return -1
    }

    companion object {
        /** The longest a wait goes without asking whether to go on. */
        const val POLL_MS = 50L

        private const val MAX_LINE = 2048
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 64 * 1024

        /** Room for the largest message (a 64 KB '$' frame, or a head and body) after the buffer was compacted. */
        private const val ROOM = MAX_HEAD + MAX_BODY + 8
        private const val BUFFER = 2 * ROOM + 64 * 1024
    }
}

/** The parts of an SDP description (RFC 4566) the live view needs. */
internal class Sdp(val sessionControl: String?, val media: List<Media>) {

    class Media(
        val type: String,
        val formats: List<Int>,
        val control: String?,
        val rtpmap: Map<Int, String>,
        val fmtp: Map<Int, String>,
    )

    enum class Codec { H264, JPEG }

    /** The video to ask for: its media section (with its control URL), codec, payload type, clock rate, and for H.264 the parameter sets the SDP gave. */
    class Video(
        val media: Media,
        val codec: Codec,
        val payloadType: Int,
        val clockRate: Int,
        val sps: ByteArray?,
        val pps: ByteArray?,
    )

    /** The first m=video with a format this app can show: H.264, or JPEG (named, or the static payload type 26). */
    fun video(): Video? {
        for (m in media) {
            if (!m.type.equals("video", ignoreCase = true)) continue
            for (pt in m.formats) {
                val map = m.rtpmap[pt]
                val name = map?.substringBefore('/')?.trim()?.uppercase(Locale.US)
                val clock = map?.substringAfter('/', "")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 90_000
                when {
                    name == "H264" -> {
                        val sets = parameterSets(m.fmtp[pt])
                        return Video(m, Codec.H264, pt, clock, sets.first, sets.second)
                    }
                    name == "JPEG" || (map == null && pt == 26) -> return Video(m, Codec.JPEG, pt, clock, null, null)
                }
            }
        }
        return null
    }

    companion object {
        fun parse(text: String): Sdp {
            var sessionControl: String? = null
            val media = ArrayList<Media>()
            var type: String? = null
            var formats = emptyList<Int>()
            var control: String? = null
            var rtpmap = HashMap<Int, String>()
            var fmtp = HashMap<Int, String>()

            fun close() {
                type?.let { media.add(Media(it, formats, control, rtpmap, fmtp)) }
            }

            for (raw in text.split('\n')) {
                val line = raw.trim()
                if (line.length < 2 || line[1] != '=') continue
                val value = line.substring(2)
                when (line[0]) {
                    'm' -> {
                        close()
                        val parts = value.split(' ').filter { it.isNotEmpty() }
                        type = parts.getOrNull(0) ?: ""
                        formats = parts.drop(3).mapNotNull { it.toIntOrNull() }
                        control = null
                        rtpmap = HashMap()
                        fmtp = HashMap()
                    }
                    'a' -> {
                        val name = value.substringBefore(':')
                        val arg = value.substringAfter(':', "").trim()
                        when (name.lowercase(Locale.US)) {
                            "control" -> if (type == null) sessionControl = arg else control = arg
                            "rtpmap" -> arg.substringBefore(' ').toIntOrNull()?.let { rtpmap[it] = arg.substringAfter(' ', "").trim() }
                            "fmtp" -> arg.substringBefore(' ').toIntOrNull()?.let { fmtp[it] = arg.substringAfter(' ', "").trim() }
                        }
                    }
                }
            }
            close()
            return Sdp(sessionControl, media)
        }

        /**
         * SPS and PPS from an H.264 fmtp line's sprop-parameter-sets (base64 NAL units, comma-separated). They are
         * told apart by their NAL type, not by their order; one that cannot be decoded counts as not given.
         */
        fun parameterSets(fmtp: String?): Pair<ByteArray?, ByteArray?> {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val value = fmtp?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.substringBefore('=').trim().equals("sprop-parameter-sets", ignoreCase = true) }
                ?.substringAfter('=') ?: return null to null
            for (item in value.split(',')) {
                val nal = base64(item) ?: continue
                if (nal.isEmpty()) continue
                when (nal[0].toInt() and 0x1f) {
                    7 -> if (sps == null) sps = nal
                    8 -> if (pps == null) pps = nal
                }
            }
            return sps to pps
        }

        private fun base64(s: String): ByteArray? {
            val clean = s.filterNot { it.isWhitespace() }.trimEnd('=')
            if (clean.isEmpty()) return null
            return try {
                Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
