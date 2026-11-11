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

    /** Not sent at all: it is not one of the reads this app may make. Trying again will not change that. */
    class RefusedException(message: String) : IOException(message)

    /**
     * The camera answered the request for a file and then sent not a single new byte. Unlike a dropped
     * link this is about that one file: if it keeps happening, the file cannot be had.
     */
    class NoDataException(message: String, cause: Throwable? = null) : IOException(message, cause)

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
     *
     * A file path is taken literally and must stay literal on its way to the camera. Android's HTTP
     * client and most web servers quietly resolve "/./", "/../" and "//", and some decode "%3F" back
     * into "?": a path built from those could arrive as a different request than the one checked
     * here. So such paths are not cleaned up, they are refused.
     */
    @Throws(IOException::class)
    fun requireRead(pathAndQuery: String) {
        val q = pathAndQuery.indexOf('?')
        if (q < 0) {
            val lower = pathAndQuery.lowercase(Locale.US)
            val ok = pathAndQuery.startsWith("/") &&
                pathAndQuery.none { it == '#' || it == '\\' || it.code <= 0x20 || it.code >= 0x7f } &&
                pathAndQuery.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
                !lower.contains("cgi-bin") && !lower.contains(".cgi") &&
                !hidesStructure(pathAndQuery)
            if (!ok) throw RefusedException("refused: not a file on the camera: $pathAndQuery")
            return
        }
        // Every query this app sends is spelled out in plain letters. An escape could only be there to
        // hide something from this check that a camera might still read: "%26action%3Ddel".
        if (pathAndQuery.substring(0, q) != "/cgi-bin/Config.cgi" || '%' in pathAndQuery || '#' in pathAndQuery) {
            throw RefusedException("refused: unknown camera command: $pathAndQuery")
        }
        val actions = ArrayList<String>()
        for (pair in pathAndQuery.substring(q + 1).split('&')) {
            val key = decode(pair.substringBefore('='))
            if (key.equals("action", ignoreCase = true)) actions.add(decode(pair.substringAfter('=', "")))
        }
        if (actions.size != 1 || actions[0] !in readActions) {
            throw RefusedException("refused: only reading from the camera is allowed: $pathAndQuery")
        }
    }

    /** True if a %XX escape in [path] stands for a character that has a meaning in a URL, or is not an escape at all. */
    private fun hidesStructure(path: String): Boolean {
        var i = path.indexOf('%')
        while (i >= 0) {
            val code = if (i + 2 < path.length) path.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (code == null || code < 0x20 || code == 0x7f || code.toChar() in "%?#/\\.") return true
            i = path.indexOf('%', i + 1)
        }
        return false
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

    /**
     * The request path for a file from the list: always absolute, with spaces and non-ASCII letters
     * escaped. A name containing ? # % or a backslash is refused rather than escaped: no clip on a
     * memory card is called that, and nothing in a file name may ever start a query.
     */
    @Throws(IOException::class)
    fun filePath(path: String): String {
        if (path.any { it == '?' || it == '#' || it == '%' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            throw RefusedException("refused: not a file name a camera would use: $path")
        }
        return escape(if (path.startsWith("/")) path else "/$path", "/-._~!$&'()*+,;=:@")
    }

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

    /**
     * The folder names come from the camera's own settings. They go into the request as they are, so
     * only names made of plain letters, digits, ".", "_" and "-" are accepted; anything else is refused
     * rather than passed on in some escaped form.
     */
    @Throws(IOException::class)
    fun listUrl(folder: String, rear: Boolean, from: Int, count: Int): String {
        val action = if (rear) "reardir" else "dir"
        val prop = folder.substringBefore(',').replace("/", "")
        if (prop.isEmpty() || prop.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") }) {
            throw RefusedException("refused: not a folder name a camera would use: $folder")
        }
        return "/cgi-bin/Config.cgi?action=$action&property=$prop&count=$count&from=$from&format=all&backward="
    }

    fun list(folder: String, rear: Boolean, from: Int, count: Int): List<Clip> =
        parseList(get(listUrl(folder, rear, from, count), 15000))

    /**
     * Reads a whole folder, [PAGE] files at a time, newest first. It always ends: on an empty page, on
     * two pages in a row with nothing new (a camera that repeats itself), at [MAX_FILES], or when
     * [keepGoing] says no. A failure part-way does not lose what was already read; it comes back in
     * [Listing.error].
     */
    fun listAll(folder: String, rear: Boolean, keepGoing: () -> Boolean, loaded: (count: Int) -> Unit): Listing {
        val all = ArrayList<Clip>()
        val seen = HashSet<String>()
        var error: String? = null
        var truncated = false
        try {
            session {
                var pages = 0
                var from = 0
                var barren = 0
                while (keepGoing()) {
                    if (all.size >= MAX_FILES || pages >= MAX_PAGES) {
                        truncated = true
                        break
                    }
                    val page = parsePage(get(listUrl(folder, rear, from, PAGE), 15000))
                    pages++
                    if (page.entries == 0) break               // the end of the folder
                    // The next page starts after everything the camera sent, whether or not it was a clip.
                    from += page.entries
                    val fresh = page.clips.filter { seen.add(it.path) }
                    if (fresh.isEmpty()) {
                        // Entries that are not clips, or a camera repeating itself. One such page is
                        // passed over; a second one in a row means there is no point in asking on.
                        if (++barren >= 2) break
                        continue
                    }
                    barren = 0
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

    /** One reply to a list request: the clips in it, and how many entries the camera sent (not all need be clips). */
    class Page(val clips: List<Clip>, val entries: Int)

    fun parseList(xml: String): List<Clip> = parsePage(xml).clips

    fun parsePage(xml: String): Page {
        val out = ArrayList<Clip>()
        var entries = 0
        var i = 0
        while (true) {
            val start = openTag(xml, "file", i)
            if (start < 0) break
            val end = xml.indexOf("</file>", start)
            if (end < 0) break
            i = end + 7
            entries++
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
        return Page(out, entries)
    }

    // ---------------------------------------------------------------- files

    /** What a Content-Range header says. [start] is -1 for the "no such range" form; [total] is -1 when it is not given. */
    private class Range(val start: Long, val total: Long)

    /** A Content-Range header that is there but makes no sense. */
    private val unreadable = Range(-2, -1)

    /**
     * Reads "bytes 100-999/1000" - also with "=" where the space belongs and without the total, as
     * some small web servers write it. Null means there is no header at all; [unreadable] means there
     * is one that cannot be understood. The two must not be confused: no header can be taken on
     * trust, a garbled one cannot.
     */
    private fun contentRange(header: String?): Range? {
        val h = header?.trim()
        if (h.isNullOrEmpty()) return null
        if (!h.startsWith("bytes", ignoreCase = true)) return unreadable
        val spec = h.substring(5).trimStart(' ', '\t', '=', ':')
        val slash = spec.indexOf('/')
        val span = (if (slash >= 0) spec.substring(0, slash) else spec).trim()
        val total = if (slash >= 0) spec.substring(slash + 1).trim().toLongOrNull() ?: -1L else -1L
        if (span == "*") return Range(-1, total)
        val first = span.substringBefore('-').trim().toLongOrNull() ?: return unreadable
        return if (first < 0) unreadable else Range(first, total)
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
     *  - [RefusedException] when the clip's path is not something this app will ask for,
     *  - [HttpException] when the camera refused,
     *  - [NoDataException] when the camera answered and then sent nothing new,
     *  - [RestartException] when the part on the phone had to be thrown away,
     *  - any other IOException when the link dropped (call again to continue from the same byte).
     */
    fun download(clip: Clip, sink: Sink, cancelled: () -> Boolean, progress: (have: Long, total: Long) -> Unit) {
        // Waiting for the camera to be free (a long file listing may be under way) must not be deaf to Stop.
        while (true) {
            if (cancelled()) throw CancelledException()
            val mine = try {
                gate.tryLock(250, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting for the camera")
            }
            if (mine) break
        }
        try {
            transfer(clip, sink, cancelled, progress)
        } finally {
            gate.unlock()
        }
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
            var total = -1L             // how long the camera says the file is
            var fileSize = false        // …and whether it said so about the file itself (Content-Range), not just this reply
            when (val code = c.responseCode) {
                206 -> {
                    val range = contentRange(c.getHeaderField("Content-Range"))
                    // No Content-Range: trusted to be what was asked for. One that says something else, or
                    // that cannot be read: appending these bytes could silently corrupt the clip.
                    val from = when {
                        range == null -> have
                        range === unreadable -> if (have == 0L) 0L else -2L
                        else -> range.start
                    }
                    if (from != have) {
                        storage("reset the unfinished file") { sink.truncate(0) }
                        if (from != 0L) {
                            throw RestartException(
                                if (from == -2L) "the camera did not say which part of the file it sent"
                                else "the camera sent bytes from $from instead of $have"
                            )
                        }
                        have = 0L                      // it sent the file from the top: take that
                    }
                    if (range != null && range.total > 0) {
                        total = range.total
                        fileSize = true
                    } else {
                        c.contentLengthLong.let { if (it >= 0) total = have + it }
                    }
                }
                200 -> {
                    if (have > 0) {                    // camera ignored the Range header: start this file over
                        storage("reset the unfinished file") { sink.truncate(0) }
                        have = 0L
                    }
                    total = c.contentLengthLong
                }
                416 -> {
                    // Asked to continue past the end. That is only good news if the camera says its file is
                    // exactly as long as what the phone has; otherwise the part on the phone cannot be trusted.
                    val size = contentRange(c.getHeaderField("Content-Range"))?.total ?: -1L
                    if (have > 0 && size == have) return
                    storage("reset the unfinished file") { sink.truncate(0) }
                    throw RestartException("resume point rejected, restarting this file")
                }
                else -> throw HttpException(code)
            }
            val expected = if (total > 0) total else if (clip.size > 0) clip.size else -1L
            var received = 0L
            try {
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
                        received += n
                        progress(have, expected)
                    }
                } finally {
                    try {
                        ins.close()
                    } catch (e: Exception) {
                        // the connection is being dropped anyway
                    }
                }
            } catch (e: IOException) {
                if (received == 0L && e !is CancelledException && e !is StorageException) {
                    throw NoDataException("the camera answered but sent no data (${describe(e)})", e)
                }
                throw e
            }
            if (received == 0L) {
                // The reply had no body at all.
                val known = if (fileSize) total else clip.size
                when {
                    have > 0 && known > 0 && have >= known -> return        // nothing was left to send
                    have == 0L -> throw NoDataException("the camera sent an empty file")
                    known > 0 -> throw NoDataException("the camera sent nothing after byte $have of $known")
                    else -> {
                        // No size from anywhere: whether the part on the phone is the whole file cannot be told.
                        storage("reset the unfinished file") { sink.truncate(0) }
                        throw RestartException("cannot tell whether the part on the phone is complete, restarting this file")
                    }
                }
            }
            if (fileSize && have > total) {
                // More bytes than the file is long: something was appended that does not belong to it.
                storage("reset the unfinished file") { sink.truncate(0) }
                throw RestartException("received $have bytes of a file the camera says has $total")
            }
            if (expected > 0 && have < expected) throw IOException("stream ended at $have of $expected bytes")
        } finally {
            c.disconnect()
        }
    }
}
