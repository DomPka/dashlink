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
    private val changed: () -> Unit
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

    /** How often the system has been asked since connect(), and how many of those were automatic. */
    @Volatile var requests = 0
        private set
    @Volatile var retries = 0
        private set

    var timeoutMs = 45_000
    var retryDelayMs = 3_000L
    var maxRetries = 20

    private var want: Want? = null
    private var active: Request? = null
    private var askedAt = 0L
    private var everConnected = false
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
        ask()
    }

    fun disconnect() {
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
        requests++
        lastError = null
        state = LinkState.SEARCHING
        try {
            radio.request(r, timeoutMs)
        } catch (e: Exception) {
            // A permission was refused, the password cannot be a WPA2 passphrase, the system has too many requests…
            active = null
            release(r)
            lastError = "Android refused to look for the camera (${Cam.describe(e)})"
            move(LinkState.NOT_FOUND)
            return
        }
        if (active === r && state == LinkState.SEARCHING) {
            // Safety net: Android promises onUnavailable() after the time-out. If it never says anything, stop waiting anyway.
            watchdog = timer.after(timeoutMs + WATCHDOG_MS) {
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
    private fun mayRetry(): Boolean = want?.exact == true && retries < maxRetries

    fun onAvailable(r: Request, net: Any) {
        if (r !== active) return
        cancelWatchdog()
        network = net
        everConnected = true
        retries = 0
        lastError = null
        move(LinkState.CONNECTED)
    }

    /** [name] as Android reports it: possibly quoted, possibly hidden because the location permission is missing. */
    fun onSsid(r: Request, name: String?) {
        if (r !== active) return
        val clean = name?.trim()?.trim('"') ?: return
        if (clean.isEmpty() || clean == UNKNOWN_SSID || clean == cameraSsid) return
        cameraSsid = clean
        // From now on ask for exactly this network: Android skips its prompt for an exact name it has approved before.
        want?.let { want = Want(clean, it.pass, exact = true) }
        changed()
    }

    fun onAddresses(r: Request, dhcpServer: InetAddress?, gateways: List<InetAddress>, own: List<InetAddress>) {
        if (r !== active) return
        val pick = pickCameraIp(dhcpServer, gateways, own) ?: return
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
        unavailable(r, timedOut = timer.now() - askedAt >= timeoutMs - EARLY_MS)
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
        if (timedOut && everConnected && mayRetry()) {
            retryLater()
            return
        }
        lastError = when {
            !timedOut -> "Android could not join the camera's Wi-Fi: the prompt was declined, the password is wrong, or Wi-Fi is off."
            everConnected -> "The camera's Wi-Fi did not come back. Tap Connect to look for it again."
            else -> "No camera Wi-Fi with that name was found within ${timeoutMs / 1000} s."
        }
        move(LinkState.NOT_FOUND)
    }

    fun onLost(r: Request) {
        if (r !== active) return
        cancelWatchdog()
        // The system does not serve a lost request again by itself; drop it now and ask afresh shortly.
        active = null
        release(r)
        clearNetwork()
        if (mayRetry()) {
            retryLater()
        } else {
            lastError = "The camera's Wi-Fi dropped. Tap Connect to look for it again."
            move(LinkState.NOT_FOUND)
        }
    }

    private fun retryLater() {
        retries++
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
