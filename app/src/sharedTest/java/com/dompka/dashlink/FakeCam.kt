package com.dompka.dashlink

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the camera: a tiny HTTP server on 127.0.0.1 that speaks the protocol described at the
 * top of Cam.kt and can be told to misbehave (cut a transfer, ignore Range, never stop listing, ...).
 * Used by the JVM tests and by the emulator tests. It is not the camera: it only proves how the app
 * reacts to the replies it is given.
 */
class FakeCam : Closeable {

    class Request(val method: String, val target: String, val headers: Map<String, String>) {
        /** The path as the client meant it, with %XX escapes resolved. */
        val path: String
            get() {
                val raw = target.substringBefore('?')
                if ('%' !in raw) return raw
                val out = ByteArrayOutputStream()
                var i = 0
                while (i < raw.length) {
                    val hex = if (raw[i] == '%' && i + 2 < raw.length) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                    } else {
                        out.write(raw[i].code)
                        i++
                    }
                }
                return out.toString("UTF-8")
            }
        val query: Map<String, String>
            get() {
                val out = LinkedHashMap<String, String>()
                if ('?' in target) {
                    for (pair in target.substringAfter('?').split('&')) {
                        out[pair.substringBefore('=')] = pair.substringAfter('=', "")
                    }
                }
                return out
            }

        /** N from "Range: bytes=N-", or -1 when the request has no Range header. */
        val rangeStart: Long
            get() = headers["range"]?.substringAfter("bytes=", "")?.substringBefore('-')?.trim()?.toLongOrNull() ?: -1L
    }

    class Reply(var status: Int, var body: ByteArray = ByteArray(0)) {
        val headers = LinkedHashMap<String, String>()

        /** Send a Content-Length header (false: the body simply ends when the connection closes). */
        var sendLength = true

        /** Drop the connection after this many body bytes; -1 sends everything. */
        var cutAfter = -1

        /** How the cut looks to the phone: true = connection reset, false = the stream just ends early. */
        var cutHard = true

        /** Wait this long before answering at all. */
        var stallMs = 0L

        /** Send the body in pieces of [chunk] bytes with [chunkDelayMs] between them. */
        var chunk = 64 * 1024
        var chunkDelayMs = 0L
    }

    class Entry(val path: String, val size: Long, val time: String, val res: String = "1920x1080", val secs: String = "60")

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val open = CopyOnWriteArrayList<Socket>()
    private val active = AtomicInteger()

    val port: Int get() = server.localPort

    /** Every request received, in order. */
    val requests = CopyOnWriteArrayList<Request>()

    /** The most requests that were ever in progress at the same moment. */
    @Volatile
    var maxActive = 0
        private set

    /** Reply to action=get. */
    @Volatile
    var props = "0\nOK\n" +
        "Vendor.DeviceInfo.Model=MiVue 956W\n" +
        "Vendor.DeviceInfo.Version.Firmware=1.0.test\n" +
        "Net.WIFI_AP.SSID=MiVue_Test\n" +
        "Net.WIFI_AP.CryptoKey=hunter2secret\n"

    /** Folder name -> its files, for action=dir. */
    val folders = ConcurrentHashMap<String, List<Entry>>()

    /** Folder name -> its files, for action=reardir. A folder that is missing here answers 404 (no rear camera). */
    val rearFolders = ConcurrentHashMap<String, List<Entry>>()

    /** File path -> content. */
    val files = ConcurrentHashMap<String, ByteArray>()

    /** Honour "Range: bytes=N-" on file requests. */
    @Volatile
    var supportsRange = true

    /** Replace any reply: return null to get the normal behaviour. */
    @Volatile
    var override: ((Request) -> Reply?)? = null

    /** Adjust the normal reply before it is sent (cut it, slow it down, ...). */
    @Volatile
    var tweak: ((Request, Reply) -> Unit)? = null

    init {
        val t = Thread({ acceptLoop() }, "fakecam-accept")
        t.isDaemon = true
        t.start()
    }

    /** Points Cam at this server. */
    fun opener(): Cam.Opener = Cam.Opener { path ->
        URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
    }

    /** Adds a file with [size] bytes of repeatable pseudo-random content to [folder] and returns its content. */
    fun addFile(folder: String, name: String, size: Int, time: String = "2026-01-01 12:00:00", rear: Boolean = false): ByteArray {
        val path = "/SD/$folder/${if (rear) "R" else "F"}/$name"
        val data = ByteArray(size)
        Random(path.hashCode().toLong()).nextBytes(data)
        files[path] = data
        val map = if (rear) rearFolders else folders
        map[folder] = (map[folder] ?: emptyList()) + Entry(path, size.toLong(), time)
        return data
    }

    fun clip(path: String): Cam.Clip {
        val all = folders.values.flatten() + rearFolders.values.flatten()
        val e = all.first { it.path == path }
        return Cam.Clip(e.path, e.size, e.time, e.res, e.secs)
    }

    fun fileRequests(): List<Request> = requests.filter { '?' !in it.target }

    /** Kills every transfer that is under way right now, the way a vanished Wi-Fi link does. */
    fun hangUp() {
        for (s in open) {
            try {
                s.setSoLinger(true, 0)
                s.close()
            } catch (e: IOException) {
                // already gone
            }
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (e: IOException) {
            // closing
        }
        for (s in open) {
            try {
                s.close()
            } catch (e: IOException) {
                // closing
            }
        }
    }

    // ---------------------------------------------------------------- server

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            open.add(s)
            val t = Thread({ serve(s) }, "fakecam-conn")
            t.isDaemon = true
            t.start()
        }
    }

    private fun serve(s: Socket) {
        try {
            val req = readRequest(s) ?: return
            requests.add(req)
            val now = active.incrementAndGet()
            synchronized(this) { if (now > maxActive) maxActive = now }
            // The request counts as "in progress" until the moment its last bytes go out: from then on the
            // phone may rightly start the next one, so only a real overlap ever makes maxActive exceed 1.
            val done = AtomicBoolean()
            val finished = { if (done.compareAndSet(false, true)) active.decrementAndGet() }
            try {
                val reply = override?.invoke(req) ?: normal(req).also { tweak?.invoke(req, it) }
                send(s, reply, finished)
            } finally {
                finished()
            }
        } catch (e: Exception) {
            // the client went away; nothing to do
        } finally {
            try {
                s.close()
            } catch (e: IOException) {
                // closing
            }
            open.remove(s)
        }
    }

    private fun readRequest(s: Socket): Request? {
        val ins = BufferedInputStream(s.getInputStream())
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = ins.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b == (if (matched % 2 == 0) '\r' else '\n').code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return Request(first[0], first[1], headers)
    }

    private fun text(status: Int, body: String, type: String = "text/plain"): Reply {
        val r = Reply(status, body.toByteArray(Charsets.UTF_8))
        r.headers["Content-Type"] = type
        return r
    }

    fun xml(entries: List<Entry>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<DCIM>\n")
        for (e in entries) {
            sb.append("<file>\n<name>").append(e.path).append("</name>\n")
            sb.append("<format size=\"").append(e.res).append("\" time=\"").append(e.secs).append("\">mp4</format>\n")
            sb.append("<size>").append(e.size).append("</size>\n<attr>RW</attr>\n")
            sb.append("<time>").append(e.time).append("</time>\n</file>\n")
        }
        return sb.append("<amount>").append(entries.size).append("</amount>\n</DCIM>\n").toString()
    }

    private fun normal(req: Request): Reply {
        if (req.method != "GET") return text(405, "GET only")
        if (req.path == "/cgi-bin/Config.cgi") {
            val q = req.query
            return when (q["action"]) {
                "get" -> text(200, props)
                "dir", "reardir" -> {
                    val all = (if (q["action"] == "dir") folders else rearFolders)[q["property"] ?: ""]
                        ?: return text(404, "709\n?\n")
                    val from = q["from"]?.toIntOrNull() ?: 0
                    val count = q["count"]?.toIntOrNull() ?: 16
                    text(200, xml(all.drop(from).take(count)), "text/xml")
                }
                // The app must never get here: see ReadOnlyTest.
                else -> text(200, "0\nOK\n")
            }
        }
        val data = files[req.path] ?: return text(404, "not found")
        val from = req.rangeStart
        if (from < 0 || !supportsRange) {
            return Reply(200, data).also { it.headers["Content-Type"] = "video/mp4" }
        }
        if (from >= data.size) {
            return Reply(416).also { it.headers["Content-Range"] = "bytes */${data.size}" }
        }
        return Reply(206, data.copyOfRange(from.toInt(), data.size)).also {
            it.headers["Content-Type"] = "video/mp4"
            it.headers["Content-Range"] = "bytes $from-${data.size - 1}/${data.size}"
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        else -> "Status"
    }

    private fun send(s: Socket, r: Reply, lastBytes: () -> Unit) {
        if (r.stallMs > 0) Thread.sleep(r.stallMs)
        val out = s.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        if (r.sendLength) head.append("Content-Length: ").append(r.body.size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        val limit = if (r.cutAfter >= 0) minOf(r.cutAfter, r.body.size) else r.body.size
        if (limit == 0) lastBytes()
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        var sent = 0
        while (sent < limit) {
            val n = minOf(r.chunk, limit - sent)
            if (sent + n >= limit) lastBytes()
            out.write(r.body, sent, n)
            out.flush()
            sent += n
            if (r.chunkDelayMs > 0 && sent < limit) Thread.sleep(r.chunkDelayMs)
        }
        if (r.cutAfter >= 0 && r.cutAfter < r.body.size && r.cutHard) {
            s.setSoLinger(true, 0)      // drop the connection the hard way, like a link that vanished
        }
    }
}

/** A download target in a plain file, for tests. */
class FileSink(private val file: File) : Cam.Sink, Closeable {
    private val raf = RandomAccessFile(file, "rw")

    override fun length(): Long = raf.length()

    override fun truncate(length: Long) {
        raf.setLength(length)
    }

    override fun append(buf: ByteArray, count: Int) {
        raf.seek(raf.length())
        raf.write(buf, 0, count)
    }

    override fun close() = raf.close()
}

/** A download target in memory that can be made to fail, for tests. */
class MemorySink(initial: ByteArray = ByteArray(0)) : Cam.Sink {
    private var data = ByteArrayOutputStream().also { it.write(initial) }

    /** Fail every write once this many bytes are stored ("disk full"); -1 never fails. */
    var failAt = -1L
    var truncations = 0

    fun bytes(): ByteArray = data.toByteArray()

    override fun length(): Long = data.size().toLong()

    override fun truncate(length: Long) {
        truncations++
        val keep = data.toByteArray().copyOf(length.toInt())
        data = ByteArrayOutputStream().also { it.write(keep) }
    }

    override fun append(buf: ByteArray, count: Int) {
        if (failAt >= 0 && data.size() + count > failAt) throw IOException("write failed: ENOSPC (No space left on device)")
        data.write(buf, 0, count)
    }
}
