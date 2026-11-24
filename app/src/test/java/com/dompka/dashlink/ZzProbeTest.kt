package com.dompka.dashlink

import org.junit.Test
import java.net.InetAddress
import java.util.Random

/** Throwaway probe for the review; deleted afterwards. */
class ZzProbeTest {
    private class FakeTimer : LinkCore.Timer {
        private class Task(val at: Long, val run: () -> Unit)
        var time = 1_000_000L
        private val tasks = ArrayList<Task>()
        val pending: Int get() = tasks.size
        override fun now(): Long = time
        override fun after(delayMs: Long, run: () -> Unit): Any = Task(time + delayMs, run).also { tasks.add(it) }
        override fun cancel(token: Any) { tasks.remove(token) }
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
        val registered = LinkedHashSet<LinkCore.Request>()
        val asked = ArrayList<LinkCore.Request>()
        var fail: Exception? = null
        val last: LinkCore.Request get() = asked.last()
        override fun request(request: LinkCore.Request, timeoutMs: Int) {
            fail?.let { throw it }
            asked.add(request)
            registered.add(request)
        }
        override fun release(request: LinkCore.Request) { registered.remove(request) }
    }

    private val cameraIp = InetAddress.getByName("192.168.0.1")
    private val phoneIp = InetAddress.getByName("192.168.0.10")

    private fun p(s: String) = println("PROBE " + s)

    private fun st(core: LinkCore, radio: FakeRadio) =
        "state=${core.state} reconnecting=${core.reconnecting} asked=${radio.asked.size} requests=${core.requests} retries=${core.retries} err=${core.lastError}"

    @Test
    fun f1_unavailableWhileConnected_singleWifi() {
        for (connectedFor in listOf(300_000L, 5_000L)) {
            val timer = FakeTimer(); val radio = FakeRadio(); val notes = ArrayList<String>()
            val core = LinkCore(radio, timer, {}, { notes.add(it) })
            core.singleWifi = true
            core.connect("MiVue", "12345678", false)
            core.onAvailable(radio.last, "net")
            core.onSsid(radio.last, "\"MiVue_a46240\"")
            core.onAddresses(radio.last, cameraIp, listOf(cameraIp), listOf(phoneIp))
            timer.advance(connectedFor)
            radio.registered.remove(radio.last)
            core.onUnavailable(radio.last)
            p("F1 connectedFor=$connectedFor after onUnavailable: ${st(core, radio)}")
            timer.advance(3_000)
            p("F1 connectedFor=$connectedFor +3s: ${st(core, radio)}")
            // now let every later search time out
            var n = 0
            while (core.state == LinkState.SEARCHING && n < 50) {
                timer.advance(45_000); radio.registered.remove(radio.last); core.onUnavailable(radio.last)
                timer.advance(3_000); n++
            }
            p("F1 connectedFor=$connectedFor final: ${st(core, radio)} loops=$n")
            p("F1 notes=" + notes)
        }
    }

    @Test
    fun f1_fuzz_singleWifi_askedMoreThanTapped() {
        val rnd = Random(20261110)
        var bad = 0
        repeat(300) {
            val timer = FakeTimer(); val radio = FakeRadio()
            val core = LinkCore(radio, timer, {})
            core.singleWifi = true
            var taps = 0
            val seen = ArrayList<LinkCore.Request>()
            repeat(60) {
                seen.addAll(radio.asked.filter { it !in seen })
                val target = if (seen.isEmpty()) null else seen[rnd.nextInt(seen.size)]
                when (rnd.nextInt(10)) {
                    0 -> { taps++; core.connect("MiVue", "12345678", rnd.nextBoolean()) }
                    1 -> core.disconnect()
                    2 -> target?.let { core.onAvailable(it, "n") }
                    3 -> target?.let { core.onLost(it) }
                    4 -> target?.let { radio.registered.remove(it); core.onUnavailable(it) }
                    5 -> target?.let { core.onSsid(it, "MiVue_956W_1234") }
                    6 -> target?.let { core.onAddresses(it, cameraIp, emptyList(), listOf(phoneIp)) }
                    7 -> timer.advance(rnd.nextInt(5_000).toLong())
                    8 -> timer.advance(rnd.nextInt(70_000).toLong())
                    9 -> radio.fail = if (rnd.nextInt(4) == 0) SecurityException("no") else null
                }
            }
            if (radio.asked.size > taps) bad++
        }
        p("F1 fuzz: runs where Android was asked more often than Connect was tapped: $bad / 300")
    }

    @Test
    fun f1_fuzz_singleWifi_onlyOnLostAsDrop() {
        // Same fuzz, but onUnavailable is only delivered to requests still SEARCHING (the platform contract
        // for a timeout), never to a connected one.
        val rnd = Random(20261110)
        var bad = 0
        repeat(300) {
            val timer = FakeTimer(); val radio = FakeRadio()
            val core = LinkCore(radio, timer, {})
            core.singleWifi = true
            var taps = 0
            val seen = ArrayList<LinkCore.Request>()
            repeat(60) {
                seen.addAll(radio.asked.filter { it !in seen })
                val target = if (seen.isEmpty()) null else seen[rnd.nextInt(seen.size)]
                when (rnd.nextInt(10)) {
                    0 -> { taps++; core.connect("MiVue", "12345678", rnd.nextBoolean()) }
                    1 -> core.disconnect()
                    2 -> target?.let { core.onAvailable(it, "n") }
                    3 -> target?.let { core.onLost(it) }
                    4 -> target?.let { if (core.state != LinkState.CONNECTED) { radio.registered.remove(it); core.onUnavailable(it) } }
                    5 -> target?.let { core.onSsid(it, "MiVue_956W_1234") }
                    6 -> target?.let { core.onAddresses(it, cameraIp, emptyList(), listOf(phoneIp)) }
                    7 -> timer.advance(rnd.nextInt(5_000).toLong())
                    8 -> timer.advance(rnd.nextInt(70_000).toLong())
                    9 -> radio.fail = if (rnd.nextInt(4) == 0) SecurityException("no") else null
                }
            }
            if (radio.asked.size > taps) bad++
        }
        p("F1 fuzz (no onUnavailable while CONNECTED): runs asked > taps: $bad / 300")
    }

    @Test
    fun f3_redact() {
        val lines = listOf(
            "Android gave up early (prompt declined, wrong password or Wi-Fi off)",
            "Connect tapped: looking for a network starting with \"MiVue\" (this phone has one Wi-Fi connection)",
            "not asked for again: on a phone with one Wi-Fi connection that would take it from what has it now",
            "Wi-Fi up: \"Passat_AA\"",
            "Android refused the request: IllegalArgumentException: passphrase not ASCII encodable",
        )
        for (l in lines) p("F3 redact(\"$l\") -> \"${Diag.redact(l)}\"")
    }

    @Test
    fun f4_ringBuffer() {
        // Clear LinkLog by filling it with markers first
        repeat(50) { LinkLog.add("filler") }
        val timer = FakeTimer(); val radio = FakeRadio()
        val core = LinkCore(radio, timer, {}, { LinkLog.add(it) })
        core.connect("MiVue_a46240", "12345678", true)
        core.onAvailable(radio.last, "net")
        core.onSsid(radio.last, "\"MiVue_a46240\"")
        timer.advance(60_000)
        LinkLog.add("Wi-Fi gone: \"MiVue_a46240\"")
        core.onLost(radio.last)
        var n = 0
        while (core.state != LinkState.NOT_FOUND && n < 100) {
            timer.advance(3_000)
            timer.advance(45_000)
            radio.registered.remove(radio.last)
            core.onUnavailable(radio.last)
            n++
        }
        val l = LinkLog.lines()
        p("F4 lines=${l.size} first=${l.first()} last=${l.last()}")
        p("F4 has 'lost after'=${l.any { it.contains("lost after") }} has 'Connect tapped'=${l.any { it.contains("Connect tapped") }} has 'Wi-Fi gone'=${l.any { it.contains("Wi-Fi gone") }}")
        p("F4 count 'not found within' = ${l.count { it.contains("not found within") }}")
        for (x in l) p("F4   $x")
    }

    @Test
    fun extra_dropWhileSearching_singleWifi() {
        // A drop reported during SEARCHING (onLost without onAvailable): ignored? (r === active)
        val timer = FakeTimer(); val radio = FakeRadio(); val notes = ArrayList<String>()
        val core = LinkCore(radio, timer, {}, { notes.add(it) })
        core.singleWifi = true
        core.connect("MiVue", "12345678", false)
        core.onLost(radio.last)
        p("X onLost during SEARCHING: ${st(core, radio)} notes=$notes")
    }

    @Test
    fun extra_twoWifi_retryCycleThenTimeoutNoReasonLine() {
        val timer = FakeTimer(); val radio = FakeRadio(); val notes = ArrayList<String>()
        val core = LinkCore(radio, timer, {}, { notes.add(it) })
        core.connect("MiVue_a46240", "12345678", true)
        core.onAvailable(radio.last, "net")
        timer.advance(60_000)
        core.onLost(radio.last)
        timer.advance(3_000)
        timer.advance(45_000); radio.registered.remove(radio.last); core.onUnavailable(radio.last)
        timer.advance(3_000)
        p("X two-wifi notes=$notes")
    }

    @Test
    fun extra_lostAfterOnLostBeforeAvailable() {
        // 'lost after' uses connectedAt, which is stale (0 or previous connection) if onLost comes on a request never available
        val timer = FakeTimer(); val radio = FakeRadio(); val notes = ArrayList<String>()
        val core = LinkCore(radio, timer, {}, { notes.add(it) })
        core.connect("MiVue_a46240", "12345678", true)
        timer.advance(10_000)
        core.onLost(radio.last)
        p("X onLost before onAvailable notes=$notes state=${core.state}")
    }
}
