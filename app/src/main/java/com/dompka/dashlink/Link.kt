package com.dompka.dashlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The second Wi-Fi link, to the camera.
 *
 * It is requested as a "local-only" network (WifiNetworkSpecifier, no internet capability), which on
 * phones with dual-Wi-Fi support comes up NEXT TO the Wi-Fi the phone is already using - e.g. the
 * car's wireless Android Auto link - instead of replacing it. Nothing in the process is bound to
 * it globally; Cam.kt opens its sockets on this Network explicitly.
 *
 * This file is only the Android side: it turns ConnectivityManager callbacks into calls on LinkCore,
 * which holds the rules (retry, give up, camera address). Call everything here from the main thread.
 */
object Link {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var watching = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Every Wi-Fi network the phone currently holds (camera included), for the status line. */
    val wifiNets = ConcurrentHashMap<Network, NetworkCapabilities>()

    /** The system side of the link. Tests swap in a pretend one. */
    @Volatile
    internal var radio: LinkCore.Radio = SystemRadio()

    internal val core = LinkCore(
        object : LinkCore.Radio {
            override fun request(request: LinkCore.Request, timeoutMs: Int) = radio.request(request, timeoutMs)
            override fun release(request: LinkCore.Request) = radio.release(request)
        },
        object : LinkCore.Timer {
            override fun now(): Long = SystemClock.elapsedRealtime()
            override fun after(delayMs: Long, run: () -> Unit): Any = Runnable { run() }.also { main.postDelayed(it, delayMs) }
            override fun cancel(token: Any) = main.removeCallbacks(token as Runnable)
        },
        ::changed
    )

    val state: LinkState get() = core.state
    val network: Network? get() = core.network as? Network
    val cameraIp: String? get() = core.cameraIp
    val cameraSsid: String? get() = core.cameraSsid
    val lastError: String? get() = core.lastError
    val reconnecting: Boolean get() = core.reconnecting

    /** True once requests can reach the camera. */
    val ready: Boolean get() = core.state == LinkState.CONNECTED && core.cameraIp != null

    /** Nothing is connected and nothing will try again by itself. */
    val gone: Boolean get() = core.state == LinkState.IDLE || core.state == LinkState.NOT_FOUND

    /** [listener] is called on the main thread whenever anything above changes. */
    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun changed() {
        for (l in listeners) l()
    }

    private fun cm(c: Context) = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        if (watching) return
        try {
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            cm(ctx).registerNetworkCallback(req, watcher, main)
            watching = true
        } catch (e: Exception) {
            // Only the "other Wi-Fi" part of the status line depends on this; it is tried again on the next init().
        }
    }

    private val watcher = object : ConnectivityManager.NetworkCallback(
        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
    ) {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            wifiNets[n] = caps
            changed()
        }

        override fun onLost(n: Network) {
            wifiNets.remove(n)
            changed()
        }
    }

    /** [ssid] is the camera's Wi-Fi name, or just the start of it unless [exact]. */
    fun connect(ctx: Context, ssid: String, pass: String, exact: Boolean) {
        init(ctx)
        core.connect(ssid, pass, exact)
    }

    fun disconnect() = core.disconnect()

    /** Opens [pathAndQuery] on the camera, over the camera's own network whatever the phone's main Wi-Fi is. */
    @Throws(IOException::class)
    fun open(pathAndQuery: String): HttpURLConnection {
        val net = network ?: throw IOException("not connected to the camera")
        val host = cameraIp ?: throw IOException("camera address not known yet")
        // Straight to the camera: a proxy configured for the phone's other networks must not get in the way.
        return net.openConnection(URL("http://$host$pathAndQuery"), Proxy.NO_PROXY) as HttpURLConnection
    }

    /** The real thing: WifiNetworkSpecifier requests through ConnectivityManager. */
    private class SystemRadio : LinkCore.Radio {
        private val callbacks = HashMap<LinkCore.Request, Callback>()

        private inner class Callback(val r: LinkCore.Request) : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            var net: Network? = null

            override fun onAvailable(n: Network) {
                net = n
                core.onAvailable(r, n)
            }

            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                if (callbacks[r] !== this) return
                wifiNets[n] = caps
                core.onSsid(r, (caps.transportInfo as? WifiInfo)?.ssid)
                changed()
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                core.onAddresses(
                    r,
                    lp.dhcpServerAddress,
                    lp.routes.filter { it.hasGateway() }.mapNotNull { it.gateway },
                    lp.linkAddresses.map { it.address }
                )
            }

            override fun onUnavailable() = core.onUnavailable(r)

            override fun onLost(n: Network) {
                wifiNets.remove(n)
                core.onLost(r)
            }
        }

        override fun request(request: LinkCore.Request, timeoutMs: Int) {
            val ctx = app ?: throw IllegalStateException("Link.init() was not called")
            val w = request.want
            // The builder itself rejects some input (a passphrase that is not plain ASCII, an empty name);
            // that is reported like any other refusal instead of crashing the app.
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
            val cb = Callback(request)
            if (timeoutMs > 0) cm(ctx).requestNetwork(req, cb, main, timeoutMs) else cm(ctx).requestNetwork(req, cb, main)
            callbacks[request] = cb
        }

        override fun release(request: LinkCore.Request) {
            val cb = callbacks.remove(request) ?: return
            cb.net?.let { wifiNets.remove(it) }
            val ctx = app ?: return
            try {
                cm(ctx).unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // Android had already dropped it (after onUnavailable)
            }
        }
    }
}
