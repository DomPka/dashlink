package com.dompka.dashlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Diagnostics report must never carry the camera's Wi-Fi password. */
class DiagTest {
    private val reply = """
        0
        OK
        Vendor.DeviceInfo.Model=MiVue 956W
        Net.WIFI_AP.SSID=MiVue_956W_1234
        Net.WIFI_AP.CryptoKey=hunter2secret
        Net.WIFI_STA.AP.1.CryptoKey=other-key
        Net.Dev.1.Password=opensesame
        Net.WIFI_AP.WPA_PSK=abcdefgh
        Net.WIFI_AP.Pwd=zzzzzzzz
        Camera.Menu.VideoRes=1440P30
    """.trimIndent()

    @Test
    fun linesAboutKeysAndPasswordsAreLeftOut() {
        val out = Diag.redact(reply)
        for (secret in listOf("hunter2secret", "other-key", "opensesame", "abcdefgh", "zzzzzzzz")) {
            assertFalse("$secret leaked", out.contains(secret))
        }
        for (word in listOf("CryptoKey", "Password", "PSK", "Pwd")) assertFalse(out.contains(word))
        // Everything else is kept, in order.
        assertEquals(
            listOf("0", "OK", "Vendor.DeviceInfo.Model=MiVue 956W", "Net.WIFI_AP.SSID=MiVue_956W_1234", "Camera.Menu.VideoRes=1440P30"),
            out.lines()
        )
    }

    @Test
    fun matchingIgnoresCase() {
        val out = Diag.redact("a=1\nNET.CRYPTOKEY=topsecret1\nnet.passphrase=topsecret2\nSomeKEY=topsecret3\nb=2")
        assertEquals("a=1\nb=2", out)
    }

    @Test
    fun theTypedPasswordIsRemovedWhereverItAppears() {
        // The camera may echo the key under a name nobody thought of, or inside another value.
        val body = "Vendor.Odd.Field=12345678\nUrl=http://cam/?k=12345678&x=1\n<name>/SD/12345678.MP4</name>"
        val out = Diag.redact(body, listOf("12345678"))
        assertFalse(out.contains("12345678"))
        assertTrue(out.contains("Vendor.Odd.Field=[removed]"))
        assertTrue(out.contains("<name>/SD/[removed].MP4</name>"))
        // mask() does only that: lines that merely mention the word "password" stay.
        val text = "Link: the password is wrong\nstate=12345678"
        assertEquals("Link: the password is wrong\nstate=[removed]", Diag.mask(text, listOf("12345678")))
    }

    @Test
    fun anEmptyOrTinySecretDoesNotBlankTheReport() {
        val body = "abc=1\nabd=2"
        assertEquals(body, Diag.redact(body, listOf("")))
        assertEquals(body, Diag.redact(body, listOf("ab")))
        assertEquals(body, Diag.redact(body))
    }

    @Test
    fun listsAndOrdinaryRepliesPassUntouched() {
        val xml = "<file><name>/SD/Normal/F/A.MP4</name><size>5</size></file>"
        assertEquals(xml, Diag.redact(xml, listOf("12345678")))
        assertEquals("", Diag.redact(""))
    }

    @Test
    fun longReportsAreCut() {
        assertEquals("short", Diag.limit("short", 100))
        val cut = Diag.limit("x".repeat(1000), 100)
        assertTrue(cut.startsWith("x".repeat(100) + "\n"))
        assertTrue(cut.contains("900 more characters"))
        assertTrue(cut.length < 200)
    }
}
