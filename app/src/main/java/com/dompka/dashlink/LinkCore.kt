package com.dompka.dashlink

import java.net.Inet4Address
import java.net.InetAddress

enum class LinkState { IDLE, SEARCHING, CONNECTED, LOST, NOT_FOUND }

/**
 * The rules of the camera link: when to ask Android for the network, when to ask again, when to stop
 * asking, and which address is the camera's. It holds no Android classes, so the rules can be tested on
 * a desk with a pretend radio and a pretend clock; Link.kt feeds it the real ConnectivityManager callbacks.
 *
 * Everything here runs on one thread (the main thread in the app). The @Volatile fields may be read anywhere.
 */
internal class LinkCore(
    private val radio: Radio,
    private val timer: Timer,
    private val changed: () -> Unit,
    /** Told what happened, in a few words, as it happens: kept for the Diagnostics report. */
    private val note: (String) -> Unit = {},
) {
    /** What LinkCore needs from ConnectivityManager. */
    interface Radio {
        /** Asks the system for the network [request] describes. May throw. Answers come back through the on…() calls below. */
        fun request(request: Request, timeoutMs: Int)

        /** Drops [request]. Harmless if it was never registered or is already gone. */
        fun release(request: Request)
    }

    interface Timer {
        fun now(): Long
        fun after(delayMs: Long, run: () -> Unit): Any
        fun cancel(token: Any)
    }

    /** [ssid] is the camera's Wi-Fi name, or just the start of it unless [exact]. */
    class Want(val ssid: String, val pass: String, val exact: Boolean)

    /** One question put to the system. Answers that belong to an older question are ignored. */
    class Request(val want: Want)

    @Volatile var state = LinkState.IDLE
        private set
    @Volatile var network: Any? = null
        private set
    @Volatile var cameraIp: String? = null
        private set
    @Volatile var cameraSsid: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /** What Android last reported about the camera network's addresses, and what was made of it. */
    @Volatile var addressInfo: String? = null
        private set

    /** How often the system has been asked since connect(), and how many of those were automatic. */
    @Volatile var requests = 0
        private set
    @Volatile var retries = 0
        private set

    /** How long a silent search may take: an exact, known name that Android joins without asking anyone. */
    var timeoutMs = 45_000

    /**
     * How long Android's own "choose a device" prompt may stay up. It searches for 30 seconds and then
     * offers "Try again" by itself; ending the request under it would replace that with a box saying
     * "the application has cancelled the request". This limit is only there so that a prompt nobody
     * answers does not keep the phone scanning for ever.
     */
    var promptTimeoutMs = 180_000
    var retryDelayMs = 3_000L
    var maxRetries = 20

    /**
     * The phone can hold only one Wi-Fi connection (no local-only second link). Then the camera's network
     * replaces the phone's other Wi-Fi, and when Android takes the camera's away again it is for something
     * else (wireless Android Auto, a saved network). Asking for it back at once would cut that off, and it
     * would take the Wi-Fi back in turn: a tug of war that ends neither. So after such a drop nothing is
     * asked for until someone taps Connect. Set before [connect].
     */
    var singleWifi = false

    private var want: Want? = null
    private var active: Request? = null
    private var askedAt = 0L
    private var limitMs = 0
    private var everConnected = false
    private var connectedAt = 0L
    private var retryTimer: Any? = null
    private var watchdog: Any? = null
    private var ipRank = 0

    /** True while the link is down but will be asked for again without anyone tapping anything. */
    val reconnecting: Boolean get() = want != null && retries > 0 && state != LinkState.CONNECTED && state != LinkState.NOT_FOUND

    fun connect(ssid: String, pass: String, exact: Boolean) {
        stopTimers()
        drop()
        want = Want(ssid, pass, exact)
        everConnected = false
        retries = 0
        requests = 0
        cameraSsid = null
        note("Connect tapped: looking for ${if (exact) "\"$ssid\"" else "a network starting with \"$ssid\""}" +
            if (singleWifi) " (this phone has one Wi-Fi connection)" else "")
        ask()
    }

    fun disconnect() {
        if (want != null) note("Disconnect")
        want = null
        stopTimers()
        drop()
        retries = 0
        lastError = null
        move(LinkState.IDLE)
    }

    private fun ask() {
        val w = want ?: return
        val r = Request(w)
        active = r
        askedAt = timer.now()
        limitMs = if (w.exact) timeoutMs else promptTimeoutMs
        requests++
        lastError = null
        state = LinkState.SEARCHING
        try {
            radio.request(r, limitMs)
        } catch (e: Exception) {
            // A permission was refused, the password cannot be a WPA2 passphrase, the system has too many requests…
            active = null
            release(r)
            lastError = "Android refused to look for the camera (${Cam.describe(e)})"
            note("Android refused the request: ${Cam.describe(e)}")
            move(LinkState.NOT_FOUND)
            return
        }
        if (active === r && state == LinkState.SEARCHING) {
            // Safety net: Android promises onUnavailable() after the time-out. If it never says anything, stop waiting anyway.
            watchdog = timer.after(limitMs + WATCHDOG_MS) {
                watchdog = null
                if (active === r && state == LinkState.SEARCHING) unavailable(r, timedOut = true)
            }
        }
        changed()
    }

    /**
     * Whether to ask again without being told to. Only for an exact, known network name: Android joins
     * that silently once it has been approved. A name pattern would put the system prompt on the screen
     * again and again, in front of someone who may be driving.
     */
    private fun mayRetry(): Boolean = !singleWifi && want?.exact == true && retries < maxRetries

    fun onAvailable(r: Request, net: Any) {
        if (r !== active) return
        cancelWatchdog()
        if (network != null && network != net) {
            // A different network for the same request: what was learned about the old one's addresses is void.
            cameraIp = null
            ipRank = 0
            addressInfo = null
        }
        network = net
        everConnected = true
        connectedAt = timer.now()
        note("connected after ${seconds(connectedAt - askedAt)}")
        retries = 0
        lastError = null
        move(LinkState.CONNECTED)
    }

    /** [name] as Android reports it: possibly quoted, possibly hidden because the location permission is missing. */
    fun onSsid(r: Request, name: String?) {
        if (r !== active || network == null) return
        val clean = name?.trim()?.trim('"') ?: return
        if (clean.isEmpty() || clean == UNKNOWN_SSID || clean == cameraSsid) return
        cameraSsid = clean
        note("the camera's network is \"$clean\"")
        // From now on ask for exactly this network: Android skips its prompt for an exact name it has approved before.
        want?.let { want = Want(clean, it.pass, exact = true) }
        changed()
    }

    fun onAddresses(r: Request, dhcpServer: InetAddress?, gateways: List<InetAddress>, own: List<InetAddress>) {
        if (r !== active || network == null) return
        val pick = pickCameraIp(dhcpServer, gateways, own)
        // Kept word for word for the diagnostics report: if the camera's address is ever picked wrongly,
        // this line says what the phone was told.
        addressInfo = "DHCP server ${dhcpServer?.hostAddress}, gateways ${gateways.map { it.hostAddress }}, " +
            "phone ${own.map { it.hostAddress }} -> " +
            (pick?.let { "${it.ip} (${listOf("", "guessed", "gateway", "DHCP server")[it.rank]})" } ?: "nothing usable")
        if (pick == null) return
        // A later update with less information must not replace a better answer with a guess.
        if (pick.rank < ipRank) return
        ipRank = pick.rank
        if (pick.ip != cameraIp) {
            cameraIp = pick.ip
            changed()
        }
    }

    /** The system gave up on [r]: it timed out, the prompt was declined, the password was wrong, Wi-Fi is off… */
    fun onUnavailable(r: Request) {
        if (r !== active) return
        // Android may also end a request it had already served this way: that is the link dropping, not a search failing.
        if (state == LinkState.CONNECTED) {
            lost(r)
            return
        }
        unavailable(r, timedOut = timer.now() - askedAt >= limitMs - EARLY_MS)
    }

    private fun unavailable(r: Request, timedOut: Boolean) {
        cancelWatchdog()
        active = null
        release(r)
        clearNetwork()
        // Android does not say why. But only a time-out takes the full time; everything a person or the
        // system decides (declined prompt, wrong password, Wi-Fi off, app in the background) comes back early.
        // So: keep looking by itself only for a camera that worked before and is simply not in range right
        // now. Never ask again after an early "no" - that is how a declined prompt turns into a loop.
        val why = if (timedOut) "not found within ${seconds(limitMs.toLong())}" else "Android ended the search early (prompt declined, wrong Wi-Fi key or Wi-Fi off)"
        if (timedOut && everConnected && mayRetry()) {
            retryLater(why)
            return
        }
        note(why)
        lastError = when {
            !timedOut -> "Android could not join the camera's Wi-Fi: the prompt was declined, the password is wrong, or Wi-Fi is off."
            everConnected -> "The camera's Wi-Fi did not come back. Tap Connect to look for it again."
            else -> "No camera Wi-Fi with that name was found within ${limitMs / 1000} s. Is Wi-Fi switched on in the camera's menu?"
        }
        move(LinkState.NOT_FOUND)
    }

    fun onLost(r: Request) {
        if (r !== active) return
        lost(r)
    }

    private fun lost(r: Request) {
        cancelWatchdog()
        val why = "lost after ${seconds(timer.now() - connectedAt)} connected"
        // The system does not serve a lost request again by itself; drop it now and ask afresh shortly.
        active = null
        release(r)
        clearNetwork()
        if (singleWifi) {
            note("$why; not asked for again (this phone has one Wi-Fi connection)")
            lastError = "The camera's Wi-Fi dropped: the camera may be off or out of reach, or Android gave this " +
                "phone's only Wi-Fi connection to something else, such as wireless Android Auto. On this phone " +
                "DashLink does not take it back by itself: tap Connect when you want the camera, and downloads go " +
                "on from where they stopped."
            move(LinkState.NOT_FOUND)
            return
        }
        if (mayRetry()) {
            retryLater(why)
        } else {
            note(why)
            lastError = "The camera's Wi-Fi dropped. Tap Connect to look for it again."
            move(LinkState.NOT_FOUND)
        }
    }

    private fun retryLater(why: String) {
        retries++
        note("$why; asking again by itself in ${seconds(retryDelayMs)} (${retries}x)")
        retryTimer?.let { timer.cancel(it) }
        retryTimer = timer.after(retryDelayMs) {
            retryTimer = null
            if (want != null && active == null) ask()
        }
        move(LinkState.LOST)
    }

    private fun release(r: Request) {
        try {
            radio.release(r)
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun drop() {
        active?.let { release(it) }
        active = null
        clearNetwork()
    }

    private fun clearNetwork() {
        network = null
        cameraIp = null
        ipRank = 0
        addressInfo = null
    }

    private fun cancelWatchdog() {
        watchdog?.let { timer.cancel(it) }
        watchdog = null
    }

    private fun stopTimers() {
        cancelWatchdog()
        retryTimer?.let { timer.cancel(it) }
        retryTimer = null
    }

    private fun move(s: LinkState) {
        state = s
        changed()
    }

    private fun seconds(ms: Long): String = if (ms < 10_000) String.format(java.util.Locale.US, "%.1f s", ms / 1000.0) else "${ms / 1000} s"

    companion object {
        /** WifiManager.UNKNOWN_SSID: what Android reports when the app may not see Wi-Fi names. */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** An answer this much before the time-out or later counts as "timed out". */
        const val EARLY_MS = 2_000

        const val WATCHDOG_MS = 15_000L
    }
}

internal class IpPick(val ip: String, val rank: Int)

private fun usable(a: InetAddress?): String? {
    if (a !is Inet4Address || a.isAnyLocalAddress || a.isLoopbackAddress || a.isMulticastAddress) return null
    return a.hostAddress
}

/**
 * The camera is the DHCP server / gateway of its own little network. Best answer first:
 * the DHCP server (rank 3), then a gateway (rank 2), and if the camera handed out neither, the usual
 * ".1" of the phone's own subnet (rank 1, a guess). Null when there is no IPv4 address to go by.
 */
internal fun pickCameraIp(dhcpServer: InetAddress?, gateways: List<InetAddress>, own: List<InetAddress>): IpPick? {
    val mine = own.mapNotNull { usable(it) }
    usable(dhcpServer)?.let { if (it !in mine) return IpPick(it, 3) }
    for (g in gateways) usable(g)?.let { if (it !in mine) return IpPick(it, 2) }
    for (m in mine) {
        if (m.startsWith("169.254.")) continue          // self-assigned: the camera gave no address at all
        val guess = m.substringBeforeLast('.') + ".1"
        if (guess != m) return IpPick(guess, 1)
    }
    return null
}
