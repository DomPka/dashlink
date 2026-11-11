package com.dompka.dashlink

import java.io.Closeable

/**
 * Sees one job through: start or continue the file, wait out a dropped link, and know when to stop
 * trying. The camera, the phone's storage and the clock are passed in, so every one of these decisions
 * can be tested on a desk (RunnerTest) - including the ones that take ten minutes on a real clock.
 */
internal class Runner(
    private val source: Source,
    private val store: Store,
    private val clock: Clock,
    /** What the camera link is doing right now. */
    private val link: () -> LinkState,
    /** The whole queue should stop now (the service is going away); the job stays queued. */
    private val halt: () -> Boolean,
    /** Called when a job's state or progress changed (at most about once a second while downloading). */
    private val changed: (Job) -> Unit
) {
    fun interface Source {
        fun download(clip: Cam.Clip, sink: Cam.Sink, cancelled: () -> Boolean, progress: (have: Long, total: Long) -> Unit)
    }

    /** The phone side: where unfinished and finished files live. Failures are Cam.StorageException. */
    interface Store {
        fun freeBytes(): Long

        /** This exact clip is already saved on the phone. */
        fun has(clip: Cam.Clip): Boolean

        /**
         * True if job.dest already is a finished, visible file: the app was stopped after publishing it
         * and before it could write that down. The file is kept and job.dest is cleared.
         */
        fun published(job: Job): Boolean

        /** Opens the job's unfinished file, creating it on the first call and remembering it in job.dest. */
        fun open(job: Job): Out

        /** Turns the finished file into a normal, visible one. */
        fun publish(job: Job)

        /** Removes the job's unfinished file, if there is one. Never throws. */
        fun discard(job: Job)
    }

    interface Out : Cam.Sink, Closeable

    interface Clock {
        fun now(): Long
        fun sleep(ms: Long)
    }

    enum class Result {
        SAVED,
        CANCELLED,
        /** This clip cannot be had (the camera no longer has it, or keeps refusing). The queue goes on. */
        FAILED,
        /** The camera is out of reach for now. The clip stays queued; the queue stops until someone resumes it. */
        PAUSED,
        /** The phone cannot store it (full, shared storage unavailable). The clip stays queued; the queue stops. */
        STORAGE
    }

    fun run(job: Job): Result {
        var restarts = 0                        // attempts that had to start this file over from byte 0
        var refusals = 0                        // answers in a row that brought nothing
        var high = 0L                           // most bytes on the phone since the file was last started over
        var lastByteAt = clock.now()            // when the camera last delivered a new byte
        var silentSince = lastByteAt            // the same, but counted afresh whenever the link itself was down
        while (true) {
            if (job.cancel) return cancelled(job)
            if (halt()) return requeue(job, Result.PAUSED)
            var out: Out? = null
            var startedOver = false             // this attempt threw away what was on the phone and began at byte 0
            try {
                if (job.dest != null && store.published(job)) {
                    if (job.clip.size > job.done) job.done = job.clip.size
                    return finish(job, Jobs.SAVED, Result.SAVED)
                }
                if (job.dest == null && job.clip.size > 0 && store.has(job.clip)) {
                    job.done = job.clip.size
                    return finish(job, Jobs.SAVED, Result.SAVED)
                }
                val file = store.open(job)
                out = file
                val have = file.length()
                val need = if (job.clip.size > have) job.clip.size - have else 0L
                val free = store.freeBytes()
                if (free < need + SPARE_BYTES) {
                    throw Cam.StorageException(
                        "not enough free space on the phone: ${mb(need)} MB needed, ${mb(free)} MB free"
                    )
                }
                job.done = have
                high = have
                job.state = Jobs.DOWNLOADING
                changed(job)
                var markTime = clock.now()
                var markBytes = have
                // The download writes through this, so that the file being emptied is seen the moment it
                // happens - whether the camera rejected the resume point or simply ignored it.
                val sink = object : Cam.Sink {
                    override fun length(): Long = file.length()
                    override fun append(buf: ByteArray, count: Int) = file.append(buf, count)
                    override fun truncate(length: Long) {
                        if (length < file.length()) {
                            startedOver = true
                            high = length
                            markBytes = length
                            markTime = clock.now()
                        }
                        file.truncate(length)
                    }
                }
                source.download(job.clip, sink, { job.cancel || halt() }) { got, total ->
                    job.done = got
                    if (total > 0) job.total = total
                    val now = clock.now()
                    if (got > high) {
                        high = got
                        lastByteAt = now
                        silentSince = now
                        refusals = 0
                    }
                    if (now - markTime >= 1000) {
                        job.bytesPerSec = (got - markBytes).coerceAtLeast(0) * 1000 / (now - markTime)
                        markTime = now
                        markBytes = got
                        changed(job)
                    }
                }
                out = null
                try {
                    file.close()
                } catch (e: Exception) {
                    throw Cam.StorageException("could not finish writing the file on the phone (${Cam.describe(e)})", e)
                }
                job.bytesPerSec = 0
                job.state = Jobs.SAVING
                changed(job)
                store.publish(job)
                if (job.clip.size > job.done) job.done = job.clip.size
                return finish(job, Jobs.SAVED, Result.SAVED)
            } catch (e: Exception) {
                closeQuietly(out)
                job.bytesPerSec = 0
                if (job.cancel) return cancelled(job)
                if (e is Cam.CancelledException || halt()) return requeue(job, Result.PAUSED)
                job.lastError = Cam.describe(e)
                val now = clock.now()
                if (startedOver || e is Cam.RestartException) {
                    // Downloading the file again from the top is progress, not silence - but see the limit below.
                    restarts++
                    silentSince = now
                }
                when (e) {
                    // The phone cannot store it. Asking the camera again would not help: stop the queue.
                    is Cam.StorageException -> return requeue(job, Result.STORAGE)
                    // Not a path this app will ever ask a camera for. No waiting changes that.
                    is Cam.RefusedException -> return fail(job)
                    // The part on the phone was unusable and has been emptied: try again at once.
                    is Cam.RestartException -> if (restarts <= MAX_RESTARTS) continue
                    is Cam.HttpException -> {
                        // 4xx: the camera does not have this file (any more). Waiting will not change that either.
                        val gone = e.code in 400..499 && e.code != 408 && e.code != 429
                        if (gone || ++refusals > MAX_REFUSALS) return fail(job)
                    }
                    // The camera answers but has nothing (more) of this file to give.
                    is Cam.NoDataException -> if (++refusals > MAX_REFUSALS) return fail(job)
                }
                // A camera that makes every attempt start the file over (it cannot resume, and the link keeps
                // dropping) would otherwise download the same clip for ever.
                if (restarts > MAX_RESTARTS) {
                    job.lastError = "started over $restarts times without finishing (${job.lastError})"
                    return fail(job)
                }
                // Anything else is the link. A dropped link is not an error: wait, then continue from the byte
                // it stopped at. How long to wait depends on what the link is doing:
                //  - nobody is trying to bring it back (disconnected, or it gave up): stop now;
                //  - it is being re-established: that has its own limit (see LinkCore), so wait for it;
                //  - it is up but the camera sends nothing new: give it AWAY_MS, then stop;
                //  - and whatever the link does, STALL_MS without a single new byte is enough.
                if (now - lastByteAt >= STALL_MS) return requeue(job, Result.PAUSED)
                when (link()) {
                    LinkState.IDLE, LinkState.NOT_FOUND -> return requeue(job, Result.PAUSED)
                    LinkState.LOST, LinkState.SEARCHING -> silentSince = now
                    LinkState.CONNECTED -> if (now - silentSince >= AWAY_MS) return requeue(job, Result.PAUSED)
                }
                job.state = Jobs.WAITING
                changed(job)
                try {
                    clock.sleep(RETRY_MS)
                } catch (ie: InterruptedException) {
                    return requeue(job, Result.PAUSED)
                }
            }
        }
    }

    private fun finish(job: Job, state: String, result: Result): Result {
        job.bytesPerSec = 0
        job.lastError = null
        job.state = state
        changed(job)
        return result
    }

    /** Back in the queue with whatever it has so far; [job.lastError] says why. */
    private fun requeue(job: Job, result: Result): Result {
        job.bytesPerSec = 0
        job.state = Jobs.QUEUED
        changed(job)
        return result
    }

    private fun fail(job: Job): Result {
        store.discard(job)
        job.bytesPerSec = 0
        job.state = Jobs.FAILED
        changed(job)
        return Result.FAILED
    }

    private fun cancelled(job: Job): Result {
        store.discard(job)          // nothing half-downloaded is left behind after Stop
        job.bytesPerSec = 0
        job.state = Jobs.CANCELLED
        changed(job)
        return Result.CANCELLED
    }

    private fun closeQuietly(out: Out?) {
        try {
            out?.close()
        } catch (e: Exception) {
            // it is being abandoned anyway
        }
    }

    private fun mb(bytes: Long) = bytes / 1_048_576

    companion object {
        const val RETRY_MS = 3_000L

        /** Stop the queue when the camera has been connected this long without a single new byte arriving. */
        const val AWAY_MS = 10 * 60 * 1000L

        /** Stop the queue when no new byte has arrived for this long, however often the link came and went. */
        const val STALL_MS = 30 * 60 * 1000L

        /** How often one clip may be started over from its first byte. */
        const val MAX_RESTARTS = 3

        /** Answers in a row that bring nothing ("500", an empty reply) before a clip is given up. */
        const val MAX_REFUSALS = 10

        /** Never fill the phone to the last byte. */
        const val SPARE_BYTES = 64L * 1024 * 1024
    }
}
