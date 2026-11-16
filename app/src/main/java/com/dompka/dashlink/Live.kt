package com.dompka.dashlink

import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket

/*
 * The camera's live view: Live.run finds a stream, plays it into a VideoSink and gives the camera up whenever something
 * else needs it. The pieces: LiveRun.kt (the loop, the camera's turn, retries), Rtsp.kt (requests, replies, SDP),
 * Rtp.kt and Jpeg.kt (packets back into pictures).
 *
 * What is known (from cameras of the same firmware family; nothing confirmed on a 956W): the camera serves
 * RTSP on port 554 at /liveRTSP/av1 .. av4 and /liveRTSP/v1 .. v3; front-camera video has been reported on
 * av2 and av4. "Camera.Preview.RTSP.av" (read with action=get) probably names the default av stream, and
 * "Camera.Preview.RTSP.keepalive" (60) the session time-out in seconds. Whether the server accepts RTP over
 * TCP (interleaved) or only UDP is unknown, so both are tried: TCP first, UDP when TCP is refused or shows nothing.
 * No set command is sent, ever.
 *
 * Read-only: the only RTSP methods this app may send are OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER and
 * TEARDOWN, and only to the paths [Live.requireStreamPath] accepts (Rtsp.requireRequest checks every request).
 */

/** What a camera stream carries. */
internal sealed class VideoFormat {
    /**
     * H.264. [sps] and [pps] are single NAL units WITHOUT start codes, from the SDP (sprop-parameter-sets),
     * or null if the SDP had none (they then arrive in-band). [width]/[height] are 0 when not known.
     */
    class H264(val sps: ByteArray?, val pps: ByteArray?, val width: Int, val height: Int) : VideoFormat()

    /** Motion JPEG: every frame is one complete JPEG image (JFIF, starts with FF D8, ends with FF D9). */
    class Jpeg(val width: Int, val height: Int) : VideoFormat()
}

/** Where a stream's pictures go. Called on the streaming thread. */
internal interface VideoSink {
    /** Before the first frame of a stream, and again whenever a new stream starts (a retry, another path). */
    fun onFormat(format: VideoFormat)

    /**
     * One complete picture. H.264: one access unit in Annex-B form (00 00 00 01 before every NAL unit),
     * with SPS/PPS included when the camera sent them in-band. JPEG: one complete image.
     * [data] is only valid during the call; [ptsUs] counts from the first frame of the stream.
     */
    fun onFrame(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean)
}

/** How sockets to the camera are made: on the camera's own Wi-Fi network (see Link.kt). Tests use plain sockets. */
internal interface LiveSockets {
    @Throws(IOException::class)
    fun tcp(host: String, port: Int, timeoutMs: Int): Socket

    /** A UDP socket already bound to the camera network, unconnected, on a port chosen by the system. */
    @Throws(IOException::class)
    fun udp(): DatagramSocket
}

internal object Live {
    /** Set in App.onCreate to sockets on the camera link; tests replace it. */
    @Volatile
    var sockets: LiveSockets = object : LiveSockets {
        override fun tcp(host: String, port: Int, timeoutMs: Int): Socket = throw IOException("not connected to the camera")
        override fun udp(): DatagramSocket = throw IOException("not connected to the camera")
    }

    /** The camera's address, or null while it is not known. Set in App.onCreate; tests replace it. */
    @Volatile
    var host: () -> String? = { null }

    enum class Phase {
        /** Looking for a stream that works. */
        STARTING,
        /** Pictures are arriving. */
        PLAYING,
        /** Something else needs the camera (a download, a file list, a thumbnail); it comes back by itself. */
        PAUSED_BUSY,
        /** No stream worked; [Status.detail] says why. It keeps trying, slowly. */
        UNAVAILABLE,
        /** run() returned. */
        STOPPED,
    }

    /**
     * [stream] is the path in use ("/liveRTSP/av4"), [transport] "TCP" or "UDP"; [fps] and [kbps] are measured
     * over the last second or two; [lagging] is true when pictures pile up faster than they can be shown.
     */
    class Status(
        val phase: Phase,
        val detail: String,
        val stream: String? = null,
        val transport: String? = null,
        val fps: Float = 0f,
        val kbps: Int = 0,
        val lagging: Boolean = false,
    )

    private const val PREFIX = "/liveRTSP/"

    /** After the camera's own default: the front camera's reported streams first, then the rest. */
    private val ORDER = listOf("av4", "av2", "v1", "v2", "v3", "av1", "av3")

    /** Throws [Cam.RefusedException] unless [path] is /liveRTSP/av<digit> or /liveRTSP/v<digit>. */
    fun requireStreamPath(path: String) {
        val name = if (path.startsWith(PREFIX)) path.substring(PREFIX.length) else ""
        val ok = (name.length == 3 && name.startsWith("av") && name[2] in '1'..'9') ||
            (name.length == 2 && name[0] == 'v' && name[1] in '1'..'9')
        if (!ok) throw Cam.RefusedException("refused: not a live stream: ${path.take(100)}")
    }

    /** Which stream paths to try, best first, from what the camera's settings say (Camera.Preview.*). */
    fun candidates(props: Map<String, String>): List<String> {
        val out = LinkedHashSet<String>()
        val own = previewProp(props, "Camera.Preview.RTSP.av")?.trim()?.toIntOrNull()
        if (own != null && own in 1..9) out.add("${PREFIX}av$own")
        for (name in ORDER) out.add(PREFIX + name)
        return out.toList()
    }

    /**
     * Blocking; returns only when [stop] returns true (checked at least every 250 ms). Finds a stream, plays
     * it into [sink], gives the camera up whenever [Cam.othersWaiting] says something else needs it and comes
     * back when it is free. Holds the camera (Cam.session, without ever queueing for it) only while it looks for a
     * stream or plays one. Never throws for camera or network trouble: it reports through [status] (called on the
     * streaming thread) and retries with back-off.
     */
    fun run(sink: VideoSink, stop: () -> Boolean, status: (Status) -> Unit) {
        LiveRun(sink, stop, status).run()
    }

    /** The last attempts, newest last, one line each, for the diagnostics report (no secrets in it). */
    fun log(): List<String> = LiveLog.lines()
}
