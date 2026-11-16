package com.dompka.dashlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.Random

/**
 * What the live view reads before any picture: the SDP, the stream paths it may ask for, the order it tries them in,
 * which URLs a request may go to, and the picture size in an H.264 SPS.
 */
class SdpTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val sps = FakeRtsp.sps(1280, 720)
    private val pps = FakeRtsp.PPS
    private val b64 = Base64.getEncoder()

    // ---------------------------------------------------------------- SDP

    private fun sdp(vararg media: String) = (listOf("v=0", "o=- 1 1 IN IP4 192.168.1.254", "s=live", "t=0 0", "a=control:*") + media).joinToString("\r\n") + "\r\n"

    @Test
    fun h264_withSpropParameterSets() {
        val s = Sdp.parse(sdp(
            "m=video 0 RTP/AVP 96",
            "a=rtpmap:96 H264/90000",
            "a=fmtp:96 packetization-mode=1;profile-level-id=42C01F;sprop-parameter-sets=${b64.encodeToString(sps)},${b64.encodeToString(pps)}",
            "a=control:trackID=1",
        ))
        assertEquals("*", s.sessionControl)
        val v = s.video()!!
        assertEquals(Sdp.Codec.H264, v.codec)
        assertEquals(96, v.payloadType)
        assertEquals(90_000, v.clockRate)
        assertEquals("trackID=1", v.media.control)
        assertArrayEquals(sps, v.sps)
        assertArrayEquals(pps, v.pps)
    }

    @Test
    fun h264_withoutSprop_parameterSetsAreLeftToTheStream() {
        val v = Sdp.parse(sdp("m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1", "a=control:trackID=1")).video()!!
        assertNull(v.sps)
        assertNull(v.pps)
        val noFmtp = Sdp.parse(sdp("m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000")).video()!!
        assertNull(noFmtp.sps)
        assertNull("no control at all: the stream's own URL is used", noFmtp.media.control)
    }

    @Test
    fun h264_spropInAnyOrder_unpaddedOrBroken() {
        val unpadded = b64.encodeToString(pps).trimEnd('=') + "," + b64.encodeToString(sps).trimEnd('=')
        val v = Sdp.parse(sdp("m=video 0 RTP/AVP 97", "a=rtpmap:97 h264/90000", "a=fmtp:97 sprop-parameter-sets=$unpadded; packetization-mode=1")).video()!!
        assertEquals("the encoding name is not case-sensitive", Sdp.Codec.H264, v.codec)
        assertArrayEquals("told apart by NAL type, not by order", sps, v.sps)
        assertArrayEquals(pps, v.pps)
        val broken = Sdp.parse(sdp("m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=fmtp:96 sprop-parameter-sets=%%%,!!")).video()!!
        assertNull(broken.sps)
        assertNull(broken.pps)
    }

    @Test
    fun jpeg_staticPayloadType26_orNamed() {
        val static = Sdp.parse(sdp("m=video 0 RTP/AVP 26", "a=control:track1")).video()!!
        assertEquals(Sdp.Codec.JPEG, static.codec)
        assertEquals(26, static.payloadType)
        assertEquals(90_000, static.clockRate)
        val named = Sdp.parse(sdp("m=video 0 RTP/AVP 98", "a=rtpmap:98 JPEG/90000")).video()!!
        assertEquals(Sdp.Codec.JPEG, named.codec)
        assertEquals(98, named.payloadType)
    }

    @Test
    fun audioFirst_theVideoIsFound_andItsControlIsItsOwn() {
        val s = Sdp.parse(sdp(
            "m=audio 0 RTP/AVP 97",
            "a=rtpmap:97 MPEG4-GENERIC/16000/1",
            "a=control:trackID=2",
            "m=video 0 RTP/AVP 96",
            "a=rtpmap:96 H264/90000",
            "a=control:trackID=1",
        ))
        assertEquals(2, s.media.size)
        assertEquals("trackID=2", s.media[0].control)
        assertEquals("trackID=1", s.video()!!.media.control)
    }

    @Test
    fun onlyVideoThisAppCanShow_isPicked() {
        assertNull(Sdp.parse(sdp("m=video 0 RTP/AVP 96", "a=rtpmap:96 H265/90000")).video())
        assertNull(Sdp.parse(sdp("m=audio 0 RTP/AVP 0")).video())
        assertNull(Sdp.parse("").video())
        assertNull(Sdp.parse("garbage\n\n=\nm=\na=rtpmap:x\n").video())
        // Two formats in one m= line: the first one that can be shown.
        val v = Sdp.parse(sdp("m=video 0 RTP/AVP 96 97", "a=rtpmap:96 H265/90000", "a=rtpmap:97 H264/90000")).video()!!
        assertEquals(97, v.payloadType)
        // Bare line feeds and stray spaces.
        val lf = Sdp.parse("v=0\na=control:rtsp://h/x\nm=video 0  RTP/AVP  96 \na=rtpmap:96  H264/90000\n").video()!!
        assertEquals(96, lf.payloadType)
    }

    // ---------------------------------------------------------------- URLs

    @Test
    fun controlUrls_resolveAgainstTheirBase() {
        val url = "rtsp://192.168.1.254/liveRTSP/av4"
        assertEquals("$url/trackID=1", Rtsp.resolve(url, "trackID=1"))
        assertEquals("$url/trackID=1", Rtsp.resolve("$url/", "trackID=1"))
        assertEquals(url, Rtsp.resolve(url, "*"))
        assertEquals(url, Rtsp.resolve(url, null))
        assertEquals(url, Rtsp.resolve(url, " "))
        assertEquals("rtsp://192.168.1.254:554/liveRTSP/av4/track1", Rtsp.resolve(url, "rtsp://192.168.1.254:554/liveRTSP/av4/track1"))
        assertEquals("rtsp://192.168.1.254/other", Rtsp.resolve(url, "/other"))
        assertEquals("rtsp://[fe80::1]/liveRTSP/av2", Rtsp.url("fe80::1", "/liveRTSP/av2"))
    }

    @Test
    fun requests_onlyTheReadMethods_onlyToThisStream() {
        val host = "192.168.1.254"
        val stream = "/liveRTSP/av4"
        for (ok in listOf(
            "rtsp://192.168.1.254/liveRTSP/av4",
            "rtsp://192.168.1.254/liveRTSP/av4/",
            "rtsp://192.168.1.254:554/liveRTSP/av4/trackID=1",
            "RTSP://192.168.1.254/liveRTSP/av4/track1",
            "rtsp://192.168.1.254/liveRTSP/av4/video/stream_0.v",
        )) {
            for (method in Rtsp.METHODS) Rtsp.requireRequest(method, ok, host, stream)
        }
        for (bad in listOf(
            "rtsp://192.168.1.253/liveRTSP/av4/trackID=1",               // another host
            "rtsp://192.168.1.254.evil.example/liveRTSP/av4",
            "rtsp://evil.example/liveRTSP/av4",
            "rtsp://user@192.168.1.254/liveRTSP/av4",
            "rtsp://192.168.1.254:8554/liveRTSP/av4",                    // another port
            "rtsp://192.168.1.254:/liveRTSP/av4",
            "rtsp://192.168.1.254/liveRTSP/av2/trackID=1",               // another stream
            "rtsp://192.168.1.254/liveRTSP/av40",
            "rtsp://192.168.1.254/liveRTSP/av4x/trackID=1",
            "rtsp://192.168.1.254/liveRTSP/av4/../av2",
            "rtsp://192.168.1.254/liveRTSP/av4/./trackID=1",
            "rtsp://192.168.1.254/liveRTSP/av4//trackID=1",
            "rtsp://192.168.1.254/liveRTSP/av4/%2e%2e/av2",
            "rtsp://192.168.1.254/liveRTSP/av4/trackID=1?action=del",
            "rtsp://192.168.1.254/liveRTSP/av4;action=del",
            "rtsp://192.168.1.254/liveRTSP/av4/trackID=1#x",
            "rtsp://192.168.1.254/liveRTSP/av4/track 1",
            "rtsp://192.168.1.254/liveRTSP/av4/track\r\nSET_PARAMETER",
            "rtsp://192.168.1.254/liveRTSP/av4/träck",
            "rtsp://192.168.1.254/cgi-bin/Config.cgi?action=set",
            "rtsp://192.168.1.254",
            "rtsps://192.168.1.254/liveRTSP/av4",
            "http://192.168.1.254/liveRTSP/av4",
            "/liveRTSP/av4",
            "trackID=1",
            "",
        )) {
            try {
                Rtsp.requireRequest("SETUP", bad, host, stream)
                fail("must be refused: $bad")
            } catch (e: Cam.RefusedException) {
                assertTrue(e.message!!.startsWith("refused"))
            }
        }
        for (method in listOf("SET_PARAMETER", "ANNOUNCE", "RECORD", "REDIRECT", "PAUSE", "GET", "options", "PLAY ", "")) {
            try {
                Rtsp.requireRequest(method, "rtsp://192.168.1.254/liveRTSP/av4", host, stream)
                fail("must be refused: $method")
            } catch (e: Cam.RefusedException) {
                // expected
            }
        }
        // A stream that is not one of the camera's cannot be asked for under any URL.
        try {
            Rtsp.requireRequest("OPTIONS", "rtsp://192.168.1.254/cgi-bin/x", host, "/cgi-bin/x")
            fail("must be refused")
        } catch (e: Cam.RefusedException) {
            // expected
        }
    }

    @Test
    fun requireStreamPath_acceptsExactlyTheStreamPaths() {
        for (i in 1..9) {
            Live.requireStreamPath("/liveRTSP/av$i")
            Live.requireStreamPath("/liveRTSP/v$i")
        }
        for (bad in listOf(
            "/liveRTSP/av0", "/liveRTSP/v0", "/liveRTSP/av10", "/liveRTSP/avx", "/liveRTSP/av", "/liveRTSP/v",
            "/liveRTSP/av4/", "/liveRTSP/av4/trackID=1", "/liveRTSP/AV4", "/LIVERTSP/av4", "/liveRTSP/a4",
            "liveRTSP/av4", "//liveRTSP/av4", "/liveRTSP//av4", "/liveRTSP/../cgi-bin", "/liveRTSP/av4?x", "/liveRTSP/v1 ",
            "/cgi-bin/Config.cgi", "", "/",
        )) {
            try {
                Live.requireStreamPath(bad)
                fail("must be refused: $bad")
            } catch (e: Cam.RefusedException) {
                // expected
            }
        }
    }

    // ---------------------------------------------------------------- which streams, in which order

    @Test
    fun candidates_theCamerasOwnDefaultFirst_thenTheKnownOrder() {
        val known = listOf("av4", "av2", "v1", "v2", "v3", "av1", "av3").map { "/liveRTSP/$it" }
        assertEquals(known, Live.candidates(emptyMap()))
        assertEquals(listOf("/liveRTSP/av2", "/liveRTSP/av4", "/liveRTSP/v1", "/liveRTSP/v2", "/liveRTSP/v3", "/liveRTSP/av1", "/liveRTSP/av3"), Live.candidates(mapOf("Camera.Preview.RTSP.av" to "2")))
        assertEquals("no duplicates", known, Live.candidates(mapOf("Camera.Preview.RTSP.av" to "4")))
        assertEquals("/liveRTSP/av7", Live.candidates(mapOf("Camera.Preview.RTSP.av" to " 7 ")).first())
        assertEquals(8, Live.candidates(mapOf("camera.preview.rtsp.AV" to "1", "Camera.Preview.RTSP.av" to "9")).size)
        assertEquals("/liveRTSP/av1", Live.candidates(mapOf("camera.preview.rtsp.AV" to "1")).first())
        for (bad in listOf("0", "10", "-1", "x", "", "4; action=set", "av4")) {
            assertEquals(bad, known, Live.candidates(mapOf("Camera.Preview.RTSP.av" to bad)))
        }
        for (c in Live.candidates(mapOf("Camera.Preview.RTSP.av" to "3"))) Live.requireStreamPath(c)
    }

    // ---------------------------------------------------------------- the size in an SPS

    @Test
    fun sps_sizesAsX264WritesThem() {
        // Parameter sets taken from x264's output for these sizes and profiles (with emulation prevention bytes).
        val vectors = mapOf(
            "6742c01fd9005005bb0110000003001000000303c0f1832480" to (1280 to 720),                 // Baseline
            "674d401eeca05017fcb80880000003008000001e078b16cb" to (640 to 360),                    // Main, cropped
            "67640028acd940780227e5c044000003000400000300f03c60c658" to (1920 to 1080),            // High, cropped
            "67640028acd94078044fde0220000003002000000783e2c5b2c0" to (1920 to 1080),              // High, interlaced
            "677a001fbcd940d43db0110000030001000003003c0f183196" to (848 to 480),                  // High 4:2:2
            "67f40028919b280f0044fc4e0220000003002000000781e30632c0" to (1920 to 1080),            // High 4:4:4
        )
        for ((h, size) in vectors) assertEquals(h, size, H264Sps.size(hex(h)))
    }

    @Test
    fun sps_handMade_scalingListsMonochromeAndPocType1() {
        // Made field by field for the branches x264 does not use; an independent H.264 parser reads them the same way.
        assertEquals(1920 to 1080, H264Sps.size(hex("67640028ad846924924924924123232323232323232323232323232323232323232323232323232323232323233b280f0044fca8")))
        assertEquals(1000 to 700, H264Sps.size(hex("676e001e2728531050f4c0fc16789950")))
    }

    @Test
    fun sps_madeToMeasure_anySizeComesBack() {
        for ((w, h) in listOf(640 to 360, 1280 to 720, 1920 to 1080, 848 to 480, 176 to 144, 2560 to 1440, 3840 to 2160, 16 to 16, 1922 to 1082)) {
            assertEquals(w to h, H264Sps.size(FakeRtsp.sps(w, h)))
        }
    }

    @Test
    fun sps_emulationPreventionBytes_areTakenOut() {
        assertArrayEquals(hex("000001000002000000"), H264Sps.unescape(hex("670000030100000302000003000000")).copyOf(9))
        assertArrayEquals("only after two zeros", hex("0003000300"), H264Sps.unescape(hex("670003000300")))
    }

    @Test
    fun sps_thatMakesNoSense_givesNoSize() {
        assertNull("not an SPS", H264Sps.size(pps))
        assertNull(H264Sps.size(ByteArray(0)))
        assertNull(H264Sps.size(hex("67")))
        val good = hex("67640028acd940780227e5c044000003000400000300f03c60c658")
        // Its size fields end with the 88th bit: anything shorter than 11 bytes is missing some of them.
        for (n in 1..10) assertNull("cut short at $n bytes", H264Sps.size(good.copyOf(n)))
        assertEquals(1920 to 1080, H264Sps.size(good.copyOf(11)))
        assertNull("all zeros: an Exp-Golomb code that never ends", H264Sps.size(byteArrayOf(0x67, 0x42, 0, 0x1f) + ByteArray(40)))
        val r = Random(5)
        repeat(20_000) {
            val b = ByteArray(1 + r.nextInt(40)).also { r.nextBytes(it) }
            b[0] = 0x67
            val size = H264Sps.size(b)
            if (size != null) assertTrue("$size", size.first in 16..8192 && size.second in 16..8192)
        }
        assertFalse(Sdp.parameterSets("sprop-parameter-sets=").let { it.first != null || it.second != null })
    }
}
