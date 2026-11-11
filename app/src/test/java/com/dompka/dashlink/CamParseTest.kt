package com.dompka.dashlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two text formats the camera answers in: the XML file list and "key=value" settings. */
class CamParseTest {

    private val normal = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <DCIM>
        <file>
        <name>/SD/Normal/F/FILE240101-120000F.MP4</name>
        <format size="2560x1440" time="60">mp4</format>
        <size>104857600</size>
        <attr>RW</attr>
        <time>2024-01-01 12:00:00</time>
        </file>
        <file>
        <name>/SD/Normal/F/FILE240101-120100F.MP4</name>
        <format size="2560x1440" time="59">mp4</format>
        <size>99999999</size>
        <attr>RO</attr>
        <time>2024-01-01 12:01:00</time>
        </file>
        <amount>2</amount>
        </DCIM>
    """.trimIndent()

    // ---------------------------------------------------------------- parseList

    @Test
    fun list_normalXml() {
        val clips = Cam.parseList(normal)
        assertEquals(2, clips.size)
        assertEquals("/SD/Normal/F/FILE240101-120000F.MP4", clips[0].path)
        assertEquals("FILE240101-120000F.MP4", clips[0].name)
        assertEquals(104857600L, clips[0].size)
        assertEquals("2024-01-01 12:00:00", clips[0].time)
        assertEquals("2560x1440", clips[0].res)
        assertEquals("60", clips[0].secs)
        assertEquals("/SD/Normal/F/FILE240101-120100F.MP4", clips[1].path)
        assertEquals(99999999L, clips[1].size)
        assertEquals("59", clips[1].secs)
    }

    @Test
    fun list_windowsLineEndingsAndIndentation() {
        val clips = Cam.parseList(normal.replace("\n", "\r\n   "))
        assertEquals(2, clips.size)
        assertEquals("/SD/Normal/F/FILE240101-120000F.MP4", clips[0].path)
        assertEquals("2024-01-01 12:00:00", clips[0].time)
    }

    @Test
    fun list_empty() {
        assertTrue(Cam.parseList("").isEmpty())
        assertTrue(Cam.parseList("<?xml version=\"1.0\" ?>\n<DCIM>\n<amount>0</amount>\n</DCIM>\n").isEmpty())
        assertTrue(Cam.parseList("0\nOK\n").isEmpty())
        assertTrue(Cam.parseList("<DCIM></DCIM>").isEmpty())
    }

    @Test
    fun list_malformedXml_neverThrows_andKeepsWhatIsWhole() {
        val good = "<file><name>/SD/Normal/F/GOOD.MP4</name><size>5</size><time>t</time></file>"
        val damaged = listOf(
            "<file>",                                               // never closed
            "<file><name>/SD/a.MP4",                                // cut off inside a tag
            "<file><name>/SD/a.MP4</name>",                         // no </file>
            "<file></file>",                                        // nothing inside
            "<file><name></name></file>",                           // empty name
            "<file><name>/SD/a.MP4<size>1</size></file>",           // name never closed
            "<file><name>/SD/Normal/</name></file>",                // a folder, not a clip
            "</file></file><file",                                  // tag soup
            "<file><format size=\"1x1</file>",                      // attribute never closed
            "<html><body><h1>500 Internal Server Error</h1></body></html>",
            "\u0000\u0001\u0002<<<>>>&&&;;;",
            "<file", "<", "<fil", "<file ", "<file><name", "<file><name>x</name><size>"
        )
        for (d in damaged) {
            assertTrue("alone: $d", Cam.parseList(d).isEmpty())
            // Damage after a whole entry must not lose that entry.
            val after = Cam.parseList(good + d)
            assertEquals("after a good entry: $d", listOf("/SD/Normal/F/GOOD.MP4"), after.map { it.path })
        }
        // Damage before a whole entry: an unclosed <file> swallows up to the next </file>, which then
        // belongs to it. Whatever comes back, it is never an exception and never an entry without a name.
        for (d in damaged) {
            for (c in Cam.parseList(d + good + good)) assertTrue(c.name.isNotEmpty())
        }
    }

    @Test
    fun list_missingSizeAndTime() {
        val clips = Cam.parseList("<file><name>/SD/Event/F/E1.MP4</name></file>")
        assertEquals(1, clips.size)
        assertEquals(-1L, clips[0].size)
        assertEquals("", clips[0].time)
        assertEquals("", clips[0].res)
        assertEquals("", clips[0].secs)
    }

    @Test
    fun list_sizeThatIsNotANumber() {
        val xml = "<file><name>/a/1.MP4</name><size>big</size></file>" +
            "<file><name>/a/2.MP4</name><size>-5</size></file>" +
            "<file><name>/a/3.MP4</name><size></size></file>" +
            "<file><name>/a/4.MP4</name><size> 42 </size></file>" +
            "<file><name>/a/5.MP4</name><size>99999999999999999999</size></file>"
        assertEquals(listOf(-1L, -1L, -1L, 42L, -1L), Cam.parseList(xml).map { it.size })
    }

    @Test
    fun list_formatAttributesInAnyOrder() {
        fun one(format: String) = Cam.parseList("<file><name>/a/x.MP4</name>$format<size>1</size></file>").single()

        for (format in listOf(
            "<format size=\"1920x1080\" time=\"60\">mp4</format>",
            "<format time=\"60\" size=\"1920x1080\">mp4</format>",
            "<format fps=\"30\" time=\"60\" codec=\"h264\" size=\"1920x1080\" audio=\"1\">mp4</format>",
            "<format   size = \"1920x1080\"\n  time = \"60\" >mp4</format>",
            "<format size='1920x1080' time='60'>mp4</format>",
            "<format size=\"1920x1080\" time=\"60\"/>"
        )) {
            val c = one(format)
            assertEquals(format, "1920x1080", c.res)
            assertEquals(format, "60", c.secs)
        }
        // Only one of the two present.
        assertEquals("", one("<format time=\"60\">mp4</format>").res)
        assertEquals("60", one("<format time=\"60\">mp4</format>").secs)
        // No attributes at all, or no format tag.
        assertEquals("", one("<format>mp4</format>").secs)
        assertEquals("", one("").res)
        // An attribute whose name merely ends in "size" or "time" is not the one.
        assertEquals("", one("<format filesize=\"9\" uptime=\"8\">mp4</format>").res)
        assertEquals("", one("<format filesize=\"9\" uptime=\"8\">mp4</format>").secs)
        // The <size> element and the size attribute are different things.
        val both = one("<format size=\"1280x720\" time=\"5\">mp4</format>")
        assertEquals(1L, both.size)
        assertEquals("1280x720", both.res)
    }

    @Test
    fun list_elementOrderInsideAFileDoesNotMatter() {
        val c = Cam.parseList(
            "<file><time>2024-02-02 02:02:02</time><size>7</size><format time=\"9\" size=\"4K\">mp4</format>" +
                "<name>/SD/Parking/F/P.MP4</name></file>"
        ).single()
        assertEquals("/SD/Parking/F/P.MP4", c.path)
        assertEquals(7L, c.size)
        assertEquals("2024-02-02 02:02:02", c.time)
        assertEquals("4K", c.res)
        assertEquals("9", c.secs)
    }

    @Test
    fun list_xmlEscapesInNames() {
        val c = Cam.parseList("<file><name>/SD/Photo/A &amp; B &lt;1&gt;.JPG</name></file>").single()
        assertEquals("/SD/Photo/A & B <1>.JPG", c.path)
    }

    @Test
    fun list_tagsWithSimilarNamesAreNotMistaken() {
        val xml = "<files><filename>no</filename></files>" +
            "<file><names>wrong</names><name>/a/right.MP4</name><sizes>9</sizes><size>3</size></file>"
        val c = Cam.parseList(xml).single()
        assertEquals("/a/right.MP4", c.path)
        assertEquals(3L, c.size)
    }

    @Test
    fun page_countsEveryEntryTheCameraSent_clipOrNot() {
        // The next page starts after everything the camera sent, so entries that are not clips still count.
        val page = Cam.parsePage(
            "<DCIM><file><name>/SD/Normal/F/A.MP4</name></file>" +
                "<file><name>/SD/Normal/sub/</name></file>" +          // a folder
                "<file><size>5</size></file>" +                         // no name at all
                "<file><name>/SD/Normal/F/B.MP4</name></file>" +
                "<file><name>/SD/Normal/F/C.MP4</name>" +               // cut off: not an entry
                "<amount>4</amount></DCIM>"
        )
        assertEquals(listOf("A.MP4", "B.MP4"), page.clips.map { it.name })
        assertEquals(4, page.entries)
        assertEquals(0, Cam.parsePage("0\nOK\n").entries)
        assertEquals(0, Cam.parsePage("").entries)
    }

    @Test
    fun list_twentyThousandEntries() {
        val sb = StringBuilder("<DCIM>")
        for (i in 0 until 20_000) {
            sb.append("<file><name>/SD/Normal/F/F").append(i).append(".MP4</name><format size=\"1x1\" time=\"60\">mp4</format>")
                .append("<size>").append(i).append("</size><time>t").append(i).append("</time></file>\n")
        }
        val clips = Cam.parseList(sb.append("</DCIM>").toString())
        assertEquals(20_000, clips.size)
        assertEquals("/SD/Normal/F/F19999.MP4", clips.last().path)
        assertEquals(19_999L, clips.last().size)
    }

    @Test(timeout = 10_000)
    fun list_hugeDamagedReply_isStillQuick() {
        // Two megabytes of openings that are never closed: must come back empty, promptly, without blowing the stack.
        val sb = StringBuilder()
        while (sb.length < 2 * 1024 * 1024) sb.append("<file><name>/SD/x")
        assertTrue(Cam.parseList(sb.toString()).isEmpty())
        // And one enormous entry with a megabyte of junk in the middle.
        val junk = "y".repeat(1024 * 1024)
        val c = Cam.parseList("<file><name>/a/ok.MP4</name><junk>$junk</junk><size>12</size></file>").single()
        assertEquals(12L, c.size)
    }

    // ---------------------------------------------------------------- parseProps

    @Test
    fun props_statusPrefixIsNotASetting() {
        val p = Cam.parseProps("0\nOK\nCamera.Menu.VideoRes=1440P30\nCamera.Menu.LoopingVideo=1MIN\n")
        assertEquals(mapOf("Camera.Menu.VideoRes" to "1440P30", "Camera.Menu.LoopingVideo" to "1MIN"), p)
    }

    @Test
    fun props_blankLinesAndLineEndings() {
        val p = Cam.parseProps("0\r\nOK\r\n\r\n\r\nA=1\r\n\n   \nB=2\r\n\r\n")
        assertEquals(mapOf("A" to "1", "B" to "2"), p)
        assertEquals(listOf("A", "B"), p.keys.toList())
    }

    @Test
    fun props_valuesContainingEquals() {
        val p = Cam.parseProps("0\nOK\nNet.WIFI_AP.CryptoKey=ab=cd==\nUrl=http://x/?a=1&b=2\nE==\n")
        assertEquals("ab=cd==", p["Net.WIFI_AP.CryptoKey"])
        assertEquals("http://x/?a=1&b=2", p["Url"])
        assertEquals("=", p["E"])
    }

    @Test
    fun props_edges() {
        assertTrue(Cam.parseProps("").isEmpty())
        assertTrue(Cam.parseProps("0\nOK\n").isEmpty())
        assertTrue(Cam.parseProps("709\n?\n").isEmpty())
        val p = Cam.parseProps("  Key With Spaces  =  value with spaces  \nEmpty=\n=novalue\nnoequals\nDup=1\nDup=2\n")
        assertEquals("value with spaces", p["Key With Spaces"])
        assertEquals("", p["Empty"])
        assertEquals("2", p["Dup"])
        assertEquals(3, p.size)
    }
}
