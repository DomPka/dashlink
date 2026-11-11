package com.dompka.dashlink

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The camera's HTTP interface, as used by MiVue Pro 1.1.233.1 (read from its code, not from Mio docs):
 *
 *   settings/info : GET /cgi-bin/Config.cgi?action=get&property=<key or pattern>   -> "key=value" lines
 *   file list     : GET /cgi-bin/Config.cgi?action=dir&property=<folder>&count=N&from=K&format=all&backward=
 *                   (action=reardir for the rear camera)                             -> XML, one <file> per clip
 *   a file        : GET <the /SD/... path from the list>, supports "Range: bytes=N-"
 *   keep-alive    : GET /cgi-bin/Config.cgi?action=get&property=status
 *
 * This class only ever reads. It never sends action=set / del / format, and [requireRead] refuses
 * to open anything else, whatever the caller (or a strange file list) asks for.
 *
 * Nothing in here touches Android, so all of it runs in plain JVM tests against a local server.
 */
object Cam {
    const val INFO = "/cgi-bin/Config.cgi?action=get&property=Vendor.DeviceInfo.*"
    const val STATUS = "/cgi-bin/Config.cgi?action=get&property=status"

    /** The camera's own app asks for 16 files at a time. */
    const val PAGE = 16

    /** A folder listing stops here, so a camera that never stops answering cannot fill the phone's memory. */
    const val MAX_FILES = 20_000

    /** Wait as long as it takes for the camera to be free. */
    const val FOREVER = -1L

    private const val MAX_PAGES = MAX_FILES / PAGE * 2 + 16
    private const val MAX_TEXT = 2 * 1024 * 1024
    private const val BUFFER = 256 * 1024

    class Clip(val path: String, val size: Long, val time: String, val res: String, val secs: String) {
        val name: String get() = path.substringAfterLast('/')
    }

    /** A whole folder: what could be read, why it stopped early (if it did), and whether [MAX_FILES] cut it short. */
    class Listing(val clips: List<Clip>, val error: String?, val truncated: Boolean)

    /** The camera answered, but not with what was asked for. */
    class HttpException(val code: Int) : IOException("HTTP $code")

    /** Writing on the phone failed (storage full, file gone). Asking the camera again will not help. */
    class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** The part already on the phone could not be continued. It has been emptied; the next attempt starts the file over. */
    class RestartException(message: String) : IOException(message)

    /** The caller asked for the download to stop. */
    class CancelledException : InterruptedIOException("cancelled")

    /** Another request is still talking to the camera and did not finish in the time allowed. */
    class BusyException : IOException("the camera is busy with another request")

    /** Where a download is written. It outlives a single attempt, so the next attempt continues it. */
    interface Sink {
        /** Bytes already stored. */
        fun length(): Long

        /** Throws away everything after the first [length] bytes. */
        fun truncate(length: Long)

        /** Adds the first [count] bytes of [buf] to the end. */
        fun append(buf: ByteArray, count: Int)
    }

    // ---------------------------------------------------------------- connection

    /** How a connection for a camera path is opened. */
    fun interface Opener {
        @Throws(IOException::class)
        fun open(pathAndQuery: String): HttpURLConnection
    }

    /**
     * The app opens connections on the camera's own Wi-Fi network (see Link.open), so requests reach
     * the camera whatever the phone's main Wi-Fi is. Tests replace this with a local server.
     */
    @Volatile
    var opener: Opener = Opener { Link.open(it) }

    /** Connect and read time-out for file downloads. */
    @Volatile
    var downloadTimeoutMs = 15_000

    /** The camera is treated as serving one request at a time (its own app queues them too). */
    private val gate = ReentrantLock(true)

    private fun <T> exclusive(waitMs: Long, block: () -> T): T {
        if (waitMs < 0) {
            gate.lock()
        } else {
            val got = try {
                gate.tryLock(waitMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting for the camera")
            }
            if (!got) throw BusyException()
        }
        try {
            return block()
        } finally {
            gate.unlock()
        }
    }

    /**
     * Runs several requests as one turn at the camera (a whole folder listing, a diagnostics report).
     * Throws [BusyException] if the camera is still busy with something else after [waitMs].
     */
    fun <T> session(waitMs: Long = FOREVER, block: () -> T): T = exclusive(waitMs, block)

    private fun open(pathAndQuery: String, timeoutMs: Int): HttpURLConnection {
        requireRead(pathAndQuery)
        val c = opener.open(pathAndQuery)
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.instanceFollowRedirects = false
        // No connection reuse: a pooled socket from before a Wi-Fi drop would fail the next request.
        c.setRequestProperty("Connection", "close")
        return c
    }

    private val readActions = setOf("get", "dir", "reardir")

    /**
     * Throws unless [pathAndQuery] is one of the reads this app is allowed to make: Config.cgi with
     * exactly one action out of get / dir / reardir, or a plain file. Every request passes through here.
     */
    @Throws(IOException::class)
    fun requireRead(pathAndQuery: String) {
        val q = pathAndQuery.indexOf('?')
        if (q < 0) {
            // A file from the list: an absolute path that is not a script.
            val lower = pathAndQuery.lowercase(Locale.US)
            val script = lower.startsWith("/cgi-bin") || lower.split('/').any { it.endsWith(".cgi") }
            if (!pathAndQuery.startsWith("/") || pathAndQuery.contains('#') || script) {
                throw IOException("refused: not a file on the camera: $pathAndQuery")
            }
            return
        }
        if (pathAndQuery.substring(0, q) != "/cgi-bin/Config.cgi") {
            throw IOException("refused: unknown camera command: $pathAndQuery")
        }
        val actions = ArrayList<String>()
        for (pair in pathAndQuery.substring(q + 1).split('&')) {
            val key = decode(pair.substringBefore('='))
            if (key.equals("action", ignoreCase = true)) actions.add(decode(pair.substringAfter('=', "")))
        }
        if (actions.size != 1 || actions[0] !in readActions) {
            throw IOException("refused: only reading from the camera is allowed: $pathAndQuery")
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private const val HEX = "0123456789ABCDEF"

    private fun escape(s: String, keep: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt() and 0xff
            val c = ch.toChar()
            if (ch < 0x80 && (c.isLetterOrDigit() || keep.indexOf(c) >= 0)) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[ch shr 4]).append(HEX[ch and 15])
            }
        }
        return sb.toString()
    }

    /** The request path for a file from the list: always absolute, and never able to turn into a command. */
    fun filePath(path: String): String = escape(if (path.startsWith("/")) path else "/$path", "/-._~!$&'()*+,;=:@")

    // ---------------------------------------------------------------- settings and file list

    /** Throws [BusyException] if the camera is still busy with something else after [waitMs]. */
    fun get(pathAndQuery: String, timeoutMs: Int = 8000, waitMs: Long = FOREVER): String = exclusive(waitMs) {
        val c = open(pathAndQuery, timeoutMs)
        try {
            val code = c.responseCode
            if (code != 200) throw HttpException(code)
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TEXT) throw IOException("reply is larger than ${MAX_TEXT / 1024} KB")
                }
            }
            out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    fun listUrl(folder: String, rear: Boolean, from: Int, count: Int): String {
        val action = if (rear) "reardir" else "dir"
        val prop = escape(folder.substringBefore(',').replace("/", ""), "-._~")
        return "/cgi-bin/Config.cgi?action=$action&property=$prop&count=$count&from=$from&format=all&backward="
    }

    fun list(folder: String, rear: Boolean, from: Int, count: Int): List<Clip> =
        parseList(get(listUrl(folder, rear, from, count), 15000))

    /**
     * Reads a whole folder, [PAGE] files at a time, newest first. It always ends: on an empty page, on a
     * page with nothing new (a camera that repeats itself), at [MAX_FILES], or when [keepGoing] says no.
     * A failure part-way does not lose what was already read; it comes back in [Listing.error].
     */
    fun listAll(folder: String, rear: Boolean, keepGoing: () -> Boolean, loaded: (count: Int) -> Unit): Listing {
        val all = ArrayList<Clip>()
        val seen = HashSet<String>()
        var error: String? = null
        var truncated = false
        try {
            session {
                var pages = 0
                while (keepGoing()) {
                    if (all.size >= MAX_FILES || pages >= MAX_PAGES) {
                        truncated = true
                        break
                    }
                    val page = list(folder, rear, all.size, PAGE)
                    pages++
                    val fresh = page.filter { seen.add(it.path) }
                    if (fresh.isEmpty()) break
                    all.addAll(fresh)
                    loaded(all.size)
                }
            }
        } catch (e: Exception) {
            error = describe(e)
        }
        all.sortByDescending { it.time }
        return Listing(all, error, truncated)
    }

    fun describe(e: Throwable): String = e.javaClass.simpleName + ": " + e.message

    fun parseProps(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in body.lineSequence()) {
            val i = line.indexOf('=')
            if (i > 0) out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return out
    }

    // The list is small, flat XML. It is read with plain index searches rather than a regular
    // expression: the same code path on a desk and on Android, linear time, and nothing a damaged
    // or enormous reply can make it choke on.

    /** Index just past the '>' of the first `<name>` / `<name attr…>` at or after [from], or -1. */
    private fun openTag(s: String, name: String, from: Int): Int {
        var i = from
        while (true) {
            i = s.indexOf("<$name", i)
            if (i < 0) return -1
            val after = i + name.length + 1
            if (after >= s.length) return -1
            val ch = s[after]
            if (ch == '>') return after + 1
            if (ch.isWhitespace()) {
                val close = s.indexOf('>', after)
                return if (close < 0) -1 else close + 1
            }
            i = after          // a longer tag name such as <names>: keep looking
        }
    }

    private fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun tag(block: String, name: String): String? {
        val start = openTag(block, name, 0)
        if (start < 0) return null
        val end = block.indexOf("</$name>", start)
        if (end < 0) return null
        return unescape(block.substring(start, end).trim())
    }

    /** The value of [attr] inside the block's `<format …>` tag, wherever in the tag it stands. */
    private fun formatAttr(block: String, attr: String): String {
        val end = openTag(block, "format", 0)
        if (end < 0) return ""
        val start = block.lastIndexOf("<format", end)
        val attrs = block.substring(start + 7, end - 1)
        var i = 0
        while (true) {
            i = attrs.indexOf(attr, i)
            if (i < 0) return ""
            var j = i + attr.length
            val isName = i > 0 && attrs[i - 1].isWhitespace()
            i = j
            if (!isName) continue
            while (j < attrs.length && attrs[j].isWhitespace()) j++
            if (j >= attrs.length || attrs[j] != '=') continue
            j++
            while (j < attrs.length && attrs[j].isWhitespace()) j++
            if (j >= attrs.length || (attrs[j] != '"' && attrs[j] != '\'')) continue
            val close = attrs.indexOf(attrs[j], j + 1)
            if (close < 0) return ""
            return unescape(attrs.substring(j + 1, close))
        }
    }

    fun parseList(xml: String): List<Clip> {
        val out = ArrayList<Clip>()
        var i = 0
        while (true) {
            val start = openTag(xml, "file", i)
            if (start < 0) break
            val end = xml.indexOf("</file>", start)
            if (end < 0) break
            i = end + 7
            val b = xml.substring(start, end)
            val path = tag(b, "name") ?: continue
            if (path.substringAfterLast('/').isEmpty()) continue      // a folder or an empty entry, not a clip
            val size = tag(b, "size")?.toLongOrNull() ?: -1L
            out.add(
                Clip(
                    path = path,
                    size = if (size < 0) -1L else size,
                    time = tag(b, "time") ?: "",
                    res = formatAttr(b, "size"),
                    secs = formatAttr(b, "time")
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- files

    // Content-Range as (first byte, total size): "bytes 100-999/1000" gives (100, 1000). The first byte
    // is -1 for the "no such range" form a 416 carries, and the total is -1 when the camera does not say.
    private fun contentRange(header: String?): Pair<Long, Long>? {
        val h = header?.trim() ?: return null
        if (!h.startsWith("bytes", ignoreCase = true)) return null
        val spec = h.substring(5).trim()
        val slash = spec.indexOf('/')
        if (slash < 0) return null
        val total = spec.substring(slash + 1).trim().toLongOrNull() ?: -1L
        val range = spec.substring(0, slash).trim()
        val start = if (range == "*") -1L else range.substringBefore('-').trim().toLongOrNull() ?: return null
        return Pair(start, total)
    }

    private inline fun <T> storage(what: String, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: Exception) {
        throw StorageException("could not $what on the phone (${describe(e)})", e)
    }

    /**
     * Downloads [clip] into [sink], continuing from whatever [sink] already holds.
     * Returns normally only when the file is complete. Otherwise it throws:
     *  - [CancelledException] when [cancelled] said so,
     *  - [StorageException] when the phone side failed,
     *  - [HttpException] when the camera refused,
     *  - [RestartException] when the part on the phone had to be thrown away,
     *  - any other IOException when the link dropped (call again to continue from the same byte).
     */
    fun download(clip: Clip, sink: Sink, cancelled: () -> Boolean, progress: (have: Long, total: Long) -> Unit) {
        exclusive(FOREVER) { transfer(clip, sink, cancelled, progress) }
    }

    private fun transfer(clip: Clip, sink: Sink, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        if (cancelled()) throw CancelledException()
        var have = storage("read the unfinished file") { sink.length() }
        if (clip.size > 0 && have == clip.size) return
        val c = open(filePath(clip.path), downloadTimeoutMs)
        try {
            c.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            // What the camera says it is sending is believed over the size in the file list: the list
            // can be stale (a clip still being recorded), the transfer cannot.
            var total = -1L
            when (val code = c.responseCode) {
                206 -> {
                    val range = contentRange(c.getHeaderField("Content-Range"))
                    val start = if (range == null || range.first < 0) have else range.first
                    if (start != have) {
                        storage("reset the unfinished file") { sink.truncate(0) }
                        if (start != 0L) throw RestartException("the camera sent bytes from $start instead of $have")
                        have = 0L                      // it sent the file from the top: take that
                    }
                    total = range?.second ?: -1L
                    if (total <= 0) c.contentLengthLong.let { if (it >= 0) total = have + it }
                }
                200 -> {
                    if (have > 0) {                    // camera ignored the Range header: start this file over
                        storage("reset the unfinished file") { sink.truncate(0) }
                        have = 0L
                    }
                    total = c.contentLengthLong
                }
                416 -> {
                    // Asked to continue past the end.
                    val size = contentRange(c.getHeaderField("Content-Range"))?.second ?: -1L
                    if (size >= 0 && size == have) return            // the phone has exactly what the camera has
                    if (size < 0 && clip.size <= 0) return           // no size from anywhere: it can only mean "already complete"
                    storage("reset the unfinished file") { sink.truncate(0) }
                    throw RestartException("resume point rejected, restarting this file")
                }
                else -> throw HttpException(code)
            }
            val expected = if (total > 0) total else if (clip.size > 0) clip.size else -1L
            val ins = c.inputStream
            try {
                val buf = ByteArray(BUFFER)
                while (true) {
                    if (cancelled()) throw CancelledException()
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    storage("write the file") { sink.append(buf, n) }
                    have += n
                    progress(have, expected)
                }
            } finally {
                try {
                    ins.close()
                } catch (e: Exception) {
                    // the connection is being dropped anyway
                }
            }
            if (have == 0L) throw IOException("the camera sent an empty file")
            if (expected > 0 && have < expected) throw IOException("stream ended at $have of $expected bytes")
        } finally {
            c.disconnect()
        }
    }
}
