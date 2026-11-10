package com.dompka.dashlink

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * The camera's HTTP interface, as used by MiVue Pro 1.1.233.1 (read from its code, not from Mio docs):
 *
 *   settings/info : GET /cgi-bin/Config.cgi?action=get&property=<key or pattern>   -> "key=value" lines
 *   file list     : GET /cgi-bin/Config.cgi?action=dir&property=<folder>&count=N&from=K&format=all&backward=
 *                   (action=reardir for the rear camera)                             -> XML, one <file> per clip
 *   a file        : GET <the /SD/... path from the list>, supports "Range: bytes=N-"
 *   keep-alive    : GET /cgi-bin/Config.cgi?action=get&property=status
 *
 * This class only ever reads. It never sends action=set / del / format.
 */
object Cam {
    const val INFO = "/cgi-bin/Config.cgi?action=get&property=Vendor.DeviceInfo.*"
    const val STATUS = "/cgi-bin/Config.cgi?action=get&property=status"

    class Clip(val path: String, val size: Long, val time: String, val res: String, val secs: String) {
        val name: String get() = path.substringAfterLast('/')
    }

    private fun open(pathAndQuery: String, timeoutMs: Int): HttpURLConnection {
        val net = Link.network ?: throw IOException("not connected to the camera")
        val host = Link.cameraIp ?: throw IOException("camera address not known yet")
        // Opened on the camera's Network explicitly, so it works whatever the phone's main Wi-Fi is.
        val c = net.openConnection(URL("http://$host$pathAndQuery")) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        // No connection reuse: a pooled socket from before a Wi-Fi drop would fail the next request.
        c.setRequestProperty("Connection", "close")
        return c
    }

    fun get(pathAndQuery: String, timeoutMs: Int = 8000): String {
        val c = open(pathAndQuery, timeoutMs)
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun listUrl(folder: String, rear: Boolean, from: Int, count: Int): String {
        val action = if (rear) "reardir" else "dir"
        val prop = folder.substringBefore(',').replace("/", "")
        return "/cgi-bin/Config.cgi?action=$action&property=$prop&count=$count&from=$from&format=all&backward="
    }

    fun list(folder: String, rear: Boolean, from: Int, count: Int): List<Clip> =
        parseList(get(listUrl(folder, rear, from, count), 15000))

    fun parseProps(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in body.lineSequence()) {
            val i = line.indexOf('=')
            if (i > 0) out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return out
    }

    private val fileRe = Regex("<file>(.*?)</file>", RegexOption.DOT_MATCHES_ALL)

    private fun tag(block: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim()

    private fun formatAttr(block: String, attr: String): String =
        Regex("<format[^>]*\\s$attr=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""

    fun parseList(xml: String): List<Clip> {
        val out = ArrayList<Clip>()
        for (m in fileRe.findAll(xml)) {
            val b = m.groupValues[1]
            val path = tag(b, "name") ?: continue
            out.add(
                Clip(
                    path = path,
                    size = tag(b, "size")?.toLongOrNull() ?: -1L,
                    time = tag(b, "time") ?: "",
                    res = formatAttr(b, "size"),
                    secs = formatAttr(b, "time")
                )
            )
        }
        return out
    }

    /**
     * Downloads [clip] into [dest], continuing from whatever [dest] already holds.
     * Returns normally only when the file is complete; throws if the link drops (call again to resume).
     */
    fun download(clip: Clip, dest: File, cancelled: () -> Boolean, progress: (have: Long, total: Long) -> Unit) {
        var have = if (dest.exists()) dest.length() else 0L
        if (clip.size > 0 && have == clip.size) return
        if (clip.size > 0 && have > clip.size) have = 0L
        val c = open(clip.path, 15000)
        try {
            c.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            when (code) {
                206 -> Unit
                200 -> have = 0L              // camera ignored the Range header: start this file over
                416 -> {
                    // Asked to continue past the end. With no size from the list, that means "already complete".
                    if (clip.size <= 0) return
                    dest.delete()
                    throw IOException("resume point rejected, restarting this file")
                }
                else -> throw IOException("HTTP $code")
            }
            val total = if (clip.size > 0) clip.size else have + c.contentLengthLong
            RandomAccessFile(dest, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                c.inputStream.use { ins ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        progress(have, total)
                    }
                }
            }
            if (total > 0 && have < total) throw IOException("stream ended at $have of $total bytes")
        } finally {
            c.disconnect()
        }
    }
}
