package com.dompka.dashlink

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real pictures for the emulator tests, made on the device: a test picture (a road at dusk whose lane marks
 * stream towards the viewer, so that every frame differs), encoded with the device's own H.264 encoder into an MP4
 * the way a dashcam writes one, or into the access units FakeRtsp sends, or into Motion JPEG.
 *
 * Nothing here is camera footage: it only gives the app's decoders something genuine to chew on.
 */
internal object TestVideo {

    /** An H.264 stream: its parameter sets and its access units (NAL units without start codes, 90 kHz times). */
    class H264(val sps: ByteArray, val pps: ByteArray, val units: List<FakeRtsp.AccessUnit>, val fps: Int) {
        /** A live stream that plays the units again and again, as a camera's never ends. */
        fun stream(): FakeRtsp.Stream {
            val period = units.size * 90_000L / fps
            val s = FakeRtsp.Stream("H264") { loop(units.size) { i, round -> FakeRtsp.AccessUnit(units[i].nals, units[i].time + round * period) } }
            s.sps = sps
            s.pps = pps
            s.intervalMs = 1000L / fps
            return s
        }
    }

    /** A Motion JPEG live stream of [frames], played again and again. */
    fun jpegStream(frames: List<FakeRtsp.JpegFrame>, fps: Int): FakeRtsp.Stream {
        val period = frames.size * 90_000L / fps
        val s = FakeRtsp.Stream("JPEG") {
            loop(frames.size) { i, round ->
                val f = frames[i]
                FakeRtsp.JpegFrame(f.scan, f.type, f.q, f.width, f.height, f.time + round * period, f.tables, f.precision)
            }
        }
        s.intervalMs = 1000L / fps
        return s
    }

    private fun loop(n: Int, make: (index: Int, round: Long) -> FakeRtsp.Picture): FakeRtsp.Source {
        var i = 0L
        return FakeRtsp.Source { make((i % n).toInt(), i++ / n) }
    }

    // ---------------------------------------------------------------- the test picture

    /** Frame [index] at [fps] of the test picture, [w] x [h] ARGB pixels. */
    fun picture(w: Int, h: Int, index: Int, fps: Int): IntArray {
        val seconds = index.toDouble() / fps
        val out = IntArray(w * h)
        val horizon = (h * 0.46).toInt()
        val cx = w * (0.5 + 0.05 * sin(seconds * 0.8))                  // the road sways a little
        val sunX = w * 0.72
        val sunY = horizon - h * 0.07
        val sunR = h * 0.075
        for (y in 0 until h) {
            val row = y * w
            if (y < horizon) {
                val k = y.toDouble() / horizon
                val sky = mix(0xFF1E3550.toInt(), 0xFFE79A62.toInt(), k * k)
                for (x in 0 until w) {
                    val dx = x - sunX
                    val dy = y - sunY
                    val d = sqrt(dx * dx + dy * dy)
                    out[row + x] = when {
                        d < sunR -> 0xFFFFD27A.toInt()
                        d < sunR * 2.2 -> mix(0xFFFFC56A.toInt(), sky, (d - sunR) / (sunR * 1.2))
                        else -> sky
                    }
                }
                continue
            }
            val depth = (y - horizon + 1).toDouble() / (h - horizon)       // 0 at the horizon, 1 at the bottom
            val half = w * 0.62 * depth + 2
            val dashOn = ((1.6 / depth + seconds * 6.0) % 2.0) < 1.0         // the centre dashes come closer
            val dashHalf = max(1.0, w * 0.008 * depth)
            val edgeAt = half * 0.9
            val edgeHalf = max(1.0, w * 0.006 * depth)
            val verge = mix(0xFF1F2B22.toInt(), 0xFF2F4A33.toInt(), depth)
            val asphalt = mix(0xFF4A4C52.toInt(), 0xFF35383D.toInt(), depth)
            for (x in 0 until w) {
                val dx = abs(x - cx)
                out[row + x] = when {
                    dx > half -> verge
                    abs(dx - edgeAt) < edgeHalf -> 0xFFE6E4DA.toInt()
                    dx < dashHalf && dashOn -> 0xFFF2F0E6.toInt()
                    else -> asphalt
                }
            }
        }
        return out
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(s: Int) = (((a ushr s) and 0xff) + (((b ushr s) and 0xff) - ((a ushr s) and 0xff)) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---------------------------------------------------------------- H.264

    /**
     * [seconds] of the test picture as an MP4 file's bytes: H.264 from the device's encoder, a key frame every
     * second, written by MediaMuxer, which puts the index (moov) after the pictures, as a dashcam recording does.
     */
    fun mp4(seconds: Int, width: Int, height: Int, fps: Int): ByteArray {
        val file = File.createTempFile("testvideo", ".mp4", Kit.ctx.cacheDir)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        try {
            encode(seconds * fps, width, height, fps, onFormat = { f ->
                track = muxer.addTrack(f)
                muxer.start()
            }) { buffer, info, config ->
                // The parameter sets travel in the track's format; only pictures are samples.
                if (!config) {
                    check(track >= 0) { "the encoder sent pictures before its format" }
                    muxer.writeSampleData(track, buffer, info)
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return try {
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** [seconds] of the test picture as H.264 access units for FakeRtsp, parameter sets in front of every key frame. */
    fun h264(seconds: Int, width: Int, height: Int, fps: Int): H264 {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val units = ArrayList<FakeRtsp.AccessUnit>()
        encode(seconds * fps, width, height, fps, onFormat = {}) { buffer, info, config ->
            val bytes = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(bytes)
            val nals = nals(bytes)
            if (config) {
                sps = nals.firstOrNull { it[0].toInt() and 0x1f == 7 }
                pps = nals.firstOrNull { it[0].toInt() and 0x1f == 8 }
                return@encode
            }
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val inBand = nals.any { it[0].toInt() and 0x1f == 7 }
            val all = if (key && !inBand) listOf(checkNotNull(sps), checkNotNull(pps)) + nals else nals
            units.add(FakeRtsp.AccessUnit(all, units.size * 90_000L / fps))
        }
        return H264(checkNotNull(sps) { "the encoder gave no SPS" }, checkNotNull(pps) { "the encoder gave no PPS" }, units, fps)
    }

    /** The NAL units of an Annex-B buffer, without their start codes. */
    private fun nals(b: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()                 // where each start code begins, where its NAL begins
        var i = 0
        while (i + 3 <= b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val begin = if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i
                starts.add(begin to i + 3)
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, (_, from) ->
            val to = if (n + 1 < starts.size) starts[n + 1].first else b.size
            b.copyOfRange(from, to)
        }.filter { it.isNotEmpty() }
    }

    private fun encoder(): MediaCodec {
        val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
            .map { it.name }
        // The platform's own software encoder where there is one: the emulators have no other that works.
        val name = names.firstOrNull { it == "c2.android.avc.encoder" } ?: names.firstOrNull()
            ?: throw AssertionError("this device has no H.264 encoder")
        return MediaCodec.createByCodecName(name)
    }

    /** Encodes [frames] pictures; [out] gets every output buffer (codec config ones flagged), [onFormat] the format. */
    private fun encode(
        frames: Int, width: Int, height: Int, fps: Int,
        onFormat: (MediaFormat) -> Unit,
        out: (java.nio.ByteBuffer, MediaCodec.BufferInfo, Boolean) -> Unit,
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * fps / 2)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val codec = encoder()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var done = false
            val deadline = System.currentTimeMillis() + 120_000
            while (!done) {
                check(System.currentTimeMillis() < deadline) { "the encoder took more than two minutes" }
                if (fed <= frames) {
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val pts = fed * 1_000_000L / fps
                        if (fed == frames) {
                            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val image = checkNotNull(codec.getInputImage(index)) { "the encoder gave no input image" }
                            fill(image, picture(width, height, fed, fps), width, height)
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
                        }
                        fed++
                    }
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (fed > frames) 10_000 else 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        onFormat(codec.outputFormat)
                        continue
                    }
                    if (index < 0) break
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0) out(buffer, info, config)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        done = true
                        break
                    }
                }
            }
            codec.stop()
        } finally {
            codec.release()
        }
    }

    /** Writes [argb] into a YUV 4:2:0 input image, whatever its strides (BT.601, studio range). */
    private fun fill(image: Image, argb: IntArray, w: Int, h: Int) {
        val (yp, up, vp) = image.planes
        val yb = yp.buffer
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val r = (c ushr 16) and 0xff
                val g = (c ushr 8) and 0xff
                val b = c and 0xff
                yb.put(y * yp.rowStride + x * yp.pixelStride, (16 + (66 * r + 129 * g + 25 * b + 128) / 256).toByte())
            }
        }
        val ub = up.buffer
        val vb = vp.buffer
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                val c = argb[(2 * y) * w + 2 * x]
                val r = (c ushr 16) and 0xff
                val g = (c ushr 8) and 0xff
                val b = c and 0xff
                ub.put(y * up.rowStride + x * up.pixelStride, (128 + (-38 * r - 74 * g + 112 * b + 128) / 256).toByte())
                vb.put(y * vp.rowStride + x * vp.pixelStride, (128 + (112 * r - 94 * g - 18 * b + 128) / 256).toByte())
            }
        }
    }

    // ---------------------------------------------------------------- Motion JPEG

    /**
     * [n] pictures as RFC 2435 frames at [fps]: each one compressed with Bitmap.compress, then taken apart and its
     * scan coded again with the standard Huffman tables. RFC 2435 has no room for Huffman tables, so a receiver
     * rebuilds the image with the standard ones; Android's encoder computes its own for every picture.
     */
    fun jpeg(w: Int, h: Int, n: Int, fps: Int = 25): List<FakeRtsp.JpegFrame> = (0 until n).map { i ->
        val bitmap = Bitmap.createBitmap(picture(w, h, i, fps), w, h, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        val p = RtpJpeg.split(bytes)
        check(p.width == w && p.height == h) { "the JPEG is ${p.width}x${p.height}, not ${w}x$h" }
        FakeRtsp.JpegFrame(p.scan, p.type, 255, w, h, i * 90_000L / fps, tables = p.tables)
    }
}

/**
 * The pretend camera the media tests share: FakeCam for files, FakeRtsp for the live view, FakeRadio for the link.
 * It points the app at them, and on [close] stops every download, disconnects, and puts back every global it
 * changed (Cam.opener and its time-out, Live.sockets, Live.host, Link.radio).
 */
internal class MediaRig : java.io.Closeable {
    val cam = FakeCam()
    val rtsp = FakeRtsp()
    val radio = FakeRadio()
    private val oldOpener = Cam.opener
    private val oldTimeout = Cam.downloadTimeoutMs
    private val oldSockets = Live.sockets
    private val oldHost = Live.host
    private var oldRadio: LinkCore.Radio? = null

    init {
        Cam.opener = cam.opener()
        Cam.downloadTimeoutMs = 5000
        Live.sockets = rtsp.sockets()
        Kit.onMain {
            oldRadio = Link.radio
            Link.radio = radio
        }
    }

    /** Connects the pretend link and waits until the app can reach the "camera". */
    fun connect() {
        Kit.onMain { Link.connect(Kit.ctx, "MiVue", "12345678", false) }
        Kit.until("the pretend link to come up") { Link.ready }
    }

    /** Puts [data] on the camera as [name] in [folder] (with its real size in the list) and returns the clip. */
    fun clip(folder: String, name: String, data: ByteArray, time: String, res: String = "640x360", secs: String = "20"): Cam.Clip {
        val path = "/SD/$folder/F/$name"
        cam.files[path] = data
        cam.folders[folder] = (cam.folders[folder] ?: emptyList()) + FakeCam.Entry(path, data.size.toLong(), time, res, secs)
        return Cam.Clip(path, data.size.toLong(), time, res, secs)
    }

    /** A download of [clip] that takes about [seconds] (FakeCam sends it slowly), started through the service. */
    fun slowDownload(clip: Cam.Clip, seconds: Int) {
        val chunk = 16 * 1024
        val delay = seconds * 1000L * chunk / maxOf(1L, clip.size)
        val earlier = cam.tweak
        cam.tweak = { req, reply ->
            earlier?.invoke(req, reply)
            if (req.path == clip.path) {
                reply.chunk = chunk
                reply.chunkDelayMs = maxOf(1L, delay)
            }
        }
        check(DownloadService.enqueue(Kit.ctx, listOf(clip)) == null) { "Android did not start the download service" }
        Kit.until("the download to be under way") { DownloadService.working }
    }

    override fun close() {
        try {
            DownloadService.stopAll(Kit.ctx)
            Kit.onMain { Link.disconnect() }
            Kit.until("the download service to stop", 20_000) { !DownloadService.alive && !DownloadService.working }
        } finally {
            Kit.onMain { oldRadio?.let { Link.radio = it } }
            Cam.opener = oldOpener
            Cam.downloadTimeoutMs = oldTimeout
            Live.sockets = oldSockets
            Live.host = oldHost
            rtsp.close()
            cam.close()
        }
    }
}

/**
 * A baseline JPEG taken apart the way an RFC 2435 sender does it: its quantization tables, its layout (type 0 for
 * 4:2:2, 1 for 4:2:0) and its scan, the scan re-coded with the standard Huffman tables of JPEG Annex K (the ones
 * the receiver assumes). Restart markers in the source are taken out. Pure Kotlin.
 */
internal object RtpJpeg {
    class Parts(val scan: ByteArray, val tables: ByteArray, val type: Int, val width: Int, val height: Int)

    private class Huffman(bits: IntArray, values: IntArray) {
        val maxCode = IntArray(18) { -1 }
        val valPtr = IntArray(17)
        val minCode = IntArray(17)
        val values = values.copyOf()

        /** For encoding: the code and its length for each value. */
        val code = IntArray(256)
        val size = IntArray(256)

        init {
            var c = 0
            var k = 0
            for (len in 1..16) {
                valPtr[len] = k
                minCode[len] = c
                repeat(bits[len - 1]) {
                    code[values[k]] = c
                    size[values[k]] = len
                    c++
                    k++
                }
                maxCode[len] = if (bits[len - 1] > 0) c - 1 else -1
                c = c shl 1
            }
        }
    }

    private class Reader(private val b: ByteArray, var at: Int) {
        private var acc = 0
        private var count = 0

        fun bit(): Int {
            if (count == 0) {
                acc = next()
                count = 8
            }
            count--
            return (acc ushr count) and 1
        }

        /** The next byte of coded data. FF 00 is a stuffed FF; at a marker (or the end) it gives ones and stays put. */
        private fun next(): Int {
            if (at >= b.size) return 0xff
            val v = b[at].toInt() and 0xff
            if (v != 0xff) {
                at++
                return v
            }
            val following = if (at + 1 < b.size) b[at + 1].toInt() and 0xff else 0xd9
            if (following == 0) at += 2
            return 0xff
        }

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        fun decode(t: Huffman): Int {
            var c = bit()
            var len = 1
            while (len <= 16 && c > t.maxCode[len]) {
                c = (c shl 1) or bit()
                len++
            }
            check(len <= 16) { "a Huffman code that is in no table" }
            return t.values[t.valPtr[len] + c - t.minCode[len]]
        }

        /** Skips to the next byte and over the restart marker there. */
        fun restart() {
            count = 0
            check(at + 1 < b.size && b[at].toInt() and 0xff == 0xff && (b[at + 1].toInt() and 0xff) in 0xd0..0xd7) {
                "no restart marker where one belongs"
            }
            at += 2
        }
    }

    private class Writer {
        val out = ByteArrayOutputStream()
        private var acc = 0
        private var count = 0

        fun bits(value: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                acc = (acc shl 1) or ((value ushr i) and 1)
                if (++count == 8) {
                    out.write(acc)
                    if (acc == 0xff) out.write(0)                // byte stuffing
                    acc = 0
                    count = 0
                }
            }
        }

        fun code(t: Huffman, value: Int) {
            check(t.size[value] > 0) { "value $value is not in the standard table" }
            bits(t.code[value], t.size[value])
        }

        fun finish(): ByteArray {
            while (count != 0) bits(1, 1)                        // pad the last byte with ones
            return out.toByteArray()
        }
    }

    private val stdDc = arrayOf(
        Huffman(JpegDepacketizer.DC_LUMA_BITS, JpegDepacketizer.DC_VALUES),
        Huffman(JpegDepacketizer.DC_CHROMA_BITS, JpegDepacketizer.DC_VALUES),
    )
    private val stdAc = arrayOf(
        Huffman(JpegDepacketizer.AC_LUMA_BITS, JpegDepacketizer.AC_LUMA_VALUES),
        Huffman(JpegDepacketizer.AC_CHROMA_BITS, JpegDepacketizer.AC_CHROMA_VALUES),
    )

    private class Component(val id: Int, val h: Int, val v: Int, val tq: Int) {
        var dc = 0
        var ac = 0
    }

    fun split(jpeg: ByteArray): Parts {
        fun u8(i: Int) = jpeg[i].toInt() and 0xff
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        check(u16(0) == 0xffd8) { "not a JPEG" }
        val quant = arrayOfNulls<ByteArray>(4)
        val dc = arrayOfNulls<Huffman>(4)
        val ac = arrayOfNulls<Huffman>(4)
        val comps = ArrayList<Component>()
        var width = 0
        var height = 0
        var restart = 0
        var i = 2
        while (i + 4 <= jpeg.size) {
            check(u8(i) == 0xff) { "lost in the JPEG at byte $i" }
            val marker = u8(i + 1)
            val end = i + 2 + u16(i + 2)
            var p = i + 4
            when (marker) {
                0xdb -> while (p < end) {
                    check(u8(p) shr 4 == 0) { "16-bit quantization tables cannot go into RFC 2435 here" }
                    quant[u8(p) and 3] = jpeg.copyOfRange(p + 1, p + 65)
                    p += 65
                }
                0xc4 -> while (p < end) {
                    val tc = u8(p) shr 4
                    val th = u8(p) and 3
                    val bits = IntArray(16) { u8(p + 1 + it) }
                    val n = bits.sum()
                    val values = IntArray(n) { u8(p + 17 + it) }
                    if (tc == 0) dc[th] = Huffman(bits, values) else ac[th] = Huffman(bits, values)
                    p += 17 + n
                }
                0xc0 -> {
                    height = u16(p + 1)
                    width = u16(p + 3)
                    for (c in 0 until u8(p + 5)) {
                        val q = p + 6 + c * 3
                        comps.add(Component(u8(q), u8(q + 1) shr 4, u8(q + 1) and 15, u8(q + 2)))
                    }
                }
                0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf ->
                    throw AssertionError("not a baseline JPEG (SOF ${"%02x".format(marker)})")
                0xdd -> restart = u16(p)
                0xda -> {
                    val n = u8(p)
                    val order = (0 until n).map { k ->
                        val id = u8(p + 1 + k * 2)
                        val sel = u8(p + 2 + k * 2)
                        comps.first { it.id == id }.also {
                            it.dc = sel shr 4
                            it.ac = sel and 15
                        }
                    }
                    return recode(jpeg, end, order, comps, quant, dc, ac, width, height, restart)
                }
            }
            i = end
        }
        throw AssertionError("the JPEG has no scan")
    }

    private fun recode(
        jpeg: ByteArray, start: Int, order: List<Component>, comps: List<Component>,
        quant: Array<ByteArray?>, dcTables: Array<Huffman?>, acTables: Array<Huffman?>,
        width: Int, height: Int, restart: Int,
    ): Parts {
        check(comps.size == 3 && order.size == 3) { "RFC 2435 carries three components, this JPEG has ${comps.size}" }
        val y = comps[0]
        check(y.h == 2 && (y.v == 2 || y.v == 1)) { "luminance sampled ${y.h}x${y.v}: RFC 2435 needs 2x1 or 2x2" }
        check(comps[1].h == 1 && comps[1].v == 1 && comps[2].h == 1 && comps[2].v == 1) { "chrominance must be sampled 1x1" }
        check(comps[1].tq == comps[2].tq) { "Cb and Cr use different quantization tables" }
        val lumaTable = checkNotNull(quant[y.tq])
        val chromaTable = checkNotNull(quant[comps[1].tq])
        val tables = if (y.tq == comps[1].tq) lumaTable else lumaTable + chromaTable
        val mcuW = 8 * y.h
        val mcuH = 8 * y.v
        val mcus = ((width + mcuW - 1) / mcuW) * ((height + mcuH - 1) / mcuH)
        val r = Reader(jpeg, start)
        val w = Writer()
        val dcIn = IntArray(3)
        val dcOut = IntArray(3)
        for (m in 0 until mcus) {
            if (restart > 0 && m > 0 && m % restart == 0) {
                r.restart()
                dcIn.fill(0)
            }
            for ((ci, c) in order.withIndex()) {
                val std = if (c === y) 0 else 1
                repeat(c.h * c.v) {
                    block(r, checkNotNull(dcTables[c.dc]), checkNotNull(acTables[c.ac]), w, stdDc[std], stdAc[std], dcIn, dcOut, ci)
                }
            }
        }
        return Parts(w.finish(), tables, if (y.v == 2) 1 else 0, width, height)
    }

    private fun extend(v: Int, size: Int): Int = if (size == 0) 0 else if (v < (1 shl (size - 1))) v - (1 shl size) + 1 else v

    private fun category(v: Int): Int {
        var a = abs(v)
        var n = 0
        while (a > 0) {
            a = a shr 1
            n++
        }
        return n
    }

    private fun put(w: Writer, v: Int, size: Int) {
        if (size > 0) w.bits(if (v >= 0) v else v + (1 shl size) - 1, size)
    }

    /** One 8x8 block: read with the source's tables, written with the standard ones; DC kept as a running total. */
    private fun block(
        r: Reader, dcIn: Huffman, acIn: Huffman, w: Writer, dcStd: Huffman, acStd: Huffman,
        predIn: IntArray, predOut: IntArray, c: Int,
    ) {
        val s = r.decode(dcIn)
        predIn[c] += extend(r.bits(s), s)
        val diff = predIn[c] - predOut[c]
        predOut[c] = predIn[c]
        val cat = category(diff)
        w.code(dcStd, cat)
        put(w, diff, cat)
        var k = 1
        var zeros = 0
        var last = false
        while (k < 64) {
            val rs = r.decode(acIn)
            val run = rs ushr 4
            val size = rs and 15
            if (size == 0) {
                if (run != 15) break                              // end of block
                k += 16
                zeros += 16
                continue
            }
            k += run
            zeros += run
            val v = extend(r.bits(size), size)
            while (zeros >= 16) {
                w.code(acStd, 0xf0)
                zeros -= 16
            }
            w.code(acStd, (zeros shl 4) or size)
            put(w, v, size)
            zeros = 0
            last = k == 63
            k++
        }
        if (!last) w.code(acStd, 0x00)
    }
}
