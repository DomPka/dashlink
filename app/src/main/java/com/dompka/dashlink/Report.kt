package com.dompka.dashlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/** Builds the Diagnostics report. Call from a background thread: it talks to the camera. */
internal object Report {
    private const val MAX_CHARS = 100_000
    private const val MAX_JOBS = 100

    /** The runtime permissions the app asks for on this version of Android, with the name Settings shows. */
    fun wantedPermissions(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add(Manifest.permission.ACCESS_FINE_LOCATION to "Location")
        if (Build.VERSION.SDK_INT >= 33) {
            out.add(Manifest.permission.NEARBY_WIFI_DEVICES to "Nearby devices")
            out.add(Manifest.permission.POST_NOTIFICATIONS to "Notifications")
        }
        return out
    }

    fun missingPermissions(ctx: Context): List<Pair<String, String>> =
        wantedPermissions().filter { ctx.checkSelfPermission(it.first) != PackageManager.PERMISSION_GRANTED }

    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName + " (" + ctx.getString(R.string.build_commit) + ")"
    } catch (e: Exception) {
        "?"
    }

    private fun safe(block: () -> Any?): String = try {
        block().toString()
    } catch (e: Exception) {
        "? (${e.javaClass.simpleName})"
    }

    /** [secret] is the Wi-Fi password typed into the app: it is cut out of everything the camera says. */
    fun build(ctx: Context, folder: String, rear: Boolean, secret: String): String {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val sb = StringBuilder("DashLink ${version(ctx)} diagnostics\n")
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Two Wi-Fi links at once (local-only): ${safe { wifi.isStaConcurrencyForLocalOnlyConnectionsSupported }}\n")
        sb.append("Wi-Fi + hotspot at once: ${safe { wifi.isStaApConcurrencySupported }}\n")
        sb.append("Bands: 2.4=${safe { wifi.is24GHzBandSupported }} 5=${safe { wifi.is5GHzBandSupported }} 6=${safe { wifi.is6GHzBandSupported }}\n")
        sb.append("Wi-Fi switched on: ${safe { wifi.isWifiEnabled }}, location switched on: ${safe { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled }}\n")
        val missing = missingPermissions(ctx).map { it.second }
        sb.append("Permissions missing: ${if (missing.isEmpty()) "none" else missing.joinToString()}\n")
        sb.append("Battery: exempt from optimisation=${Power.exempt(ctx)}, background restricted=${Power.restricted(ctx)}, standby bucket=${Power.standbyBucket(ctx)}\n")
        sb.append("Link: ${Link.state}, camera ${Link.cameraSsid} at ${Link.cameraIp}, asked ${Link.core.requests}x (${Link.core.retries} automatic)\n")
        Link.lastError?.let { sb.append("  ").append(it).append("\n") }
        for ((net, caps) in Link.wifiNets) {
            val w = caps.transportInfo as? WifiInfo
            val role = if (net == Link.network) "camera" else "other"
            sb.append("  Wi-Fi [$role] ${w?.ssid} ${w?.frequency} MHz ${w?.rssi} dBm ${w?.linkSpeed} Mbps\n")
        }
        sb.append("Download service: running=${DownloadService.alive}, working=${DownloadService.working}, locks held=${DownloadService.locksHeld}")
        DownloadService.foregroundError?.let { sb.append(", NOT in the foreground: ").append(it) }
        sb.append("\n")
        Jobs.paused?.let { sb.append("Queue: ").append(it).append("\n") }
        if (Jobs.interrupted) sb.append("Queue: the previous run ended while downloads were in progress\n")
        sb.append("Free space: ${safe { MediaFiles(ctx).freeBytes() / 1_048_576 }} MB\n")
        val exits = Power.lastExits(ctx)
        if (exits.isNotEmpty()) {
            sb.append("\n== Why Android ended the app before\n")
            for (e in exits) sb.append(e).append("\n")
        }
        App.lastCrash(ctx)?.let { sb.append("\n== Last crash\n").append(it.take(4000)).append("\n") }

        if (Link.state == LinkState.CONNECTED) {
            try {
                // One turn at the camera for all three questions; give up quickly if a download has it.
                Cam.session(3000) {
                    for ((title, path) in listOf(
                        "Device info" to Cam.INFO,
                        "Status" to Cam.STATUS,
                        "List $folder" to Cam.listUrl(folder, rear, 0, Cam.PAGE)
                    )) {
                        sb.append("\n== $title: $path\n")
                        try {
                            // Never put the camera's Wi-Fi password in a report.
                            sb.append(Diag.redact(Cam.get(path, 10000), listOf(secret)).take(3000)).append("\n")
                        } catch (e: Exception) {
                            sb.append("FAILED ${Cam.describe(e)}\n")
                        }
                    }
                }
            } catch (e: Cam.BusyException) {
                sb.append("\n== Camera not asked: it is busy with a download\n")
            }
        }

        sb.append("\n== Downloads\n")
        val jobs = Jobs.all.toList()
        if (jobs.size > MAX_JOBS) sb.append("(the last $MAX_JOBS of ${jobs.size})\n")
        for (j in jobs.takeLast(MAX_JOBS)) {
            sb.append("${j.clip.name}: ${j.state} ${j.done}/${j.clip.size}")
            j.lastError?.let { sb.append("  last error: ").append(it) }
            sb.append("\n")
        }
        // The password must not be in the report at all, and the report travels inside an Intent, which has a size limit.
        return Diag.limit(Diag.mask(sb.toString(), listOf(secret)), MAX_CHARS)
    }
}
