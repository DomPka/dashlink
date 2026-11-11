package com.dompka.dashlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the screen shows, kept outside the Activity so that it survives the Activity being rebuilt
 * (language or font-size change, "Don't keep activities", the system reclaiming it in the background):
 * the file list is not fetched from the camera again and ticked clips stay ticked.
 *
 * Everything here belongs to the main thread, except what runs inside [background].
 */
internal object Browser {
    private val ui = Handler(Looper.getMainLooper())

    /** One request to the camera at a time (its own app also queues them); downloads run in the service. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "dashlink-camera") }

    var folders = listOf("Normal", "Event", "Parking", "Photo")
        private set
    var folder = 0
    var rear = false
    var clips: List<Cam.Clip> = emptyList()
        private set

    /** Paths of the ticked clips. */
    val ticked = HashSet<String>()
    var info: Map<String, String> = emptyMap()
        private set
    var note = ""
        private set

    /** The screen that is currently showing this, if any. */
    var onChange: (() -> Unit)? = null

    private var app: Context? = null
    private var linked = false
    private var savedSsid: String? = null
    private val gen = AtomicInteger()
    private val keepAliveQueued = AtomicBoolean()

    fun attach(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Link.addListener { linkChanged() }
    }

    val folderName: String get() = folders.getOrElse(folder) { folders[0] }

    private fun changed() {
        onChange?.invoke()
    }

    /** Runs [block] on the camera thread. Nothing it throws can reach the system's crash handler. */
    fun background(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                // A failed list or report is shown as such; it is never a reason to crash.
            }
        }
    }

    private fun linkChanged() {
        val now = Link.ready
        val ssid = Link.cameraSsid
        if (now && ssid != null && ssid != savedSsid) {
            // Remember the camera's full Wi-Fi name: an exact name lets Android skip its prompt next time.
            savedSsid = ssid
            app?.getSharedPreferences("dashlink", Context.MODE_PRIVATE)?.edit()
                ?.putString("exactSsid", ssid)?.putString("ssid", ssid)?.apply()
        }
        if (now && !linked) onLinked()
        linked = now
        changed()
    }

    private fun onLinked() {
        background {
            var fresh: Map<String, String> = emptyMap()
            try {
                fresh = Cam.parseProps(Cam.get(Cam.INFO))
            } catch (e: Exception) {
                // The list below reports the problem if the camera is not answering.
            }
            ui.post {
                info = fresh
                val names = listOf(
                    fresh["Vendor.DeviceInfo.Record.VideoFolder"] ?: "Normal",
                    fresh["Vendor.DeviceInfo.Record.EventFolder"] ?: "Event",
                    fresh["Vendor.DeviceInfo.Record.ParkingFolder"] ?: "Parking",
                    fresh["Vendor.DeviceInfo.Record.PhotoFolder"] ?: "Photo"
                ).map { it.substringBefore(',').replace("/", "") }.filter { it.isNotEmpty() }.distinct()
                if (names.isNotEmpty() && names != folders) {
                    folders = names
                    if (folder >= names.size) folder = 0
                }
                load()
            }
        }
    }

    /** Fetches the current folder again. A newer call makes an older one stop and its result be ignored. */
    fun load() {
        val g = gen.incrementAndGet()
        if (!Link.ready) {
            if (clips.isEmpty()) note = "Connect to the camera to see its files."
            changed()
            return
        }
        val name = folderName
        val rearNow = rear
        note = if (DownloadService.working) {
            "Loading $name… (the camera is busy with a download; the list follows when the current file is done)"
        } else {
            "Loading $name…"
        }
        changed()
        background {
            val listing = try {
                Cam.listAll(name, rearNow, { g == gen.get() }) { n ->
                    ui.post {
                        if (g == gen.get()) {
                            note = "Loading $name… $n files"
                            changed()
                        }
                    }
                }
            } catch (t: Throwable) {
                Cam.Listing(emptyList(), Cam.describe(t), false)
            }
            ui.post {
                if (g != gen.get()) return@post
                clips = listing.clips
                val present = HashSet<String>()
                for (c in clips) present.add(c.path)
                ticked.retainAll(present)
                val what = if (rearNow) "$name (rear camera)" else name
                note = when {
                    listing.error != null && clips.isEmpty() && rearNow ->
                        "Could not list $what (${listing.error}). Is a rear camera fitted?"
                    listing.error != null && clips.isEmpty() -> "Could not list $what (${listing.error})"
                    listing.error != null -> "$what: ${clips.size} files, then the list stopped (${listing.error})"
                    listing.truncated -> "$what: the first ${clips.size} files (the camera has more)"
                    clips.isEmpty() && rearNow -> "$what: no files. Is a rear camera fitted?"
                    else -> "$what: ${clips.size} files"
                }
                changed()
            }
        }
    }

    /** Same keep-alive the camera's own app sends. Skipped, not queued, while anything else is talking to the camera. */
    fun keepAlive() {
        if (!keepAliveQueued.compareAndSet(false, true)) return
        background {
            try {
                Cam.get(Cam.STATUS, 5000, waitMs = 0)
            } catch (e: Exception) {
                // A dropped link is reported through Link's own callbacks.
            } finally {
                keepAliveQueued.set(false)
            }
        }
    }
}
