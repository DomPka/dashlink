package com.dompka.dashlink

import java.util.concurrent.CopyOnWriteArrayList

/** One clip on its way to the phone. Written by the download thread, read by the screen. */
class Job(val clip: Cam.Clip) {
    @Volatile var done = 0L
    @Volatile var total = clip.size
    @Volatile var state = Jobs.QUEUED
    @Volatile var cancel = false
    @Volatile var bytesPerSec = 0L
    @Volatile var lastError: String? = null

    /**
     * Where the unfinished file is kept (a MediaStore address). It is saved with the queue, so a later
     * run - even after Android killed the app - continues the same file instead of starting over.
     */
    @Volatile var dest: String? = null
}

/**
 * The download queue. It lives as long as the process and is written to disk after every change, so
 * clips that were queued when the app was killed are still queued when it comes back.
 * No Android classes in here: the service plugs in [saver].
 */
object Jobs {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val WAITING = "waiting for camera"
    const val SAVING = "saving"
    const val SAVED = "saved"
    const val FAILED = "failed"
    const val CANCELLED = "stopped"

    /** Finished jobs kept for the screen and the diagnostics report. */
    private const val KEEP_FINISHED = 300

    val all = CopyOnWriteArrayList<Job>()

    /** Why the queue stopped by itself although clips are still waiting (camera gone, phone full). Null while it runs. */
    @Volatile var paused: String? = null

    /** The pause is only about the camera being out of reach, so it may end by itself when the camera is back. */
    @Volatile var pausedByLink = false

    /** The last run of the app ended while downloads were in progress (Android stopped it, or it crashed). */
    @Volatile var interrupted = false

    /** Writes the text from [encode] somewhere that survives the process. */
    @Volatile var saver: ((String) -> Unit)? = null

    fun isOpen(j: Job) = j.state == QUEUED || j.state == DOWNLOADING || j.state == WAITING || j.state == SAVING

    /** Not finished and not told to stop. */
    fun isWanted(j: Job) = isOpen(j) && !j.cancel

    /** Adds [clips] that are not already on their way. Returns how many were added. */
    @Synchronized
    fun add(clips: List<Cam.Clip>): Int {
        var added = 0
        for (c in clips) {
            if (all.none { it.clip.path == c.path && isWanted(it) }) {
                all.add(Job(c))
                added++
            }
        }
        trim()
        save()
        return added
    }

    fun next(): Job? = all.firstOrNull { it.state == QUEUED && !it.cancel }

    fun hasWork(): Boolean = next() != null

    /** Tells every unfinished job to stop. The download thread does the rest. */
    fun cancelAll() {
        for (j in all) if (isOpen(j)) j.cancel = true
        paused = null
        pausedByLink = false
        interrupted = false
        save()
    }

    /** Files that some unfinished job will still continue. Everything else left half-done is rubbish. */
    fun dests(): Set<String> = all.filter { isWanted(it) }.mapNotNull { it.dest }.toSet()

    @Synchronized
    private fun trim() {
        val finished = all.filter { !isOpen(it) }
        if (finished.size > KEEP_FINISHED) all.removeAll(finished.take(finished.size - KEEP_FINISHED).toSet())
    }

    // ---------------------------------------------------------------- saved copy

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i >= s.length) {
                sb.append(c)
                continue
            }
            when (val n = s[i++]) {
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                else -> sb.append(n)
            }
        }
        return sb.toString()
    }

    /** One line per unfinished job: path, size, time, resolution, seconds, unfinished file. */
    fun encode(): String = all.filter { isWanted(it) }.joinToString("\n") { j ->
        listOf(j.clip.path, j.clip.size.toString(), j.clip.time, j.clip.res, j.clip.secs, j.dest ?: "").joinToString("\t") { esc(it) }
    }

    /** The reverse of [encode]. Lines that make no sense are skipped, never fatal. */
    fun decode(text: String?): List<Job> {
        val out = ArrayList<Job>()
        if (text.isNullOrEmpty()) return out
        for (line in text.split('\n')) {
            val f = line.split('\t').map { unesc(it) }
            if (f.size < 6 || f[0].isEmpty() || f[0].substringAfterLast('/').isEmpty()) continue
            val size = f[1].toLongOrNull() ?: continue
            val job = Job(Cam.Clip(f[0], size, f[2], f[3], f[4]))
            job.dest = f[5].ifEmpty { null }
            if (out.none { it.clip.path == job.clip.path }) out.add(job)
        }
        return out
    }

    /** One writer at a time, so an older picture of the queue can never overwrite a newer one. */
    @Synchronized
    fun save() {
        val s = saver ?: return
        try {
            s(encode())
        } catch (e: Exception) {
            // a queue that could not be written is still a queue; it is only lost if the app is killed
        }
    }

    /** Puts the jobs from a previous run back in the queue. [wasWorking] says that run died mid-download. */
    @Synchronized
    fun restore(text: String?, wasWorking: Boolean) {
        val old = decode(text).filter { o -> all.none { it.clip.path == o.clip.path && isWanted(it) } }
        all.addAll(old)
        if (wasWorking && old.isNotEmpty()) interrupted = true
    }
}
