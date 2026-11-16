package com.dompka.dashlink

/**
 * Motion JPEG per RFC 2435. The camera sends only the compressed scan of each picture with a small header (type,
 * Q, size in 8-pixel units); the JPEG headers around it - quantization tables, frame header, Huffman tables, scan
 * header - are rebuilt here from what the RFC says they are, so that every picture goes out as a complete JFIF image
 * (FF D8 ... FF D9) that any JPEG decoder reads.
 *
 * Understood: types 0 (4:2:2) and 1 (4:2:0), and 64 and 65 (the same with restart markers). Q 1-99 stands for the
 * standard tables scaled the way the RFC's MakeTables does it; Q 128-255 means the tables come in-band, in the first
 * packet of the picture, and for Q 128-254 they may be sent once and left out of later pictures. A picture is
 * complete with the packet that has the marker bit, provided every byte before it arrived; anything else is dropped.
 */
internal class JpegDepacketizer(payloadType: Int, clockRate: Int) : RtpDepacketizer(payloadType, clockRate) {
    private val scan = PictureBuffer(128 * 1024, MAX_PICTURE)

    /** Room for the headers around the largest scan. */
    private val image = PictureBuffer(128 * 1024, MAX_PICTURE + 4096)
    private var open = false
    private var frameTime = 0L
    private var type = 0
    private var q = 0
    private var width = 0
    private var height = 0
    private var restartInterval = 0
    private var tables: ByteArray? = null
    private var precision = 0

    /** In-band tables for Q 128-254, which a camera may send once and then only refer to. */
    private val known = arrayOfNulls<ByteArray>(127)
    private val knownPrecision = IntArray(127)

    /** Tables for Q 1-99, kept for the Q they were last made for. */
    private var madeFor = -1
    private var made = ByteArray(0)

    /** The size of the latest complete picture. */
    private var lastWidth = 0
    private var lastHeight = 0

    override fun format(): VideoFormat = VideoFormat.Jpeg(lastWidth, lastHeight)

    override fun lost() {
        open = false
    }

    override fun payload(buf: ByteArray, offset: Int, length: Int, timestamp: Long, marker: Boolean) {
        if (open && timestamp != frameTime) open = false           // the last packet of that picture never came
        val end = offset + length
        if (length < 8) {
            open = false
            return
        }
        val at = (u8(buf, offset + 1) shl 16) or (u8(buf, offset + 2) shl 8) or u8(buf, offset + 3)
        val t = u8(buf, offset + 4)
        val qq = u8(buf, offset + 5)
        val w = u8(buf, offset + 6) * 8
        val h = u8(buf, offset + 7) * 8
        var p = offset + 8
        var interval = 0
        if (t in 64..127) {                                         // a restart marker header follows
            if (p + 4 > end) {
                open = false
                return
            }
            interval = u16(buf, p)
            p += 4
        }
        if (at == 0) {
            var qt: ByteArray? = null
            var prec = 0
            if (qq >= 128) {                                        // a quantization table header follows
                if (p + 4 > end) {
                    open = false
                    return
                }
                prec = u8(buf, p + 1)
                val size = u16(buf, p + 2)
                p += 4
                if (size > end - p) {
                    open = false
                    return
                }
                if (size > 0) {
                    qt = buf.copyOfRange(p, p + size)
                    p += size
                    if (qq < 255) {
                        known[qq - 128] = qt
                        knownPrecision[qq - 128] = prec
                    }
                } else if (qq < 255) {
                    qt = known[qq - 128]
                    prec = knownPrecision[qq - 128]
                }
                if (qt == null) {                                   // tables that were never sent
                    open = false
                    return
                }
            }
            open = true
            frameTime = timestamp
            type = t
            q = qq
            width = w
            height = h
            restartInterval = interval
            tables = qt
            precision = prec
            scan.length = 0
        } else if (!open || at != scan.length || t != type || qq != q || w != width || h != height) {
            open = false                                            // a piece is missing, or it belongs elsewhere
            return
        }
        if (!scan.put(buf, p, end - p)) {
            open = false
            return
        }
        if (marker) {
            open = false
            complete()
        }
    }

    private fun complete() {
        val sampling = type and 0x3f
        if (type >= 128 || sampling > 1 || width == 0 || height == 0) return
        val qt = tables ?: standard(q)
        val count = tableCount(qt.size, if (tables == null) 0 else precision)
        if (count == 0) return
        build(qt, count, if (tables == null) 0 else precision, sampling)
        lastWidth = width
        lastHeight = height
        emit(image.data, image.length, frameTime, true)
    }

    private fun standard(q: Int): ByteArray {
        if (q != madeFor) {
            made = quantization(q)
            madeFor = q
        }
        return made
    }

    /** How many tables [length] bytes hold when [precision] says which of them are 16-bit; 0 if that does not add up. */
    private fun tableCount(length: Int, precision: Int): Int {
        var used = 0
        var n = 0
        while (used < length && n < 4) {
            used += if (precision and (1 shl n) != 0) 128 else 64
            n++
        }
        return if (used == length) n else 0
    }

    /** The picture as a JFIF image in [image]. Everything fits: [image] has room for the largest scan plus headers. */
    private fun build(qt: ByteArray, count: Int, precision: Int, sampling: Int) {
        val o = image
        o.length = 0
        o.put(START, 0, START.size)
        o.put(0xff)
        o.put(0xdb)
        put16(o, 2 + count + qt.size)
        var from = 0
        for (i in 0 until count) {
            val wide = precision and (1 shl i) != 0
            o.put((if (wide) 0x10 else 0) or i)
            val size = if (wide) 128 else 64
            o.put(qt, from, size)
            from += size
        }
        // Baseline frame header: Y sampled 2x1 (4:2:2) or 2x2 (4:2:0), Cb and Cr 1x1 with the second table if there is one.
        o.put(0xff)
        o.put(0xc0)
        put16(o, 17)
        o.put(8)
        put16(o, height)
        put16(o, width)
        o.put(3)
        val chromaTable = if (count > 1) 1 else 0
        for (component in 1..3) {
            o.put(component)
            o.put(if (component > 1) 0x11 else if (sampling == 0) 0x21 else 0x22)
            o.put(if (component > 1) chromaTable else 0)
        }
        if (type >= 64 && restartInterval > 0) {
            o.put(0xff)
            o.put(0xdd)
            put16(o, 4)
            put16(o, restartInterval)
        }
        o.put(HUFFMAN, 0, HUFFMAN.size)
        o.put(SCAN, 0, SCAN.size)
        o.put(scan.data, 0, scan.length)
        val n = scan.length
        if (n < 2 || scan.data[n - 2] != 0xff.toByte() || scan.data[n - 1] != 0xd9.toByte()) {
            o.put(0xff)
            o.put(0xd9)
        }
    }

    private fun put16(o: PictureBuffer, v: Int) {
        o.put(v ushr 8)
        o.put(v and 0xff)
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff

    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    companion object {
        /**
         * The two tables RFC 2435 means by [q] (1-99, anything else clamped): the standard luminance and chrominance
         * tables of JPEG Annex K scaled as the RFC's MakeTables does it, in the zig-zag order a DQT segment needs.
         */
        fun quantization(q: Int): ByteArray {
            val factor = q.coerceIn(1, 99)
            val scale = if (factor < 50) 5000 / factor else 200 - factor * 2
            val out = ByteArray(128)
            for (i in 0 until 64) {
                out[i] = ((LUMA[ZIGZAG[i]] * scale + 50) / 100).coerceIn(1, 255).toByte()
                out[i + 64] = ((CHROMA[ZIGZAG[i]] * scale + 50) / 100).coerceIn(1, 255).toByte()
            }
            return out
        }

        /** JPEG Annex K, table K.1, in natural (row by row) order. */
        internal val LUMA = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99,
        )

        /** JPEG Annex K, table K.2, in natural order. */
        internal val CHROMA = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99,
            18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99,
            47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
        )

        /** For each position in zig-zag order, the position in natural order. */
        private val ZIGZAG = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10,
            17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34,
            27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36,
            29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46,
            53, 60, 61, 54, 47, 55, 62, 63,
        )

        /** JPEG Annex K.3: the number of codes of each length 1..16, then the values, for the four standard tables. */
        internal val DC_LUMA_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        internal val DC_CHROMA_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        internal val DC_VALUES = IntArray(12) { it }
        internal val AC_LUMA_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        internal val AC_LUMA_VALUES = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa,
        )
        internal val AC_CHROMA_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        internal val AC_CHROMA_VALUES = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa,
        )

        /** SOI and a plain JFIF APP0 segment (version 1.1, no density, no thumbnail). */
        private val START = bytes(
            0xff, 0xd8,
            0xff, 0xe0, 0x00, 0x10, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0, 0x01, 0x01, 0, 0, 1, 0, 1, 0, 0,
        )

        /** One DHT segment with the four standard tables: DC and AC for luminance (0) and chrominance (1). */
        private val HUFFMAN: ByteArray = run {
            val parts = listOf(
                Triple(0x00, DC_LUMA_BITS, DC_VALUES),
                Triple(0x10, AC_LUMA_BITS, AC_LUMA_VALUES),
                Triple(0x01, DC_CHROMA_BITS, DC_VALUES),
                Triple(0x11, AC_CHROMA_BITS, AC_CHROMA_VALUES),
            )
            val length = 2 + parts.sumOf { 17 + it.third.size }
            val out = ArrayList<Int>()
            out += listOf(0xff, 0xc4, length ushr 8, length and 0xff)
            for ((id, bits, values) in parts) {
                out += id
                out += bits.toList()
                out += values.toList()
            }
            bytes(*out.toIntArray())
        }

        /** Start of scan: three components, Y with tables 0/0, Cb and Cr with 1/1, the whole spectrum. */
        private val SCAN = bytes(0xff, 0xda, 0x00, 0x0c, 3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0)

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }
}
