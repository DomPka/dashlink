package com.dompka.dashlink

/*
 * The live view's pictures arrive as RTP (RFC 3550): H.264 packed the way RFC 6184 describes (this file) or Motion
 * JPEG the way RFC 2435 describes (Jpeg.kt). These classes turn the packets of one stream back into whole pictures.
 *
 * Every byte here comes from the camera, so every length, offset and count is checked before it is used: nothing a
 * packet says can make this code read outside the packet, hold more than [RtpDepacketizer.MAX_PICTURE] bytes or
 * throw. A picture that lost any part on the way is never passed on, because a decoder would only show it smeared.
 *
 * Plain Kotlin without Android classes, so all of it runs in JVM tests (RtpTest, JpegTest).
 */

/** A byte buffer that grows up to [limit] and is reused from one picture to the next. */
internal class PictureBuffer(initial: Int, private val limit: Int) {
    var data = ByteArray(initial)
        private set
    var length = 0

    /** False (and nothing written) when the byte would take the buffer past its limit. */
    fun put(b: Int): Boolean {
        if (!room(1)) return false
        data[length++] = b.toByte()
        return true
    }

    fun put(src: ByteArray, offset: Int, count: Int): Boolean {
        if (!room(count)) return false
        System.arraycopy(src, offset, data, length, count)
        length += count
        return true
    }

    private fun room(count: Int): Boolean {
        if (count > limit - length) return false
        if (length + count > data.size) data = data.copyOf(minOf(limit, maxOf(data.size * 2, length + count)))
        return true
    }
}

/** The fields of one RTP packet's header, read in place (nothing is copied). */
internal class RtpHeader {
    var payloadType = 0
    var marker = false
    var sequence = 0

    /** Unsigned 32-bit. */
    var timestamp = 0L
    var ssrc = 0
    var payloadOffset = 0
    var payloadLength = 0

    /** Reads the packet in [buf] from [offset], [length] bytes long. False if it is not an RTP packet. */
    fun read(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 12 || offset < 0 || offset + length > buf.size) return false
        val b0 = buf[offset].toInt() and 0xff
        if (b0 ushr 6 != 2) return false
        val b1 = buf[offset + 1].toInt() and 0xff
        marker = b1 and 0x80 != 0
        payloadType = b1 and 0x7f
        sequence = u16(buf, offset + 2)
        timestamp = (u16(buf, offset + 4).toLong() shl 16) or u16(buf, offset + 6).toLong()
        ssrc = (u16(buf, offset + 8) shl 16) or u16(buf, offset + 10)
        var start = offset + 12 + (b0 and 0x0f) * 4          // after the CSRC list
        var end = offset + length
        if (start > end) return false
        if (b0 and 0x10 != 0) {                                // a header extension: skipped
            if (start + 4 > end) return false
            start += 4 + u16(buf, start + 2) * 4
            if (start > end) return false
        }
        if (b0 and 0x20 != 0) {                                // padding: its last byte says how much
            if (end <= start) return false
            val pad = buf[end - 1].toInt() and 0xff
            if (pad == 0 || pad > end - start) return false
            end -= pad
        }
        payloadOffset = start
        payloadLength = end - start
        return true
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}

/**
 * Turns the RTP packets of one video stream into whole pictures, handed to [out] with their time in microseconds
 * from the first picture. Packets of other payload types (audio, RTCP sent to the same port) are ignored. A lost
 * packet - a gap in the sequence numbers, or a different SSRC - tells the subclass to throw away the picture it was
 * putting together; a repeated or late packet is ignored.
 */
internal abstract class RtpDepacketizer(private val payloadType: Int, clockRate: Int) {
    fun interface Out {
        fun picture(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean)
    }

    var out: Out = Out { _, _, _, _ -> }

    /** RTP packets of this stream so far. */
    var packets = 0L
        private set

    /** Pictures handed to [out] so far. */
    var pictures = 0L
        private set

    /** A camera that names a strange clock rate is taken to mean the usual 90 kHz of video. */
    private val clock = if (clockRate in 1000..1_000_000) clockRate.toLong() else 90_000L
    private val header = RtpHeader()
    private var seen = false
    private var lastSequence = 0

    /** Packets in a row that looked late. Many of them mean the camera started counting again, not lateness. */
    private var late = 0
    private var ssrc = 0
    private var lastTime = 0L

    /** The time of the latest picture in clock ticks since the first one, on a clock that does not wrap. */
    private var ticks = 0L

    /** One packet as it arrived: a UDP datagram, or the payload of an interleaved frame. */
    fun push(buf: ByteArray, offset: Int, length: Int) {
        val h = header
        if (!h.read(buf, offset, length) || h.payloadType != payloadType) return
        if (!seen) {
            seen = true
            ssrc = h.ssrc
        } else if (h.ssrc != ssrc) {
            ssrc = h.ssrc
            lost()
        } else {
            val step = (h.sequence - lastSequence) and 0xffff
            if (step == 0 || step >= 0x8000) {
                // A copy of a packet already seen, or one that came too late: ignored, unless it keeps happening.
                if (++late <= MAX_LATE) return
                lost()
            } else if (step != 1) {
                lost()
            }
        }
        late = 0
        lastSequence = h.sequence
        packets++
        payload(buf, h.payloadOffset, h.payloadLength, h.timestamp, h.marker)
    }

    /** The payload of the next packet in sequence. [timestamp] is unsigned 32-bit. */
    protected abstract fun payload(buf: ByteArray, offset: Int, length: Int, timestamp: Long, marker: Boolean)

    /** One or more packets went missing before the next [payload]. */
    protected abstract fun lost()

    /** What the sink must be told before the first picture; asked just before it goes out. */
    abstract fun format(): VideoFormat

    protected fun emit(data: ByteArray, length: Int, timestamp: Long, keyFrame: Boolean) {
        if (pictures > 0) {
            // The difference as a signed 32-bit number survives the timestamp's wrap-around. A jump of more than
            // ten seconds is not time passing but a new start (a camera that reset its clock): it adds nothing.
            val step = (timestamp - lastTime).toInt().toLong()
            if (step in -10 * clock..10 * clock) ticks += step
        }
        lastTime = timestamp
        pictures++
        out.picture(data, length, maxOf(0L, ticks) * 1_000_000L / clock, keyFrame)
    }

    companion object {
        /** The most one picture may take up; a bigger one means a broken stream and is thrown away. */
        const val MAX_PICTURE = 4 * 1024 * 1024

        private const val MAX_LATE = 50
    }
}

/**
 * H.264 per RFC 6184: single NAL unit packets (types 1-23), STAP-A (24) and FU-A (28). The other packet types
 * belong to the RFC's interleaved mode and are ignored.
 *
 * An access unit (one picture) ends with the packet that has the marker bit, or, for a camera that forgets the
 * marker, when the timestamp changes. It goes out in Annex-B form (00 00 00 01 before every NAL unit).
 *
 * Nothing goes out before the first IDR picture, and after any loss nothing goes out until the next one: every
 * picture in between refers to the one that was lost. Parameter sets the camera sends in-band are remembered; one
 * that comes in a unit without a picture is put in front of the next picture that goes out, unless that picture
 * already carries one.
 */
internal class H264Depacketizer(
    payloadType: Int,
    clockRate: Int,
    private val sdpSps: ByteArray?,
    private val sdpPps: ByteArray?,
) : RtpDepacketizer(payloadType, clockRate) {

    private val unit = PictureBuffer(256 * 1024, MAX_PICTURE)
    private var open = false
    private var unitTime = 0L
    private var hasIdr = false
    private var hasSlice = false
    private var hasSps = false
    private var hasPps = false
    private var damaged = false

    /** Where the NAL unit being put together from FU-A packets starts in [unit], or -1. */
    private var fragment = -1

    /** Waiting for an IDR picture: at the start, and after anything was lost. */
    private var waiting = true

    /** After a loss, the rest of the unit it hit is of no use either: its packets are passed over. */
    private var skipping = false
    private var skipTime = 0L

    /** The latest parameter sets seen in-band. */
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** The next picture that goes out should carry [sps] and [pps]. True at first: the first IDR picture needs them. */
    private var carry = true

    override fun payload(buf: ByteArray, offset: Int, length: Int, timestamp: Long, marker: Boolean) {
        if (open && timestamp != unitTime) finish()
        if (skipping) {
            if (timestamp == skipTime) {
                if (marker) skipping = false
                return
            }
            skipping = false
        }
        if (!open) begin(timestamp)
        if (length > 0) {
            val ok = when (buf[offset].toInt() and 0x1f) {
                in 1..23 -> nal(buf, offset, length)
                24 -> stapA(buf, offset + 1, offset + length)
                28 -> fuA(buf, offset, length)
                else -> true
            }
            if (!ok) {
                // Larger than any picture may be: not a picture. Dropped, and the stream starts over at an IDR.
                lost()
                return
            }
        }
        if (marker) finish()
    }

    override fun lost() {
        if (open) {
            skipping = true
            skipTime = unitTime
        }
        open = false
        waiting = true
    }

    override fun format(): VideoFormat {
        val size = sdpSps?.let { H264Sps.size(it) } ?: sps?.let { H264Sps.size(it) }
        return VideoFormat.H264(sdpSps, sdpPps, size?.first ?: 0, size?.second ?: 0)
    }

    private fun begin(timestamp: Long) {
        open = true
        unitTime = timestamp
        unit.length = 0
        fragment = -1
        hasIdr = false
        hasSlice = false
        hasSps = false
        hasPps = false
        damaged = false
    }

    private fun startCode(): Boolean = unit.put(0) && unit.put(0) && unit.put(0) && unit.put(1)

    private fun nal(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (!startCode() || !unit.put(buf, offset, length)) return false
        note(buf, offset, length)
        return true
    }

    private fun stapA(buf: ByteArray, from: Int, end: Int): Boolean {
        var i = from
        while (i + 2 <= end) {
            val size = ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
            i += 2
            if (size == 0 || size > end - i) {
                damaged = true
                return true
            }
            if (!nal(buf, i, size)) return false
            i += size
        }
        return true
    }

    private fun fuA(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 2) {
            damaged = true
            return true
        }
        val indicator = buf[offset].toInt() and 0xff
        val fu = buf[offset + 1].toInt() and 0xff
        if (fu and 0x80 != 0) {                                     // the first piece
            if (fragment >= 0) {                                   // ...but the last one never came
                damaged = true
                unit.length = fragment
            }
            fragment = unit.length
            if (!startCode() || !unit.put((indicator and 0xe0) or (fu and 0x1f))) return false
        } else if (fragment < 0) {                                 // a piece of a NAL unit whose start was lost
            damaged = true
            return true
        }
        if (!unit.put(buf, offset + 2, length - 2)) return false
        if (fu and 0x40 != 0) {                                     // the last piece
            note(unit.data, fragment + 4, unit.length - fragment - 4)
            fragment = -1
        }
        return true
    }

    /** Takes note of what a complete NAL unit is. */
    private fun note(buf: ByteArray, offset: Int, length: Int) {
        when (buf[offset].toInt() and 0x1f) {
            5 -> {
                hasIdr = true
                hasSlice = true
            }
            in 1..4 -> hasSlice = true
            7 -> {
                hasSps = true
                sps = buf.copyOfRange(offset, offset + length)
            }
            8 -> {
                hasPps = true
                pps = buf.copyOfRange(offset, offset + length)
            }
        }
    }

    private fun finish() {
        if (!open) return
        open = false
        if (fragment >= 0) damaged = true                          // a NAL unit whose last piece never came
        if (damaged) {
            waiting = true
            return
        }
        if (!hasSlice) {
            // Parameter sets (or other data) without a picture: not a frame, but the next picture needs them.
            if (hasSps || hasPps) carry = true
            return
        }
        if (waiting && !hasIdr) return
        waiting = false
        var data = unit.data
        var length = unit.length
        if (carry) {
            carry = false
            val prefix = (if (!hasSps) sps else null) to (if (!hasPps) pps else null)
            if (prefix.first != null || prefix.second != null) {
                val joined = withParameterSets(prefix.first, prefix.second)
                data = joined
                length = joined.size
            }
        }
        emit(data, length, unitTime, hasIdr)
    }

    /** The unit with [sps] and [pps] in front - after an access unit delimiter, which has to stay first. */
    private fun withParameterSets(sps: ByteArray?, pps: ByteArray?): ByteArray {
        val src = unit.data
        val keep = if (unit.length > 4 && src[4].toInt() and 0x1f == 9) nextStart(5) else 0
        val extra = (sps?.let { it.size + 4 } ?: 0) + (pps?.let { it.size + 4 } ?: 0)
        val out = ByteArray(unit.length + extra)
        System.arraycopy(src, 0, out, 0, keep)
        var at = keep
        for (p in listOf(sps, pps)) {
            if (p == null) continue
            out[at + 3] = 1
            System.arraycopy(p, 0, out, at + 4, p.size)
            at += p.size + 4
        }
        System.arraycopy(src, keep, out, at, unit.length - keep)
        return out
    }

    /** Where the next 00 00 00 01 at or after [from] starts in [unit] (the end of the unit if there is none). */
    private fun nextStart(from: Int): Int {
        val d = unit.data
        var i = from
        while (i + 4 <= unit.length) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) return i
            i++
        }
        return unit.length
    }
}

/**
 * The picture size an H.264 sequence parameter set describes (ITU-T H.264 section 7.3.2.1.1), so the decoder can be
 * set up before the first picture. Anything it cannot make sense of gives null rather than a guess.
 */
internal object H264Sps {
    /** Width and height from [sps] (a NAL unit with its header byte, without a start code), or null. */
    fun size(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 4 || sps[0].toInt() and 0x1f != 7) return null
        return try {
            read(Bits(unescape(sps)))
        } catch (e: IndexOutOfBoundsException) {
            null                                                   // shorter than its own fields say
        }
    }

    /** The NAL unit's payload without its header byte and without emulation prevention bytes (00 00 03). */
    fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size)
        var n = 0
        var zeros = 0
        for (i in 1 until nal.size) {
            val b = nal[i].toInt() and 0xff
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            out[n++] = b.toByte()
        }
        return out.copyOf(n)
    }

    private val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun read(r: Bits): Pair<Int, Int>? {
        val profile = r.u(8)
        r.u(16)                                                    // constraint flags, level
        if (r.ue() > 31) return null                               // seq_parameter_set_id
        var chroma = 1
        var separatePlanes = false
        if (profile in highProfiles) {
            chroma = r.ue()
            if (chroma > 3) return null
            if (chroma == 3) separatePlanes = r.u(1) == 1
            if (r.ue() > 6 || r.ue() > 6) return null              // bit depths
            r.u(1)                                                 // qpprime_y_zero_transform_bypass_flag
            if (r.u(1) == 1) {                                     // seq_scaling_matrix_present_flag
                for (i in 0 until if (chroma != 3) 8 else 12) {
                    if (r.u(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        if (r.ue() > 12) return null                               // log2_max_frame_num_minus4
        when (r.ue()) {                                            // pic_order_cnt_type
            0 -> if (r.ue() > 12) return null
            1 -> {
                r.u(1)
                r.se()
                r.se()
                val cycle = r.ue()
                if (cycle > 255) return null
                repeat(cycle) { r.se() }
            }
            2 -> Unit
            else -> return null
        }
        if (r.ue() > 32) return null                               // max_num_ref_frames
        r.u(1)                                                     // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightUnits = r.ue() + 1
        val frameMbsOnly = r.u(1) == 1
        if (!frameMbsOnly) r.u(1)                                  // mb_adaptive_frame_field_flag
        r.u(1)                                                     // direct_8x8_inference_flag
        var width = widthMbs * 16
        var height = heightUnits * 16 * (if (frameMbsOnly) 1 else 2)
        if (r.u(1) == 1) {                                         // frame_cropping_flag
            val arrayType = if (separatePlanes) 0 else chroma
            val cropX = if (arrayType == 0 || chroma == 3) 1 else 2
            val cropY = (if (arrayType == 0 || chroma != 1) 1 else 2) * (if (frameMbsOnly) 1 else 2)
            val left = r.ue()
            val right = r.ue()
            val top = r.ue()
            val bottom = r.ue()
            if (left > 8192 || right > 8192 || top > 8192 || bottom > 8192) return null
            width -= cropX * (left + right)
            height -= cropY * (top + bottom)
        }
        if (width !in 16..8192 || height !in 16..8192) return null
        return width to height
    }

    private fun skipScalingList(r: Bits, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) {
                next = (last + r.se() + 256) % 256
            }
            last = if (next == 0) last else next
        }
    }

    /** Reads bits and Exp-Golomb codes; reading past the end throws IndexOutOfBoundsException. */
    private class Bits(private val data: ByteArray) {
        private var bit = 0

        fun u(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or one() }
            return v
        }

        private fun one(): Int {
            if (bit >= data.size * 8) throw IndexOutOfBoundsException("past the end of the SPS")
            val v = (data[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1
            bit++
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (one() == 0) {
                if (++zeros > 30) throw IndexOutOfBoundsException("an Exp-Golomb code longer than any SPS field")
            }
            return (1 shl zeros) - 1 + u(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
