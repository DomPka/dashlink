package com.dompka.dashlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Random

/**
 * Motion JPEG (RFC 2435) back into whole images. The pictures are made by the JVM's own JPEG encoder with known
 * settings; their headers are cut off, the bare scan goes through FakeRtsp's packetizer and the depacketizer, and the
 * rebuilt image must decode to exactly the same pixels as the original: same tables, same Huffman codes, same layout.
 */
class JpegTest {
    private val got = ArrayList<ByteArray>()
    private var seq = 100

    private fun depacketizer() = JpegDepacketizer(26, 90_000).also { d ->
        d.out = RtpDepacketizer.Out { data, length, _, key ->
            assertTrue("every JPEG picture stands on its own", key)
            got.add(data.copyOf(length))
        }
    }

    /** RGB pixels with gradients and noise, so that every table entry matters. */
    private fun picture(w: Int, h: Int, seed: Long): IntArray {
        val r = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = r.nextInt(40)
            (((x * 255 / w + n) and 0xff) shl 16) or (((y * 255 / h + n) and 0xff) shl 8) or ((x + y + n) and 0xff)
        }
    }

    /** What the encoder made, taken apart the way an RFC 2435 sender does it. */
    private class Encoded(val jpeg: ByteArray, val scan: ByteArray, val tables: ByteArray, val ySampling: Int, val dri: Int)

    /**
     * Encodes a [w] x [h] test picture with the JVM's JPEG writer at [quality], luminance sampled [hs] x [vs]
     * (chroma 1x1), with a restart marker every [restart] MCUs when not 0.
     */
    private fun encode(w: Int, h: Int, seed: Long, quality: Float, hs: Int, vs: Int, restart: Int = 0): Encoded {
        val img = Jdk.image(w, h, picture(w, h, seed))
        val writer = Jdk.call(Jdk.call(null, Jdk.imageIO, "getImageWritersByFormatName", "jpeg")!!, "next")!!
        val param = Jdk.call(writer, "getDefaultWriteParam")!!
        Jdk.call(param, "setCompressionMode", 2)                     // ImageWriteParam.MODE_EXPLICIT
        Jdk.call(param, "setCompressionQuality", quality)
        val spec = Jdk.call(null, Class.forName("javax.imageio.ImageTypeSpecifier"), "createFromRenderedImage", img)
        val meta = Jdk.call(writer, "getDefaultImageMetadata", spec, param)!!
        val format = "javax_imageio_jpeg_image_1.0"
        val tree = Jdk.call(meta, "getAsTree", format) as Element
        val sof = tree.getElementsByTagName("sof").item(0) as Element
        val components = sof.getElementsByTagName("componentSpec")
        for (i in 0 until components.length) {
            val c = components.item(i) as Element
            c.setAttribute("HsamplingFactor", if (i == 0) "$hs" else "1")
            c.setAttribute("VsamplingFactor", if (i == 0) "$vs" else "1")
        }
        if (restart > 0) {
            val node = Class.forName("javax.imageio.metadata.IIOMetadataNode").getConstructor(String::class.java)
            val dri = node.newInstance("dri") as Element
            dri.setAttribute("interval", "$restart")
            sof.parentNode.insertBefore(dri, sof)
        }
        Jdk.call(meta, "setFromTree", format, tree)
        val out = ByteArrayOutputStream()
        val ios = Jdk.call(null, Jdk.imageIO, "createImageOutputStream", out as OutputStream)!!
        Jdk.call(writer, "setOutput", ios)
        val image = Class.forName("javax.imageio.IIOImage").constructors.first { it.parameterCount == 3 && it.parameterTypes[0].isInstance(img) }
        Jdk.call(writer, "write", null, image.newInstance(img, null, meta), param)
        Jdk.call(ios, "close")
        Jdk.call(writer, "dispose")
        return split(out.toByteArray())
    }

    /** Cuts a baseline JPEG into its quantization tables (DQT order), sampling, restart interval and scan. */
    private fun split(jpeg: ByteArray): Encoded {
        val tables = ByteArrayOutputStream()
        var sampling = 0
        var dri = 0
        var i = 2
        while (true) {
            assertEquals(0xff, jpeg[i].toInt() and 0xff)
            val marker = jpeg[i + 1].toInt() and 0xff
            val length = ((jpeg[i + 2].toInt() and 0xff) shl 8) or (jpeg[i + 3].toInt() and 0xff)
            val seg = jpeg.copyOfRange(i + 4, i + 2 + length)
            when (marker) {
                0xdb -> {
                    var j = 0
                    while (j < seg.size) {
                        assertEquals("8-bit tables", 0, seg[j].toInt() shr 4)
                        tables.write(seg, j + 1, 64)
                        j += 65
                    }
                }
                0xc0 -> sampling = seg[7].toInt() and 0xff
                0xdd -> dri = ((seg[0].toInt() and 0xff) shl 8) or (seg[1].toInt() and 0xff)
                0xda -> {
                    var end = jpeg.size
                    if (jpeg[end - 2] == 0xff.toByte() && jpeg[end - 1] == 0xd9.toByte()) end -= 2
                    return Encoded(jpeg, jpeg.copyOfRange(i + 2 + length, end), tables.toByteArray(), sampling, dri)
                }
            }
            i += 2 + length
        }
    }

    private fun send(d: RtpDepacketizer, frame: FakeRtsp.JpegFrame, mtu: Int = 1000, skip: Set<Int> = emptySet(), marker: Boolean = true) {
        val payloads = FakeRtsp.jpegPayloads(frame, mtu)
        for ((i, p) in payloads.withIndex()) {
            val packet = FakeRtsp.rtp(26, seq++ and 0xffff, frame.time, 7, marker && i == payloads.size - 1, p)
            if (i !in skip) d.push(packet, 0, packet.size)
        }
    }

    /** Width, height and pixels of a JPEG, as the JVM's decoder reads it. */
    private class Decoded(val width: Int, val height: Int, val pixels: IntArray)

    private fun decode(jpeg: ByteArray): Decoded {
        val img = Jdk.call(null, Jdk.imageIO, "read", ByteArrayInputStream(jpeg) as InputStream)
            ?: throw AssertionError("the JVM's decoder could not read the image")
        val w = Jdk.call(img, "getWidth") as Int
        val h = Jdk.call(img, "getHeight") as Int
        return Decoded(w, h, Jdk.call(img, "getRGB", 0, 0, w, h, null, 0, w) as IntArray)
    }

    private fun assertSamePicture(original: ByteArray, rebuilt: ByteArray, w: Int, h: Int) {
        val img = decode(rebuilt)
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        assertArrayEquals("decodes to exactly the original's pixels", decode(original).pixels, img.pixels)
        assertEquals(0xffd8, ((rebuilt[0].toInt() and 0xff) shl 8) or (rebuilt[1].toInt() and 0xff))
        assertEquals(0xffd9, ((rebuilt[rebuilt.size - 2].toInt() and 0xff) shl 8) or (rebuilt[rebuilt.size - 1].toInt() and 0xff))
    }

    // ---------------------------------------------------------------- the four layouts

    @Test
    fun type1_fourTwoZero_standardTablesFromQ() {
        val e = encode(320, 240, 1, 0.75f, 2, 2)
        assertEquals(0x22, e.ySampling)
        // The encoder's tables are exactly what Q 75 stands for: otherwise this test would prove nothing.
        assertArrayEquals(JpegDepacketizer.quantization(75), e.tables)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 320, 240, 0))
        assertSamePicture(e.jpeg, got.single(), 320, 240)
        val f = d.format() as VideoFormat.Jpeg
        assertEquals(320, f.width)
        assertEquals(240, f.height)
    }

    @Test
    fun type0_fourTwoTwo_standardTablesFromQ() {
        val e = encode(240, 136, 2, 0.5f, 2, 1)
        assertEquals(0x21, e.ySampling)
        assertArrayEquals(JpegDepacketizer.quantization(50), e.tables)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(e.scan, 0, 50, 240, 136, 0), mtu = 400)
        assertSamePicture(e.jpeg, got.single(), 240, 136)
    }

    @Test
    fun restartMarkers_type65_getTheirIntervalBack() {
        val e = encode(320, 240, 3, 0.75f, 2, 2, restart = 4)
        assertEquals("the encoder wrote restart markers", 4, e.dri)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(e.scan, 65, 75, 320, 240, 0, restartInterval = 4))
        assertSamePicture(e.jpeg, got.single(), 320, 240)
    }

    @Test
    fun restartMarkers_type64_fourTwoTwo() {
        val e = encode(160, 96, 4, 0.5f, 2, 1, restart = 3)
        assertEquals(3, e.dri)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(e.scan, 64, 50, 160, 96, 0, restartInterval = 3), mtu = 300)
        assertSamePicture(e.jpeg, got.single(), 160, 96)
    }

    // ---------------------------------------------------------------- tables sent along

    @Test
    fun q255_tablesInTheFirstPacketOnly() {
        // At quality 0.9 the tables are not ones Q 1-99 can stand for: they have to come in-band.
        val e = encode(320, 240, 5, 0.9f, 2, 2)
        val d = depacketizer()
        val frame = FakeRtsp.JpegFrame(e.scan, 1, 255, 320, 240, 0, tables = e.tables)
        assertTrue("several packets, the tables only in the first", FakeRtsp.jpegPayloads(frame, 1000).size > 3)
        send(d, frame)
        assertSamePicture(e.jpeg, got.single(), 320, 240)
    }

    @Test
    fun q128to254_tablesSentOnce_thenOnlyReferredTo() {
        val a = encode(160, 96, 6, 0.9f, 2, 2)
        val b = encode(160, 96, 7, 0.9f, 2, 2)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(a.scan, 1, 200, 160, 96, 0, tables = a.tables))
        send(d, FakeRtsp.JpegFrame(b.scan, 1, 200, 160, 96, 3000))             // length 0: the tables of Q 200
        assertEquals(2, got.size)
        assertSamePicture(b.jpeg, got[1], 160, 96)
        // A Q whose tables were never sent cannot be shown, and Q 255 must always carry its own.
        send(d, FakeRtsp.JpegFrame(b.scan, 1, 201, 160, 96, 6000))
        send(d, FakeRtsp.JpegFrame(b.scan, 1, 255, 160, 96, 9000))
        assertEquals(2, got.size)
    }

    @Test
    fun oneTableForAllThreeComponents() {
        // Some encoders use a single table for luma and chroma; the rebuilt frame header must then point at it.
        val e = encode(160, 96, 8, 0.8f, 2, 2)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 255, 160, 96, 0, tables = e.tables.copyOf(64)))
        assertEquals(160, decode(got.single()).width)
    }

    // ---------------------------------------------------------------- incomplete pictures

    @Test
    fun aLostPacket_dropsThatPictureOnly() {
        val e = encode(320, 240, 9, 0.75f, 2, 2)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 320, 240, 0))
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 320, 240, 3000), skip = setOf(2))
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 320, 240, 6000))
        assertEquals(2, got.size)
        assertSamePicture(e.jpeg, got[1], 320, 240)
    }

    @Test
    fun noMarkerBit_noPicture() {
        val e = encode(160, 96, 10, 0.75f, 2, 2)
        val d = depacketizer()
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 160, 96, 0), marker = false)
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 160, 96, 3000), marker = false)
        assertTrue("without its last packet a picture cannot be told complete", got.isEmpty())
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 160, 96, 6000))
        assertEquals(1, got.size)
    }

    @Test
    fun unknownTypesAndSizes_areNotShown() {
        val e = encode(160, 96, 11, 0.75f, 2, 2)
        val d = depacketizer()
        for (type in listOf(2, 63, 66, 127, 128, 255)) send(d, FakeRtsp.JpegFrame(e.scan, type, 75, 160, 96, 0))
        send(d, FakeRtsp.JpegFrame(e.scan, 1, 75, 0, 96, 3000))
        assertTrue(got.isEmpty())
    }

    @Test
    fun hostileHeaders_neverThrow() {
        val r = Random(3)
        val d = depacketizer()
        repeat(20_000) {
            val p = ByteArray(r.nextInt(1200)).also { r.nextBytes(it) }
            if (p.size > 8 && r.nextBoolean()) {
                p[1] = 0
                p[2] = 0
                p[3] = 0                                         // a first packet, so the table header is read
                p[5] = (128 + r.nextInt(128)).toByte()
            }
            val packet = FakeRtsp.rtp(26, seq++ and 0xffff, it / 4 * 3000L, 7, r.nextInt(4) == 0, p)
            d.push(packet, 0, packet.size)
        }
        assertTrue(got.all { it.size <= RtpDepacketizer.MAX_PICTURE + 4096 })
    }

    // ---------------------------------------------------------------- the constants are JPEG's own

    @Test
    fun tablesAreThoseOfTheJpegStandard() {
        val q = Class.forName("javax.imageio.plugins.jpeg.JPEGQTable")
        assertArrayEquals(Jdk.call(q.getField("K1Luminance").get(null)!!, "getTable") as IntArray, JpegDepacketizer.LUMA)
        assertArrayEquals(Jdk.call(q.getField("K2Chrominance").get(null)!!, "getTable") as IntArray, JpegDepacketizer.CHROMA)
        val huffman = Class.forName("javax.imageio.plugins.jpeg.JPEGHuffmanTable")
        for ((name, ours) in listOf(
            "StdDCLuminance" to (JpegDepacketizer.DC_LUMA_BITS to JpegDepacketizer.DC_VALUES),
            "StdACLuminance" to (JpegDepacketizer.AC_LUMA_BITS to JpegDepacketizer.AC_LUMA_VALUES),
            "StdDCChrominance" to (JpegDepacketizer.DC_CHROMA_BITS to JpegDepacketizer.DC_VALUES),
            "StdACChrominance" to (JpegDepacketizer.AC_CHROMA_BITS to JpegDepacketizer.AC_CHROMA_VALUES),
        )) {
            val table = huffman.getField(name).get(null)!!
            assertArrayEquals(name, (Jdk.call(table, "getLengths") as ShortArray).map { it.toInt() }.toIntArray(), ours.first)
            assertArrayEquals(name, (Jdk.call(table, "getValues") as ShortArray).map { it.toInt() }.toIntArray(), ours.second)
        }
        // Q 50 is the standard table itself, in zig-zag order; Q 1 and Q 99 are the two ends of the scale.
        val q50 = JpegDepacketizer.quantization(50)
        assertEquals(16, q50[0].toInt())
        assertEquals(11, q50[1].toInt())
        assertEquals("zig-zag order: the second row's first entry comes third", 12, q50[2].toInt())
        assertTrue("clamped to 255", JpegDepacketizer.quantization(1).all { (it.toInt() and 0xff) == 255 })
        assertTrue(JpegDepacketizer.quantization(99).all { it.toInt() in 1..2 })
        assertArrayEquals("Q outside 1-99 is clamped, as MakeTables does", JpegDepacketizer.quantization(1), JpegDepacketizer.quantization(0))
    }

    /**
     * The JVM's own image classes (javax.imageio, java.awt.image), reached by reflection: unit tests are compiled
     * against Android's classes, which do not have them, but the JVM that runs the tests does. Methods are looked up
     * on the public java/javax types, because the encoder's own classes are in packages the JVM keeps closed.
     */
    private object Jdk {
        val imageIO: Class<*> = Class.forName("javax.imageio.ImageIO")

        /** A TYPE_INT_RGB BufferedImage holding [rgb]. */
        fun image(w: Int, h: Int, rgb: IntArray): Any {
            val type = Class.forName("java.awt.image.BufferedImage")
            val img = type.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(w, h, 1)
            call(img, "setRGB", 0, 0, w, h, rgb, 0, w)
            return img
        }

        /** Calls the public method [name] taking [args] on [target], as one of its public types declares it. */
        fun call(target: Any, name: String, vararg args: Any?): Any? {
            val types = ArrayList<Class<*>>()
            var t: Class<*>? = target.javaClass
            while (t != null) {
                types.add(t)
                types.addAll(t.interfaces)
                t = t.superclass
            }
            for (type in types) {
                if (!Modifier.isPublic(type.modifiers) || !(type.name.startsWith("java.") || type.name.startsWith("javax."))) continue
                find(type, name, args)?.let { return it.invoke(target, *args) }
            }
            throw NoSuchMethodException("${target.javaClass.name}.$name")
        }

        /** Calls the static method [name] of [type]. */
        fun call(target: Any?, type: Class<*>, name: String, vararg args: Any?): Any? =
            (find(type, name, args) ?: throw NoSuchMethodException("${type.name}.$name")).invoke(target, *args)

        private fun find(type: Class<*>, name: String, args: Array<out Any?>): Method? = type.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.withIndex().all { (i, p) -> if (args[i] == null) !p.isPrimitive else boxed(p).isInstance(args[i]) }
        }

        private fun boxed(t: Class<*>): Class<*> = when (t) {
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            else -> t
        }
    }
}
