package com.dompka.dashlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * The second Wi-Fi link, to the camera.
 *
 * It is requested as a "local-only" network (WifiNetworkSpecifier, no internet capability), which on
 * phones with dual-Wi-Fi support comes up NEXT TO the Wi-Fi the phone is already using - e.g. the
 * car's wireless Android Auto link - instead of replacing it. Nothing in the process is bound to
 * it globally; Cam.kt opens its sockets on this Network explicitly.
 */
object Link {
    enum class State { IDLE, SEARCHING, CONNECTED, LOST, NOT_FOUND }

    private class Want(val ssid: String, val pass: String, val exact: Boolean)

    @Volatile var state = State.IDLE
        private set
    @Volatile var network: Network? = null
        private set
    @Volatile var cameraIp: String? = null
        private set
    @Volatile var cameraSsid: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /** Every Wi-Fi network the phone currently holds (camera included), for the status line. */
    val wifiNets = ConcurrentHashMap<Network, NetworkCapabilities>()

    /** Called on the main thread whenever anything above changes. */
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var watching = false
    private var camCb: ConnectivityManager.NetworkCallback? = null
    private var want: Want? = null
    private var everConnected = false
    private var retries = 0
    private val retry = Runnable {
        if (want != null && state != State.CONNECTED) {
            release()
            request()
        }
    }

    private fun cm(c: Context) = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (watching) return
        watching = true
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                wifiNets[n] = caps
                onChange?.invoke()
            }

            override fun onLost(n: Network) {
                wifiNets.remove(n)
                onChange?.invoke()
            }
        }
        cm(ctx).registerNetworkCallback(req, cb, main)
    }

    /** [ssid] is the camera's Wi-Fi name, or just the start of it unless [exact]. */
    fun connect(ctx: Context, ssid: String, pass: String, exact: Boolean) {
        init(ctx)
        main.removeCallbacks(retry)
        release()
        want = Want(ssid, pass, exact)
        everConnected = false
        retries = 0
        request()
    }

    fun disconnect() {
        want = null
        main.removeCallbacks(retry)
        release()
        moveTo(State.IDLE)
    }

    private fun request() {
        val ctx = app ?: return
        val w = want ?: return
        val spec = WifiNetworkSpecifier.Builder()
            .setSsidPattern(
                PatternMatcher(w.ssid, if (w.exact) PatternMatcher.PATTERN_LITERAL else PatternMatcher.PATTERN_PREFIX)
            )
            .setWpa2Passphrase(w.pass)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onAvailable(n: Network) {
                network = n
                everConnected = true
                retries = 0
                moveTo(State.CONNECTED)
            }

            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                wifiNets[n] = caps
                val name = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')
                if (!name.isNullOrEmpty() && name != WifiManager.UNKNOWN_SSID) cameraSsid = name
                onChange?.invoke()
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                pickIp(lp)?.let { cameraIp = it }
                onChange?.invoke()
            }

            override fun onUnavailable() {
                // Timed out, or the system prompt was declined. Android has already dropped the request.
                camCb = null
                moveTo(State.NOT_FOUND)
                retryLater()
            }

            override fun onLost(n: Network) {
                wifiNets.remove(n)
                if (network == n) {
                    network = null
                    cameraIp = null
                }
                moveTo(State.LOST)
                retryLater()
            }
        }
        camCb = cb
        lastError = null
        moveTo(State.SEARCHING)
        try {
            cm(ctx).requestNetwork(req, cb, main, 45_000)
        } catch (e: Exception) {
            // e.g. the "nearby devices" permission was refused
            camCb = null
            lastError = e.javaClass.simpleName + ": " + e.message
            moveTo(State.NOT_FOUND)
        }
    }

    /** The camera is the DHCP server / gateway of its own little network. */
    private fun pickIp(lp: LinkProperties): String? {
        lp.dhcpServerAddress?.hostAddress?.let { if (it != "0.0.0.0") return it }
        for (r in lp.routes) {
            val gw = r.gateway
            if (r.hasGateway() && gw is Inet4Address) gw.hostAddress?.let { return it }
        }
        val own = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress ?: return null
        return own.substringBeforeLast('.') + ".1"
    }

    /** Only re-request by itself after a link that worked; never loop on a declined prompt. */
    private fun retryLater() {
        if (want == null || !everConnected || retries >= 20) return
        retries++
        main.removeCallbacks(retry)
        main.postDelayed(retry, 3000)
    }

    private fun release() {
        val ctx = app
        val cb = camCb
        if (ctx != null && cb != null) {
            try {
                cm(ctx).unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // already released
            }
        }
        camCb = null
        network?.let { wifiNets.remove(it) }
        network = null
        cameraIp = null
    }

    private fun moveTo(s: State) {
        state = s
        onChange?.invoke()
    }
}
