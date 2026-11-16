package com.dompka.dashlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * RTP packets back into H.264 pictures (RFC 6184): single NAL units, STAP-A and FU-A, what ends a picture, and what
 * happens after a loss. The packets are made here byte by byte or by FakeRtsp's packetizer; a picture is compared
 * with exactly what was sent.
 */
class RtpTest {
    private class Got(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private val got = ArrayList<Got>()
    private var seq = 65_530
    private val sps = FakeRtsp.sps(640, 360)
    private val pps = FakeRtsp.PPS

    private fun h264(sdpSps: ByteArray? = null, sdpPps: ByteArray? = null) = H264Depacketizer(96, 90_000, sdpSps, sdpPps).also { d ->
        d.out = RtpDepacketizer.Out { data, length, pts, key -> got.add(Got(data.copyOf(length), pts, key)) }
    }

    /** Sends one payload as the next packet (or as [sequence], to make gaps and repeats). */
    private fun send(d: RtpDepacketizer, payload: ByteArray, ts: Long, marker: Boolean, sequence: Int = seq++, pt: Int = 96) {
        val p = FakeRtsp.rtp(pt, sequence and 0xffff, ts and 0xFFFF_FFFFL, 0x1234, marker, payload)
        d.push(p, 0, p.size)
    }

    /** One access unit, packetized the way FakeRtsp does it. */
    private fun unit(d: RtpDepacketizer, ts: Long, vararg nals: ByteArray, mtu: Int = 1400, aggregate: Boolean = true, marker: Boolean = true) {
        val payloads = FakeRtsp.h264Payloads(nals.toList(), mtu, aggregate)
        for ((i, p) in payloads.withIndex()) send(d, p, ts, marker && i == payloads.size - 1)
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        for (n in nals) {
            b.write(byteArrayOf(0, 0, 0, 1))
            b.write(n)
        }
        return b.toByteArray()
    }

    private fun idr(seed: Int, size: Int = 3000) = FakeRtsp.slice(true, size, seed)
    private fun p(seed: Int, size: Int = 500) = FakeRtsp.slice(false, size, seed)

    // ---------------------------------------------------------------- the three packet types

    @Test
    fun fuA_acrossThreePackets_isOneNalUnit() {
        val d = h264()
        val slice = idr(1, 2500)
        val pieces = FakeRtsp.fuA(slice, 1000)
        assertEquals("the test needs exactly three fragments", 3, pieces.size)
        send(d, sps, 0, false)
        send(d, pps, 0, false)
        for ((i, piece) in pieces.withIndex()) send(d, piece, 0, i == 2)
        assertEquals(1, got.size)
        assertArrayEquals(annexB(sps, pps, slice), got[0].data)
        assertTrue(got[0].key)
        // The NAL header is rebuilt from the FU indicator (F and NRI) and the FU header (type).
        assertEquals(0x65, got[0].data[got[0].data.size - slice.size].toInt() and 0xff)
    }

    @Test
    fun stapA_carryingSpsPpsAndIdr_isOneKeyFrame() {
        val d = h264()
        val slice = idr(2, 300)
        val stap = FakeRtsp.stapA(listOf(sps, pps, slice))
        assertEquals(24, stap[0].toInt() and 0x1f)
        send(d, stap, 3000, true)
        assertEquals(1, got.size)
        assertArrayEquals(annexB(sps, pps, slice), got[0].data)
        assertTrue(got[0].key)
    }

    @Test
    fun singleNalUnits_andTheTypesNotUsedForLiveStreams_areIgnored() {
        val d = h264()
        unit(d, 0, sps, pps, idr(3), aggregate = false)
        // STAP-B (25), MTAP16 (26), MTAP24 (27), FU-B (29) and the reserved types belong to the interleaved mode.
        for (type in listOf(25, 26, 27, 29, 30, 31, 0)) send(d, byteArrayOf((0x60 or type).toByte(), 1, 2, 3, 4), 3000, false)
        val after = p(4)
        send(d, after, 3000, true)
        assertEquals(2, got.size)
        assertArrayEquals(annexB(after), got[1].data)
        assertFalse(got[1].key)
    }

    // ---------------------------------------------------------------- what ends a picture

    @Test
    fun missingMarkerBit_theTimestampChangeEndsTheUnit() {
        val d = h264()
        val first = idr(5)
        val second = p(6)
        unit(d, 0, sps, pps, first, marker = false)
        assertTrue("not over yet: the next packet could still belong to it", got.isEmpty())
        unit(d, 3000, second, marker = false)
        assertEquals(1, got.size)
        assertArrayEquals(annexB(sps, pps, first), got[0].data)
        unit(d, 6000, p(7), marker = false)
        assertEquals(2, got.size)
        assertArrayEquals(annexB(second), got[1].data)
    }

    @Test
    fun severalSlicesOfOnePicture_stayTogether() {
        val d = h264()
        val slices = listOf(idr(8, 2000), idr(9, 200), idr(10, 3000))
        unit(d, 0, sps, pps, *slices.toTypedArray())
        assertEquals(1, got.size)
        assertArrayEquals(annexB(sps, pps, *slices.toTypedArray()), got[0].data)
    }

    // ---------------------------------------------------------------- the first picture and parameter sets

    @Test
    fun nonIdrBeforeTheFirstIdr_isDropped() {
        val d = h264(sps, pps)
        unit(d, 0, p(11))
        unit(d, 3000, p(12))
        assertTrue("a P picture without the picture it refers to only shows grey smears", got.isEmpty())
        val key = idr(13)
        unit(d, 6000, key)
        val next = p(14)
        unit(d, 9000, next)
        assertEquals(2, got.size)
        assertArrayEquals(annexB(key), got[0].data)
        assertTrue(got[0].key)
        assertArrayEquals(annexB(next), got[1].data)
        assertFalse(got[1].key)
    }

    @Test
    fun inBandParameterSets_inTheirOwnUnits_goInFrontOfTheFirstIdr() {
        val d = h264()
        // Some cameras send SPS and PPS as units of their own, with the marker bit, before the IDR picture.
        send(d, sps, 0, true)
        send(d, pps, 0, true)
        assertTrue("parameter sets alone are not a picture", got.isEmpty())
        val key = idr(15)
        unit(d, 3000, key)
        assertEquals(1, got.size)
        assertArrayEquals(annexB(sps, pps, key), got[0].data)
        assertTrue(got[0].key)
        // Only that first picture needs them: the decoder has them from then on.
        val later = idr(16)
        unit(d, 6000, later)
        assertArrayEquals(annexB(later), got[1].data)
        // The size comes from the in-band SPS, since the SDP had none.
        val f = d.format() as VideoFormat.H264
        assertEquals(640, f.width)
        assertEquals(360, f.height)
        assertEquals("sps/pps in the format are only the SDP's", null, f.sps)
    }

    @Test
    fun inBandParameterSets_goAfterAnAccessUnitDelimiter() {
        val d = h264()
        send(d, sps, 0, true)
        send(d, pps, 0, true)
        val aud = byteArrayOf(0x09, 0xf0.toByte())
        val key = idr(17)
        unit(d, 3000, aud, key)
        assertArrayEquals("the delimiter has to stay first", annexB(aud, sps, pps, key), got.single().data)
    }

    @Test
    fun parameterSetsFromTheSdp_giveTheSizeBeforeAnyPicture() {
        val d = h264(FakeRtsp.sps(1280, 720), pps)
        val f = d.format() as VideoFormat.H264
        assertEquals(1280, f.width)
        assertEquals(720, f.height)
        assertArrayEquals(FakeRtsp.sps(1280, 720), f.sps)
        assertArrayEquals(pps, f.pps)
    }

    // ---------------------------------------------------------------- losses

    @Test
    fun gapInTheMiddleOfAFuA_dropsEverythingUntilTheNextIdr() {
        val d = h264()
        val k1 = idr(20)
        val p1 = p(21)
        unit(d, 0, sps, pps, k1)
        unit(d, 3000, p1)
        // An IDR in three fragments whose middle one is lost.
        val broken = FakeRtsp.fuA(idr(22, 2500), 1000)
        send(d, broken[0], 6000, false)
        seq++
        send(d, broken[2], 6000, true)
        unit(d, 9000, p(23))
        unit(d, 12000, p(24))
        val k2 = idr(25)
        unit(d, 15000, sps, pps, k2)
        val p2 = p(26)
        unit(d, 18000, p2)
        assertEquals(4, got.size)
        assertArrayEquals(annexB(sps, pps, k1), got[0].data)
        assertArrayEquals(annexB(p1), got[1].data)
        assertArrayEquals("the next picture after the loss is the next IDR", annexB(sps, pps, k2), got[2].data)
        assertArrayEquals(annexB(p2), got[3].data)
    }

    @Test
    fun lostLastPacket_theUnitAndThoseThatFollowAreDropped() {
        val d = h264()
        unit(d, 0, sps, pps, idr(27))
        val pieces = FakeRtsp.fuA(p(28, 2500), 1000)
        send(d, pieces[0], 3000, false)
        send(d, pieces[1], 3000, false)
        seq++                                                    // the last piece, with the marker, is lost
        unit(d, 6000, p(29))
        assertEquals("the damaged picture and the one after it are both of no use", 1, got.size)
    }

    @Test
    fun aFuAWithoutItsStart_orAStapAThatOverruns_isNotPassedOn() {
        // No gap in the sequence numbers: a camera that packs badly. The damaged unit is dropped all the same.
        val d = h264()
        unit(d, 0, sps, pps, idr(30))
        val pieces = FakeRtsp.fuA(p(31, 2500), 1000)
        send(d, pieces[1], 3000, false)
        send(d, pieces[2], 3000, true)
        val stap = FakeRtsp.stapA(listOf(p(32, 50), p(33, 50)))
        stap[1] = 0x7f                                           // the first NAL unit's size now reaches past the end
        send(d, stap, 6000, true)
        val k = idr(34)
        unit(d, 9000, k)
        assertEquals(2, got.size)
        assertArrayEquals(annexB(k), got[1].data)
    }

    @Test
    fun oversizeUnit_isDroppedAndTheNextIdrResyncs() {
        val d = h264()
        unit(d, 0, sps, pps, idr(35))
        // A single NAL unit of 4.5 MB: more than any picture may take.
        val huge = ByteArray(4_500_000).also { Random(1).nextBytes(it) }
        huge[0] = 0x65
        unit(d, 3000, huge, mtu = 60_000)
        unit(d, 6000, p(36))
        val k = idr(37)
        unit(d, 9000, k)
        assertEquals(2, got.size)
        assertArrayEquals(annexB(k), got[1].data)
        assertTrue(got.all { it.data.size <= RtpDepacketizer.MAX_PICTURE })
    }

    @Test
    fun sequenceNumbersWrapAround_withoutCountingAsALoss() {
        val d = h264()
        seq = 65_534
        val k = idr(38, 5000)
        unit(d, 0, sps, pps, k, mtu = 1000)
        assertTrue("the unit crossed the wrap-around", seq > 65_536)
        assertArrayEquals(annexB(sps, pps, k), got.single().data)
    }

    @Test
    fun repeatedAndLatePackets_areIgnored() {
        val d = h264()
        val k = idr(39, 2500)
        val pieces = FakeRtsp.fuA(k, 1000)
        send(d, sps, 0, false, sequence = 100)
        send(d, pps, 0, false, sequence = 101)
        send(d, pieces[0], 0, false, sequence = 102)
        send(d, pieces[0], 0, false, sequence = 102)             // the same packet twice
        send(d, pieces[1], 0, false, sequence = 103)
        send(d, pps, 0, false, sequence = 101)                   // one that comes too late
        send(d, pieces[2], 0, true, sequence = 104)
        assertArrayEquals(annexB(sps, pps, k), got.single().data)
    }

    @Test
    fun aCameraThatStartsCountingAgain_isFollowed() {
        val d = h264()
        unit(d, 0, sps, pps, idr(40))
        // The sequence numbers jump back by a lot and carry on from there: not lateness, a new count.
        seq -= 20_000
        repeat(200) { unit(d, 3000L * (it + 1), if (it % 10 == 0) idr(41 + it) else p(41 + it)) }
        // The first packets after the jump are taken for late ones; then the new count is followed, from an IDR.
        assertTrue("pictures came again after the new count: ${got.size}", got.size > 150)
    }

    @Test
    fun otherPayloadTypes_doNotDisturbTheStream() {
        val d = h264()
        var other = 7
        val k = idr(42, 2500)
        val pieces = FakeRtsp.fuA(k, 1000)
        send(d, sps, 0, false)
        // Audio (97) and RTCP sent to the same port (payload types 72-76 with the marker bit set) in between.
        send(d, byteArrayOf(1, 2, 3), 0, true, sequence = other++, pt = 97)
        send(d, pps, 0, false)
        send(d, byteArrayOf(1, 2, 3), 0, true, sequence = other, pt = 72)
        for ((i, piece) in pieces.withIndex()) send(d, piece, 0, i == pieces.size - 1)
        assertArrayEquals(annexB(sps, pps, k), got.single().data)
    }

    // ---------------------------------------------------------------- time

    @Test
    fun ptsCountsFromTheFirstPicture_acrossTheTimestampWrapAround() {
        val d = h264()
        val start = 0xFFFF_F000L
        unit(d, start, sps, pps, idr(50))
        for (i in 1..3) unit(d, start + 3000L * i, p(50 + i))
        assertEquals(listOf(0L, 33_333L, 66_666L, 100_000L), got.map { it.ptsUs })
    }

    @Test
    fun aJumpOfTheClock_doesNotSendTheTimeAway() {
        val d = h264()
        unit(d, 1000, sps, pps, idr(54))
        unit(d, 4000, p(55))
        unit(d, 4000 + 90_000L * 3600, p(56))                   // an hour later, from one picture to the next
        unit(d, 7000 + 90_000L * 3600, p(57))
        assertEquals(listOf(0L, 33_333L, 33_333L, 66_666L), got.map { it.ptsUs })
    }

    // ---------------------------------------------------------------- RTP headers

    @Test
    fun rtpHeader_csrcsExtensionAndPadding_areSkipped() {
        val payload = byteArrayOf(0x41, 9, 8, 7)
        val b = ByteArrayOutputStream()
        b.write(0x80 or 0x20 or 0x10 or 2)                       // V=2, padding, extension, two CSRCs
        b.write(0x80 or 96)
        b.write(byteArrayOf(0x12, 0x34, 0, 0, 0x0b, 0xb8.toByte(), 1, 2, 3, 4))
        b.write(ByteArray(8))                                    // CSRCs
        b.write(byteArrayOf(0xbe.toByte(), 0xde.toByte(), 0, 2)) // extension header: two words follow
        b.write(ByteArray(8))
        b.write(payload)
        b.write(byteArrayOf(0, 0, 3))                            // three bytes of padding
        val packet = b.toByteArray()
        val h = RtpHeader()
        assertTrue(h.read(packet, 0, packet.size))
        assertEquals(96, h.payloadType)
        assertTrue(h.marker)
        assertEquals(0x1234, h.sequence)
        assertEquals(3000L, h.timestamp)
        assertArrayEquals(payload, packet.copyOfRange(h.payloadOffset, h.payloadOffset + h.payloadLength))

        // Broken in ways that would make a careless reader run off the end.
        val tooMuchPadding = packet.copyOf().also { it[it.size - 1] = 100 }
        val extensionTooLong = packet.copyOf().also { it[12 + 8 + 3] = 50 }
        val version1 = packet.copyOf().also { it[0] = (0x40 or 0x20 or 0x10 or 2).toByte() }
        for (bad in listOf(tooMuchPadding, extensionTooLong, version1, packet.copyOf(11))) {
            assertFalse(h.read(bad, 0, bad.size))
        }
        assertFalse("a length past the buffer", h.read(packet, 4, packet.size))
    }

    // ---------------------------------------------------------------- everything together

    @Test
    fun roundTrip_randomPictures_everyWayOfPacking() {
        val r = Random(7)
        for (mtu in listOf(100, 576, 1400, 9000)) {
            for (aggregate in listOf(true, false)) {
                got.clear()
                val d = h264()
                val sent = ArrayList<ByteArray>()
                for (i in 0 until 40) {
                    val nals = ArrayList<ByteArray>()
                    if (i % 10 == 0) {
                        nals.add(sps)
                        nals.add(pps)
                    }
                    repeat(1 + r.nextInt(3)) { nals.add(FakeRtsp.slice(i % 10 == 0, 2 + r.nextInt(if (r.nextBoolean()) 40 else 6000), r.nextInt())) }
                    sent.add(annexB(*nals.toTypedArray()))
                    unit(d, 3000L * i, *nals.toTypedArray(), mtu = mtu, aggregate = aggregate)
                }
                assertEquals("mtu $mtu aggregate $aggregate", sent.size, got.size)
                for (i in sent.indices) assertArrayEquals("mtu $mtu aggregate $aggregate picture $i", sent[i], got[i].data)
                assertEquals(listOf(true, false, false, false), got.take(4).map { it.key })
            }
        }
    }

    @Test
    fun randomPackets_neverThrow_andNeverPassOnSomethingHuge() {
        // Whatever arrives: a reader must neither crash nor be talked into keeping more than a picture's worth.
        val r = Random(11)
        val d = h264()
        repeat(20_000) {
            val payload = ByteArray(r.nextInt(1500)).also { r.nextBytes(it) }
            if (payload.isNotEmpty() && r.nextInt(3) == 0) payload[0] = (payload[0].toInt() and 0xe0 or listOf(1, 5, 7, 8, 24, 28)[r.nextInt(6)]).toByte()
            send(d, payload, (it / 5) * 3000L, r.nextInt(5) == 0, sequence = if (r.nextInt(50) == 0) r.nextInt() else seq++)
        }
        val raw = ByteArray(2000)
        repeat(5_000) {
            r.nextBytes(raw)
            d.push(raw, 0, r.nextInt(raw.size + 1))
        }
        assertTrue(got.all { it.data.size <= RtpDepacketizer.MAX_PICTURE })
    }
}
