package com.dompka.dashlink

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * Small still pictures of clips on the camera, made on the phone from the clip itself: MediaMetadataRetriever reads
 * the clip through a RemoteFile (HTTP Range on the camera link) and takes the key frame nearest to one second in.
 * Photos (.JPG) are read whole and decoded downscaled.
 *
 * One worker thread ("dashlink-thumbs") does it, one picture at a time, the most recently bound first: what was
 * just scrolled to is what the user looks at. Pictures are kept in memory (48 of them) and on disk (the cache
 * directory, at most 30 MB, oldest dropped first), by path + size + time, so a clip that changed on the camera
 * gets a new picture.
 *
 * The camera is only asked while the link is up, no download is running, nothing has [paused] thumbnails and no
 * screen holds them off ([hold]). Meanwhile requests stay queued and go on by themselves when that changes; the
 * disk cache is still read. A clip that failed is not asked for again on the same connection, and after three
 * failures in a row nothing more is fetched until the link changes: a camera that cannot do this is not asked
 * over and over.
 */
internal object Thumbs {
    /** Call once (App.onCreate). */
    fun init(ctx: Context) {
        if (dir != null) return
        val app = ctx.applicationContext
        // About twice the 88 x 50 dp view, so the picture stays sharp on dense screens.
        val scale = maxOf(2f, app.resources.displayMetrics.density)
        width = (88 * scale).roundToInt()
        height = (50 * scale).roundToInt()
        dir = File(app.cacheDir, "thumbs").also { it.mkdirs() }
        linkReady = Link.ready
        cameraIp = Link.cameraIp
        Link.addListener(::linkChanged)
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) dropMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Superseded by onTrimMemory, still called on older releases")
            override fun onLowMemory() = dropMemory()
        })
        val t = Thread(::work, "dashlink-thumbs")
        t.isDaemon = true
        t.start()
    }

    /**
     * Shows the thumbnail of [clip] in [view] when it is ready (main thread). [view] may be recycled by a list:
     * binding it to another clip cancels the earlier request. [ready] is called on the main thread with true
     * when a picture was set, false when none will come. Until then the view has no image of its own (the row's
     * placeholder shows).
     */
    fun bind(view: ImageView, clip: Cam.Clip, ready: (Boolean) -> Unit) {
        val key = key(clip)
        view.setTag(R.id.thumb_key, key)
        val known = memory.get(key)
        val failedBefore = lock.withLock {
            drop(view)
            if (known == null && key !in failed) {
                val p = pending.getOrPut(key) { Pending(clip) }
                p.waiters.add(Waiter(WeakReference(view), ready))
                waitingFor[view] = key
                queue.remove(key)
                queue.addFirst(key)
                changed.signalAll()
            }
            key in failed
        }
        when {
            known != null -> {
                view.setImageBitmap(known)
                ready(true)
            }
            failedBefore -> {
                view.setImageDrawable(null)
                ready(false)
            }
            else -> view.setImageDrawable(null)
        }
    }

    /** Forget [view] (it left the screen). */
    fun unbind(view: ImageView) {
        view.setTag(R.id.thumb_key, null)
        lock.withLock { drop(view) }
    }

    /** While true, no new thumbnail is fetched (the screen is not visible, a live view is open...). */
    @Volatile
    var paused = false
        set(value) {
            field = value
            if (!value) lock.withLock { changed.signalAll() }
        }

    /**
     * While any [owner] holds them off, no thumbnail is fetched: the live viewer and the player use the camera
     * themselves and would otherwise be interrupted by every thumbnail. Main thread.
     */
    fun hold(owner: Any, on: Boolean) {
        lock.withLock {
            if (on) holders.add(owner) else holders.remove(owner)
            changed.signalAll()
        }
    }

    /** Lets go of the pictures in memory (the disk keeps them); Android asks for that when memory runs short. */
    fun dropMemory() = memory.evictAll()

    // ---------------------------------------------------------------- state

    private const val MEMORY_ENTRIES = 48
    private const val DISK_BYTES = 30L * 1024 * 1024
    private const val TIME_LIMIT_MS = 15_000L
    private const val PHOTO_LIMIT = 8L * 1024 * 1024
    private const val FAILURES_IN_A_ROW = 3
    private const val POLL_MS = 1_000L
    private const val GUARD_MS = 200L
    private const val QUALITY = 80

    private val main = Handler(Looper.getMainLooper())
    private val memory = LruCache<String, Bitmap>(MEMORY_ENTRIES)

    /** Watches a fetch under way: its time limit, and whether the camera may still be asked at all. */
    private val guard = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dashlink-thumbs-guard").apply { isDaemon = true } }

    private class Waiter(val view: WeakReference<ImageView>, val ready: (Boolean) -> Unit)

    /** A clip somebody waits for; [diskChecked] stays false until the disk cache was looked at for it. */
    private class Pending(val clip: Cam.Clip) {
        val waiters = ArrayList<Waiter>()
        var diskChecked = false
    }

    /** Guards everything from here down to [cameraIp]; the worker waits on [changed]. */
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    /** Keys, the most recently bound first. */
    private val queue = ArrayDeque<String>()
    private val pending = HashMap<String, Pending>()
    private val waitingFor = WeakHashMap<ImageView, String>()
    private val holders = HashSet<Any>()

    /** Keys that failed on this connection. */
    private val failed = HashSet<String>()
    private var failuresInRow = 0
    private var cameraIp: String? = null

    @Volatile
    private var linkReady = false

    private var dir: File? = null
    private var width = 176
    private var height = 100

    /** Bytes in the disk cache, -1 until counted. Worker only. */
    private var diskBytes = -1L

    private fun key(clip: Cam.Clip) = "${clip.path}|${clip.size}|${clip.time}"

    private fun isPhoto(name: String) = name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)

    /** May the camera be asked right now? (Called on the worker and the guard thread, without [lock].) */
    private fun allowed() =
        linkReady && !paused && !DownloadService.working && !Browser.listing && lock.withLock { holders.isEmpty() }

    /** Under [lock]: takes [view] out of the request it waited for; a request nobody waits for is dropped. */
    private fun drop(view: ImageView) {
        val key = waitingFor.remove(view) ?: return
        val p = pending[key] ?: return
        p.waiters.removeAll { it.view.get().let { v -> v == null || v === view } }
        if (p.waiters.isEmpty()) {
            pending.remove(key)
            queue.remove(key)
        }
    }

    /** A new connection (or none any more): what failed on the last one may work on this one. */
    private fun linkChanged() {
        val ready = Link.ready
        val ip = Link.cameraIp
        lock.withLock {
            if (ready != linkReady || ip != cameraIp) {
                failed.clear()
                failuresInRow = 0
            }
            linkReady = ready
            cameraIp = ip
            changed.signalAll()
        }
    }

    // ---------------------------------------------------------------- the worker

    private sealed class Job {
        class Disk(val key: String) : Job()
        class Fetch(val key: String, val clip: Cam.Clip) : Job()

        /** Requests that will get no picture: three failures in a row, and not on disk. */
        class Refuse(val gone: List<Pending>) : Job()
    }

    private fun work() {
        while (true) {
            when (val job = next()) {
                is Job.Disk -> fromDisk(job.key)
                is Job.Fetch -> fetch(job.key, job.clip)
                is Job.Refuse -> for (p in job.gone) answer(p, null)
            }
        }
    }

    /** Waits for the next thing to do: the disk first (it never touches the camera), then the camera when allowed. */
    private fun next(): Job {
        lock.lock()
        try {
            while (true) {
                // Rows that went away without unbind() (a closed screen) are nobody's business any more.
                val keys = queue.iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val p = pending.getValue(key)
                    p.waiters.removeAll { w -> w.view.get() == null }
                    if (p.waiters.isEmpty()) {
                        pending.remove(key)
                        keys.remove()
                    }
                }
                queue.firstOrNull { !pending.getValue(it).diskChecked }?.let { return Job.Disk(it) }
                if (queue.isEmpty()) {
                    changed.await()
                    continue
                }
                if (failuresInRow >= FAILURES_IN_A_ROW) {
                    val gone = queue.map { pending.getValue(it) }
                    pending.clear()
                    queue.clear()
                    waitingFor.clear()
                    return Job.Refuse(gone)
                }
                if (allowed()) {
                    val key = queue.first()
                    return Job.Fetch(key, pending.getValue(key).clip)
                }
                // Paused, offline or a download is running: look again in a second, or as soon as anything changes.
                changed.await(POLL_MS, TimeUnit.MILLISECONDS)
            }
        } finally {
            lock.unlock()
        }
    }

    /** Hands [bitmap] (null: none will come) to the views still waiting for [p]. Removes nothing; see [take]. */
    private fun answer(p: Pending, bitmap: Bitmap?) {
        val key = key(p.clip)
        main.post {
            for (w in p.waiters) {
                val view = w.view.get() ?: continue
                if (view.getTag(R.id.thumb_key) != key) continue        // bound to another clip meanwhile
                if (bitmap != null) view.setImageBitmap(bitmap)
                w.ready(bitmap != null)
            }
        }
    }

    /** The request for [key], taken out of the queue, or null if nobody waits for it any more. */
    private fun take(key: String): Pending? = lock.withLock {
        val p = pending.remove(key) ?: return@withLock null
        queue.remove(key)
        for (w in p.waiters) w.view.get()?.let { v -> if (waitingFor[v] == key) waitingFor.remove(v) }
        p
    }

    private fun fileFor(key: String): File? = dir?.let { File(it, sha1(key) + ".jpg") }

    private fun fromDisk(key: String) {
        val file = fileFor(key)
        val bitmap = try {
            file?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        } catch (e: OutOfMemoryError) {
            null
        }
        if (bitmap == null) {
            lock.withLock { pending[key]?.diskChecked = true }      // not there: the camera is next
            return
        }
        file?.setLastModified(System.currentTimeMillis())             // recently used: trimmed last
        memory.put(key, bitmap)
        take(key)?.let { answer(it, bitmap) }
    }

    private fun fetch(key: String, clip: Cam.Clip) {
        when (val r = make(clip)) {
            is Made.Picture -> {
                memory.put(key, r.bitmap)
                store(key, r.bitmap)
                lock.withLock { failuresInRow = 0 }
                take(key)?.let { answer(it, r.bitmap) }
            }
            Made.Failed, Made.TooBig -> {
                lock.withLock {
                    failed.add(key)
                    if (r == Made.Failed) failuresInRow++
                }
                take(key)?.let { answer(it, null) }
            }
            Made.Interrupted -> Unit                  // stays queued: it goes on when the camera may be asked again
        }
    }

    private sealed class Made {
        class Picture(val bitmap: Bitmap) : Made()
        object Failed : Made()

        /** A photo too large to read for a thumbnail: not the camera's fault. */
        object TooBig : Made()

        /** The camera may no longer be asked (a download or a list started, the link went, thumbnails were paused). */
        object Interrupted : Made()
    }

    /**
     * Makes one picture. A guard closes the clip as soon as the camera may no longer be asked, or after 15 s: a
     * closed RemoteFile makes every read waiting inside the retriever fail at once.
     */
    private fun make(clip: Cam.Clip): Made {
        val photo = isPhoto(clip.name)
        if (photo && clip.size > PHOTO_LIMIT) return Made.TooBig
        val file = RemoteFile(clip.path, clip.size, sequential = false)
        val started = SystemClock.uptimeMillis()
        val interrupted = AtomicBoolean()
        val watch = guard.scheduleWithFixedDelay({
            if (!allowed()) {
                interrupted.set(true)
                file.close()
            } else if (SystemClock.uptimeMillis() - started > TIME_LIMIT_MS) {
                file.close()
            }
        }, GUARD_MS, GUARD_MS, TimeUnit.MILLISECONDS)
        return try {
            val bitmap = if (photo) photo(file, clip) else frame(file)
            when {
                bitmap != null -> Made.Picture(bitmap)
                interrupted.get() -> Made.Interrupted
                else -> Made.Failed
            }
        } catch (e: TooBigException) {
            Made.TooBig
        } catch (e: Exception) {
            if (interrupted.get() || !allowed()) Made.Interrupted else Made.Failed
        } catch (e: OutOfMemoryError) {
            Made.Failed
        } finally {
            watch.cancel(false)
            file.close()
        }
    }

    /** The key frame nearest to 1 s into the clip (or its middle, for a shorter one), scaled to the thumbnail size. */
    private fun frame(file: RemoteFile): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ClipSource(file))
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = minOf(1_000_000L, durationMs * 1000 / 2)
            return r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // released anyway
            }
        }
    }

    private class TooBigException : IOException("too large for a thumbnail")

    /** A photo read whole (it is small) and decoded at a fraction of its size. */
    private fun photo(file: RemoteFile, clip: Cam.Clip): Bitmap? {
        val size = if (clip.size > 0) clip.size else file.length()
        if (size > PHOTO_LIMIT) throw TooBigException()
        val bytes = ByteArray(size.toInt())
        var at = 0
        while (at < bytes.size) {
            val n = file.readAt(at.toLong(), bytes, at, bytes.size - at)
            if (n < 0) break
            at += n
        }
        if (at < bytes.size) throw IOException("the photo ended after $at of ${bytes.size} bytes")
        return decodeScaled(bytes, width, height)
    }

    /** Keeps [bitmap] in the disk cache, then trims the cache to its limit, oldest first. */
    private fun store(key: String, bitmap: Bitmap) {
        val d = dir ?: return
        val file = fileFor(key) ?: return
        val tmp = File(d, file.name + ".part")
        try {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!tmp.renameTo(file)) throw IOException("could not rename ${tmp.name}")
        } catch (e: IOException) {
            tmp.delete()
            return
        }
        if (diskBytes < 0) diskBytes = d.listFiles()?.sumOf { it.length() } ?: 0L else diskBytes += file.length()
        if (diskBytes <= DISK_BYTES) return
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_BYTES * 9 / 10) break
            val n = f.length()
            if (f.delete()) total -= n
        }
        diskBytes = total
    }

    /**
     * Decodes the JPEG in [bytes] at the largest power-of-two reduction that still covers [maxW] x [maxH], then
     * scales it to fit within that box. Null if it is not a picture.
     */
    private fun decodeScaled(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(maxW.toFloat() / decoded.width, maxH.toFloat() / decoded.height)
        if (scale >= 1f) return decoded
        val w = maxOf(1, (decoded.width * scale).roundToInt())
        val h = maxOf(1, (decoded.height * scale).roundToInt())
        val out = Bitmap.createScaledBitmap(decoded, w, h, true)
        if (out !== decoded) decoded.recycle()
        return out
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
