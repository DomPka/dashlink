package com.dompka.dashlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Random

/**
 * The rules of the camera link, driven by a pretend radio and a pretend clock: what the app does with
 * every answer Android can give to "please connect me to this Wi-Fi", without a phone or a camera.
 */
class LinkCoreTest {

    private class FakeTimer : LinkCore.Timer {
        private class Task(val at: Long, val run: () -> Unit)

        var time = 1_000_000L
        private val tasks = ArrayList<Task>()
        val pending: Int get() = tasks.size

        override fun now(): Long = time
        override fun after(delayMs: Long, run: () -> Unit): Any = Task(time + delayMs, run).also { tasks.add(it) }
        override fun cancel(token: Any) {
            tasks.remove(token)
        }

        fun advance(ms: Long) {
            val end = time + ms
            while (true) {
                val next = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                time = next.at
                next.run()
            }
            time = end
        }
    }

    private class FakeRadio : LinkCore.Radio {
        /** What "the system" currently holds. Anything left in here at the end is a leaked callback. */
        val registered = LinkedHashSet<LinkCore.Request>()
        val asked = ArrayList<LinkCore.Request>()
        val timeouts = ArrayList<Int>()
        var releases = 0
        var fail: Exception? = null
        var onRequest: ((LinkCore.Request) -> Unit)? = null

        val last: LinkCore.Request get() = asked.last()

        override fun request(request: LinkCore.Request, timeoutMs: Int) {
            fail?.let { throw it }
            asked.add(request)
            timeouts.add(timeoutMs)
            registered.add(request)
            onRequest?.invoke(request)
        }

        override fun release(request: LinkCore.Request) {
            releases++
            registered.remove(request)
        }

        /** The system gives up on a request by itself (time-out, declined prompt): it forgets it, then says so. */
        fun systemDrops(core: LinkCore, r: LinkCore.Request) {
            registered.remove(r)
            core.onUnavailable(r)
        }
    }

    private lateinit var timer: FakeTimer
    private lateinit var radio: FakeRadio
    private lateinit var core: LinkCore
    private var changes = 0

    private val cameraIp = InetAddress.getByName("192.72.1.1")
    private val phoneIp = InetAddress.getByName("192.72.1.33")

    @Before
    fun setUp() {
        timer = FakeTimer()
        radio = FakeRadio()
        changes = 0
        core = LinkCore(radio, timer) { changes++ }
    }

    private fun connected(exact: Boolean = true) {
        core.connect(if (exact) "MiVue_956W_1234" else "MiVue", "12345678", exact)
        core.onAvailable(radio.last, "net-${radio.asked.size}")
        core.onAddresses(radio.last, cameraIp, listOf(cameraIp), listOf(phoneIp))
        assertEquals(LinkState.CONNECTED, core.state)
    }

    private fun assertNothingLeft() {
        assertTrue("a network callback is still registered: ${radio.registered}", radio.registered.isEmpty())
        assertEquals("a timer is still pending", 0, timer.pending)
    }

    // ---------------------------------------------------------------- connecting

    @Test
    fun connect_success() {
        assertEquals(LinkState.IDLE, core.state)
        core.connect("MiVue", "12345678", false)
        assertEquals(LinkState.SEARCHING, core.state)
        assertEquals(1, radio.asked.size)
        assertEquals("MiVue", radio.last.want.ssid)
        assertEquals("12345678", radio.last.want.pass)
        assertFalse(radio.last.want.exact)
        assertNull(core.network)

        core.onAvailable(radio.last, "the-network")
        assertEquals(LinkState.CONNECTED, core.state)
        assertEquals("the-network", core.network)
        assertNull("no address yet", core.cameraIp)
        assertNull(core.lastError)

        core.onAddresses(radio.last, cameraIp, emptyList(), listOf(phoneIp))
        assertEquals("192.72.1.1", core.cameraIp)
        assertTrue(changes >= 3)
        assertEquals(1, radio.registered.size)
        assertEquals("the safety-net timer is gone once connected", 0, timer.pending)
        timer.advance(10 * 60_000)
        assertEquals(LinkState.CONNECTED, core.state)
        assertEquals(1, radio.asked.size)
    }

    @Test
    fun requestNetwork_securityException() {
        radio.fail = SecurityException("NEARBY_WIFI_DEVICES denied")
        core.connect("MiVue", "12345678", false)
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertTrue(core.lastError!!.contains("SecurityException"))
        assertTrue(core.lastError!!.contains("NEARBY_WIFI_DEVICES denied"))
        assertNothingLeft()
        timer.advance(10 * 60_000)
        assertEquals("no retry after a refusal", LinkState.NOT_FOUND, core.state)
        // And the user can simply try again.
        radio.fail = null
        core.connect("MiVue", "12345678", false)
        assertEquals(LinkState.SEARCHING, core.state)
        assertNull(core.lastError)
    }

    @Test
    fun requestNetwork_anyOtherException() {
        // e.g. a passphrase that is not ASCII (IllegalArgumentException from the builder), or too many requests.
        for (e in listOf(IllegalArgumentException("passphrase not ASCII encodable"), IllegalStateException("x"), RuntimeException("Too many NetworkRequests filed"))) {
            radio.fail = e
            core.connect("MiVue", "pässwörd", false)
            assertEquals(LinkState.NOT_FOUND, core.state)
            assertTrue(core.lastError!!.contains(e.javaClass.simpleName))
            assertNothingLeft()
        }
    }

    @Test
    fun wrongPassword_userDeclines_wifiOff_allArriveAsAnEarlyNo() {
        // Android reports each of these the same way: onUnavailable() well before the time-out.
        // With the prompt on screen that can be minutes later: its own search takes 30 s and offers "Try again".
        for (afterMs in listOf(0L, 300L, 5_000L, 20_000L, 100_000L, 170_000L)) {
            core.connect("MiVue", "12345678", false)
            timer.advance(afterMs)
            radio.systemDrops(core, radio.last)
            assertEquals(LinkState.NOT_FOUND, core.state)
            assertNotNull(core.lastError)
            assertTrue(core.lastError!!.contains("declined") && core.lastError!!.contains("password") && core.lastError!!.contains("Wi-Fi is off"))
            assertNothingLeft()
            val asked = radio.asked.size
            timer.advance(30 * 60_000)
            assertEquals("no retry loop", asked, radio.asked.size)
            assertEquals(LinkState.NOT_FOUND, core.state)
        }
        // A silent search for an exact name is short; a "no" within it is early too.
        core.connect("MiVue_956W_1234", "12345678", true)
        timer.advance(20_000)
        radio.systemDrops(core, radio.last)
        assertTrue(core.lastError!!.contains("declined"))
    }

    @Test
    fun timeout_firstConnection_isReportedAndNotRetried() {
        // A known, exact name: Android searches without showing anything, so the app sets the limit (45 s).
        core.connect("MiVue_956W_1234", "12345678", true)
        assertEquals(45_000, radio.timeouts.last())
        timer.advance(45_000)
        radio.systemDrops(core, radio.last)
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertTrue(core.lastError!!, core.lastError!!.contains("found within 45 s"))
        assertNothingLeft()
        timer.advance(30 * 60_000)
        assertEquals(1, radio.asked.size)
    }

    @Test
    fun androidsOwnPrompt_isNotCutShort() {
        // The start of a name: Android shows its "choose a device" prompt, which searches for 30 seconds and
        // then offers "Try again". The app must not end the request under it after 45 seconds.
        core.connect("MiVue", "12345678", false)
        assertEquals(180_000, radio.timeouts.last())
        timer.advance(120_000)
        assertEquals("still the user's turn", LinkState.SEARCHING, core.state)
        assertEquals(1, radio.registered.size)
        // Only a prompt nobody answers for three minutes is ended, so the phone does not scan for ever.
        timer.advance(60_000)
        radio.systemDrops(core, radio.last)
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertTrue(core.lastError!!, core.lastError!!.contains("found within 180 s"))
        assertNothingLeft()
        timer.advance(30 * 60_000)
        assertEquals(1, radio.asked.size)
    }

    @Test
    fun systemNeverAnswers_theSafetyNetEndsTheSearch() {
        core.connect("MiVue_956W_1234", "12345678", true)
        timer.advance(45_000 + LinkCore.WATCHDOG_MS - 1)
        assertEquals(LinkState.SEARCHING, core.state)
        timer.advance(1)
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertEquals("the request is taken back", 1, radio.releases)
        assertNothingLeft()

        core.connect("MiVue", "12345678", false)
        timer.advance(180_000 + LinkCore.WATCHDOG_MS - 1)
        assertEquals(LinkState.SEARCHING, core.state)
        timer.advance(1)
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertNothingLeft()
    }

    @Test
    fun radioThatAnswersImmediately_endsConnected() {
        radio.onRequest = { core.onAvailable(it, "instant") }
        core.connect("MiVue", "12345678", true)
        assertEquals(LinkState.CONNECTED, core.state)
        assertEquals(0, timer.pending)
    }

    // ---------------------------------------------------------------- dropping and reconnecting

    @Test
    fun linkLost_stateIsCorrect_andItReconnects() {
        connected()
        val first = radio.last
        core.onLost(first)
        assertEquals(LinkState.LOST, core.state)
        assertNull(core.network)
        assertNull(core.cameraIp)
        assertTrue(core.reconnecting)
        assertTrue("the dead request is released at once", radio.registered.isEmpty())
        assertEquals(1, radio.asked.size)

        timer.advance(2_999)
        assertEquals(1, radio.asked.size)
        timer.advance(1)
        assertEquals(2, radio.asked.size)
        assertEquals(LinkState.SEARCHING, core.state)
        assertTrue(core.reconnecting)
        assertTrue(radio.last.want.exact)

        core.onAvailable(radio.last, "net-again")
        core.onAddresses(radio.last, cameraIp, emptyList(), listOf(phoneIp))
        assertEquals(LinkState.CONNECTED, core.state)
        assertEquals("net-again", core.network)
        assertEquals("192.72.1.1", core.cameraIp)
        assertFalse(core.reconnecting)
        assertEquals(0, core.retries)
        assertEquals(setOf(radio.last), radio.registered)
        assertEquals(0, timer.pending)
    }

    @Test
    fun linkLost_withoutAnExactName_doesNotBringThePromptBack() {
        // Android never told us the camera's name (location permission missing), so asking again would put
        // the system prompt on screen with nobody having tapped anything.
        connected(exact = false)
        core.onLost(radio.last)
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertTrue(core.lastError!!.contains("Tap Connect"))
        assertFalse(core.reconnecting)
        assertNothingLeft()
        timer.advance(30 * 60_000)
        assertEquals(1, radio.asked.size)
    }

    @Test
    fun learnedName_makesLaterRequestsExact() {
        core.connect("MiVue", "12345678", false)
        core.onAvailable(radio.last, "net")
        core.onSsid(radio.last, "<unknown ssid>")
        assertNull(core.cameraSsid)
        core.onSsid(radio.last, null)
        core.onSsid(radio.last, "\"\"")
        assertNull(core.cameraSsid)
        core.onSsid(radio.last, "\"MiVue_956W_1234\"")
        assertEquals("MiVue_956W_1234", core.cameraSsid)

        core.onLost(radio.last)
        assertEquals(LinkState.LOST, core.state)
        timer.advance(3_000)
        assertEquals(2, radio.asked.size)
        assertEquals("MiVue_956W_1234", radio.last.want.ssid)
        assertEquals("12345678", radio.last.want.pass)
        assertTrue(radio.last.want.exact)
    }

    @Test
    fun promptDeclinedAfterADrop_noRetryLoop() {
        connected()
        core.onLost(radio.last)
        timer.advance(3_000)
        assertEquals(2, radio.asked.size)
        // The system prompt came up after all and the user said no (or the password no longer fits).
        timer.advance(4_000)
        radio.systemDrops(core, radio.last)
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertFalse(core.reconnecting)
        assertNothingLeft()
        timer.advance(60 * 60_000)
        assertEquals("exactly one automatic attempt, then silence", 2, radio.asked.size)
    }

    @Test
    fun wifiSwitchedOffAfterADrop_noRetryLoop() {
        connected()
        core.onLost(radio.last)
        timer.advance(3_000)
        radio.systemDrops(core, radio.last)            // refused at once: Wi-Fi is off
        assertEquals(LinkState.NOT_FOUND, core.state)
        assertNothingLeft()
        timer.advance(60 * 60_000)
        assertEquals(2, radio.asked.size)
    }

    @Test
    fun cameraOutOfRangeAfterADrop_keepsLookingThenGivesUp() {
        connected()
        core.onLost(radio.last)
        var attempts = 0
        while (core.state != LinkState.NOT_FOUND) {
            assertEquals(LinkState.LOST, core.state)
            assertTrue(core.reconnecting)
            timer.advance(3_000)
            assertEquals(LinkState.SEARCHING, core.state)
            attempts++
            assertEquals(1 + attempts, radio.asked.size)
            assertEquals("never two requests at once", 1, radio.registered.size)
            timer.advance(45_000)                       // nothing found: the system's own time-out
            radio.systemDrops(core, radio.last)
            if (attempts > 100) throw AssertionError("it never gives up")
        }
        assertEquals(20, attempts)
        assertTrue(core.lastError!!.contains("did not come back"))
        assertNothingLeft()
        timer.advance(60 * 60_000)
        assertEquals(21, radio.asked.size)
    }

    @Test
    fun cameraComesBackOnTheThirdAttempt() {
        connected()
        core.onLost(radio.last)
        repeat(2) {
            timer.advance(3_000)
            timer.advance(45_000)
            radio.systemDrops(core, radio.last)
            assertEquals(LinkState.LOST, core.state)
        }
        timer.advance(3_000)
        core.onAvailable(radio.last, "back")
        assertEquals(LinkState.CONNECTED, core.state)
        assertEquals(0, core.retries)
        // A later drop gets the full number of attempts again.
        core.onLost(radio.last)
        assertEquals(LinkState.LOST, core.state)
        assertEquals(1, core.retries)
    }

    @Test
    fun differentNetworkForTheSameRequest_startsWithNoAddress() {
        connected()
        val r = radio.last
        assertEquals("192.72.1.1", core.cameraIp)
        core.onAvailable(r, "another network")
        assertEquals(LinkState.CONNECTED, core.state)
        assertNull("the old network's address says nothing about the new one", core.cameraIp)
        // Even a mere guess is taken for the new network; the old one's better rank does not block it.
        core.onAddresses(r, null, emptyList(), listOf(ip("10.9.8.7")))
        assertEquals("10.9.8.1", core.cameraIp)
        // The same network reported again changes nothing.
        core.onAvailable(r, "another network")
        assertEquals("10.9.8.1", core.cameraIp)
    }

    // ---------------------------------------------------------------- no leaked callbacks

    @Test
    fun disconnect_fromEveryState_leavesNothingBehind() {
        core.disconnect()
        assertEquals(LinkState.IDLE, core.state)

        core.connect("MiVue", "12345678", false)        // searching
        core.disconnect()
        assertEquals(LinkState.IDLE, core.state)
        assertNothingLeft()

        connected()                                     // connected
        core.disconnect()
        assertEquals(LinkState.IDLE, core.state)
        assertNull(core.network)
        assertNull(core.cameraIp)
        assertNothingLeft()

        connected()                                     // lost, waiting to retry
        core.onLost(radio.last)
        core.disconnect()
        assertEquals(LinkState.IDLE, core.state)
        assertFalse(core.reconnecting)
        assertNothingLeft()

        connected()                                     // lost, retry in flight
        core.onLost(radio.last)
        timer.advance(3_000)
        core.disconnect()
        assertNothingLeft()

        val asked = radio.asked.size
        timer.advance(60 * 60_000)
        assertEquals("nothing happens after disconnect", asked, radio.asked.size)
        assertEquals(LinkState.IDLE, core.state)
        assertNull(core.lastError)
    }

    @Test
    fun connectAgain_replacesTheOldRequest() {
        core.connect("MiVue", "12345678", false)
        val first = radio.last
        core.connect("MiVue", "87654321", false)
        val second = radio.last
        assertEquals(setOf(second), radio.registered)
        assertEquals("87654321", second.want.pass)

        // Late answers to the first question change nothing.
        core.onAvailable(first, "old")
        assertEquals(LinkState.SEARCHING, core.state)
        assertNull(core.network)
        core.onSsid(first, "OldCam")
        assertNull(core.cameraSsid)
        core.onAddresses(first, cameraIp, emptyList(), listOf(phoneIp))
        assertNull(core.cameraIp)
        core.onLost(first)
        core.onUnavailable(first)
        assertEquals(LinkState.SEARCHING, core.state)
        assertEquals(setOf(second), radio.registered)

        core.onAvailable(second, "new")
        assertEquals("new", core.network)
        core.connect("Other", "12345678", false)
        assertEquals(setOf(radio.last), radio.registered)
        assertNull("the previous camera's name is not carried over", core.cameraSsid)
        assertNull(core.network)
    }

    @Test
    fun anyOrderOfEvents_neverLeaksARequest() {
        val rnd = Random(20261110)
        repeat(300) {
            setUp()
            val seen = ArrayList<LinkCore.Request>()
            repeat(60) {
                seen.addAll(radio.asked.filter { it !in seen })
                val target = if (seen.isEmpty()) null else seen[rnd.nextInt(seen.size)]
                when (rnd.nextInt(10)) {
                    0 -> core.connect("MiVue", "12345678", rnd.nextBoolean())
                    1 -> core.disconnect()
                    2 -> target?.let { core.onAvailable(it, "n") }
                    3 -> target?.let { core.onLost(it) }
                    4 -> target?.let { radio.systemDrops(core, it) }
                    5 -> target?.let { core.onSsid(it, "MiVue_956W_1234") }
                    6 -> target?.let { core.onAddresses(it, cameraIp, emptyList(), listOf(phoneIp)) }
                    7 -> timer.advance(rnd.nextInt(5_000).toLong())
                    8 -> timer.advance(rnd.nextInt(70_000).toLong())
                    9 -> {
                        radio.fail = if (rnd.nextInt(4) == 0) SecurityException("no") else null
                    }
                }
                assertTrue("more than one request registered", radio.registered.size <= 1)
                when (core.state) {
                    LinkState.IDLE, LinkState.NOT_FOUND, LinkState.LOST ->
                        assertTrue("${core.state} with a request still registered", radio.registered.isEmpty())
                    LinkState.CONNECTED -> {
                        assertEquals(1, radio.registered.size)
                        assertNotNull(core.network)
                    }
                    LinkState.SEARCHING -> assertEquals(1, radio.registered.size)
                }
                if (core.state != LinkState.CONNECTED) {
                    assertNull(core.network)
                    assertNull(core.cameraIp)
                }
                if (core.state == LinkState.IDLE || core.state == LinkState.NOT_FOUND) assertEquals(0, timer.pending)
            }
            core.disconnect()
            assertNothingLeft()
        }
    }

    // ---------------------------------------------------------------- camera address

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun cameraAddress_dhcpServerFirst() {
        val p = pickCameraIp(ip("192.72.1.1"), listOf(ip("192.72.1.254")), listOf(ip("192.72.1.33")))!!
        assertEquals("192.72.1.1", p.ip)
        assertEquals(3, p.rank)
    }

    @Test
    fun cameraAddress_dhcpServerMissing_usesTheGateway() {
        for (dhcp in listOf(null, ip("0.0.0.0"), ip("fe80::1"), ip("127.0.0.1"))) {
            val p = pickCameraIp(dhcp, listOf(ip("fe80::1"), ip("192.72.1.1")), listOf(ip("fe80::2"), ip("192.72.1.33")))!!
            assertEquals("192.72.1.1", p.ip)
            assertEquals(2, p.rank)
        }
    }

    @Test
    fun cameraAddress_gatewayMissingToo_guessesDotOne() {
        // A camera that hands out an address and nothing else: no router option, so no gateway route.
        val p = pickCameraIp(null, emptyList(), listOf(ip("fe80::2"), ip("192.72.1.33")))!!
        assertEquals("192.72.1.1", p.ip)
        assertEquals(1, p.rank)
        assertEquals("192.168.1.1", pickCameraIp(null, listOf(ip("0.0.0.0")), listOf(ip("192.168.1.105")))!!.ip)
        assertEquals("10.0.0.1", pickCameraIp(ip("0.0.0.0"), listOf(ip("fe80::1")), listOf(ip("10.0.0.7")))!!.ip)
    }

    @Test
    fun cameraAddress_nothingToGoBy() {
        assertNull(pickCameraIp(null, emptyList(), emptyList()))
        assertNull("IPv6 only", pickCameraIp(null, listOf(ip("fe80::1")), listOf(ip("fe80::2"))))
        assertNull("the camera gave no address: self-assigned", pickCameraIp(null, emptyList(), listOf(ip("169.254.17.3"))))
        assertNull("the phone itself is .1 and nothing else is known", pickCameraIp(null, emptyList(), listOf(ip("192.168.43.1"))))
    }

    @Test
    fun cameraAddress_neverThePhonesOwn() {
        // A confused report where the "server" is the phone itself must not make the app talk to itself.
        val p = pickCameraIp(ip("192.72.1.33"), listOf(ip("192.72.1.33")), listOf(ip("192.72.1.33")))!!
        assertEquals("192.72.1.1", p.ip)
        assertEquals(1, p.rank)
    }

    @Test
    fun cameraAddress_aGuessNeverReplacesABetterAnswer() {
        core.connect("MiVue", "12345678", true)
        val r = radio.last
        core.onAvailable(r, "net")
        // First report: only the phone's own address is known yet.
        core.onAddresses(r, null, emptyList(), listOf(ip("192.72.1.33")))
        assertEquals("192.72.1.1", core.cameraIp)
        // Then the real answer arrives, and it is different.
        core.onAddresses(r, ip("192.72.1.254"), emptyList(), listOf(ip("192.72.1.33")))
        assertEquals("192.72.1.254", core.cameraIp)
        // A later, poorer report (lease renewed, DHCP details missing) changes nothing.
        core.onAddresses(r, null, emptyList(), listOf(ip("192.72.1.33")))
        assertEquals("192.72.1.254", core.cameraIp)
        core.onAddresses(r, null, listOf(ip("192.72.1.1")), listOf(ip("192.72.1.33")))
        assertEquals("192.72.1.254", core.cameraIp)
        // A report with nothing usable does not wipe it either.
        core.onAddresses(r, null, emptyList(), emptyList())
        assertEquals("192.72.1.254", core.cameraIp)
        // What the phone was told is kept for the diagnostics report, whatever was made of it.
        assertEquals("DHCP server null, gateways [], phone [] -> nothing usable", core.addressInfo)
        core.onAddresses(r, ip("192.72.1.254"), listOf(ip("192.72.1.254")), listOf(ip("192.72.1.33")))
        assertEquals("DHCP server 192.72.1.254, gateways [192.72.1.254], phone [192.72.1.33] -> 192.72.1.254 (DHCP server)", core.addressInfo)

        // After a drop the slate is clean: the next connection starts from what it is told.
        core.onLost(r)
        assertNull(core.cameraIp)
        timer.advance(3_000)
        val again = radio.last
        assertTrue(again !== r)
        core.onAvailable(again, "net2")
        core.onAddresses(again, null, emptyList(), listOf(ip("10.1.2.3")))
        assertEquals("10.1.2.1", core.cameraIp)
        assertSame("net2", core.network)
    }
}
