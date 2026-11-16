package com.dompka.dashlink

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A file on the camera's SD card, read at any position through HTTP Range requests on the camera link (Cam's
 * opener and read-only guard), for previewing and thumbnailing clips.
 *
 * All camera work is done by one reader thread per file ("dashlink-remote"): it waits for a turn at the camera
 * (Cam's gate), connects, reads, disconnects and gives the turn back. It has to be one thread, because the gate
 * is a ReentrantLock and only the thread that took it can give it back. [readAt] callers tell it which block
 * they need and wait. The reader holds the camera only while a transfer is open. Whenever [Cam.othersWaiting]
 * says a download, a file list or a thumbnail is queued, it finishes the block in progress and lets the camera
 * go; it queues up again only when a read needs data that is not in memory.
 *
 * With [sequential] set (playback) it keeps one open-ended "Range: bytes=N-" request and reads about 6 MB ahead
 * of the player into a 24 MB cache of 128 KB blocks, so that a player reading a few MB a second never waits
 * for the camera. A read the open request will not reach soon (one behind it, or more than 2 MB ahead) moves
 * it there. MediaPlayer reads a clip's head first, then its index, then plays from the start, and an MP4
 * written while recording keeps its index at the end. So a read like that in the file's last 2 MB gets a
 * request of its own, from there to the end of the file with an explicit end, and the blocks it brings are
 * never evicted: head, index and playback cost three requests instead of a new one at every turn.
 *
 * Otherwise (thumbnails) it fetches aligned 256 KB blocks with "bytes=a-b", keeps the last 8 MB, and reads
 * nothing ahead.
 *
 * Every reply must be 206 with a Content-Range that starts where it was asked to, and must deliver what it
 * announced. Bytes from anywhere else never reach the caller. If the camera answers with the whole file (200),
 * that answer is used for a request from byte 0 only; anywhere else it means [CannotSeekException].
 *
 * Do not call [readAt] or [length] inside Cam.session. The reader needs a turn at the camera of its own, so
 * such a read would wait for its own caller until the deadline. And always [close] it: until then the reader
 * thread keeps the file, and its cache, alive.
 */
internal class RemoteFile(val path: String, knownSize: Long, private val sequential: Boolean) : Closeable {

    /** A read (or [length]) waiting for [block]. [error] is set if fetching it failed. */
    private class Want(val block: Long) {
        var error: IOException? = null
    }

    private val blockSize = if (sequential) STREAM_BLOCK else RANDOM_BLOCK
    private val maxBlocks = (if (sequential) STREAM_CACHE else RANDOM_CACHE) / blockSize

    /** Guards the fields from here down to [reader]. Never held while talking to the camera. */
    private val mutex = ReentrantLock()
    private val changed = mutex.newCondition()

    /** Block number -> its bytes (a whole block; only the file's last one is shorter), least recently used first. */
    private val cache = LinkedHashMap<Long, ByteArray>()

    /** Blocks of the file's tail: never evicted (see the class comment). */
    private val pinned = HashSet<Long>()
    private val wants = ArrayList<Want>()

    /**
     * The file's size, -1 until known: from the list, else from the camera. Once known it does not change, so
     * every caller sees the same file (a player is told the size once, at the start). Only the reader thread
     * changes it, so the reader alone may also read it without [mutex].
     */
    private var fileSize = if (knownSize > 0) knownSize else -1L

    /** Where the player is expected to read next. Read-ahead runs from here. */
    private var anchor = 0L

    /**
     * No read-ahead until a read needs data that is not in memory. Set when the reader has given the camera up
     * to someone else, and when fetching failed: a camera that keeps failing is not asked again and again.
     */
    private var paused = false

    /** When the last read came in (System.nanoTime). */
    private var lastRead = 0L

    /** Counts every change the reader may have to act on, so that it never sleeps through one. */
    private var version = 0L

    private var reader: Thread? = null

    @Volatile
    private var closed = false

    /** The connection the reader has open, for [close] to break a read that is stuck. */
    @Volatile
    private var conn: HttpURLConnection? = null

    // ---------------------------------------------------------------- the reader thread's own state

    private var input: InputStream? = null

    /** The position of the next byte the open reply delivers. */
    private var at = 0L

    /** Where reading the open reply stops (exclusive). */
    private var until = 0L

    /** Where the open reply said its body ends (exclusive), -1 if it did not say. */
    private var announced = -1L

    /** The open reply is the open-ended playback stream, which stays open while the player is not reading. */
    private var stream = false

    /** The open reply fetches the file's tail: its blocks are pinned. */
    private var tail = false

    /** Transfers that broke part-way in a row, with no block coming in between (see [failed]). */
    private var strikes = 0

    /** Blocks read since this turn at the camera began: at least one before the camera is given up again. */
    private var turnBlocks = 0

    /** The reader has a turn at the camera. */
    private var holding = false

    /** The camera answered a ranged request with the whole file (200): nothing but byte 0 can be asked for. */
    private var noSeek = false

    /**
     * A block a reply ended short of, honestly (its Content-Range ended there: some servers cap a reply's size),
     * waiting for the rest from a follow-up request. Reader thread only. Never in the cache while partial, since
     * a read must never get 0 bytes.
     */
    private class Partial(val start: Long, val data: ByteArray, val filled: Int)

    private var partial: Partial? = null

    /** [version] when the reader last decided what to do. */
    private var seen = 0L

    /** The file's length (from the list, else from the first reply's Content-Range). Throws if it cannot be learned. */
    @Throws(IOException::class)
    fun length(): Long {
        mutex.withLock {
            if (fileSize >= 0) return fileSize
        }
        // The reply for the first block says how long the file is, and that block is the next one wanted anyway.
        readAt(0, ByteArray(1), 0, 1)
        mutex.withLock {
            if (fileSize >= 0) return fileSize
        }
        throw IOException("the camera did not say how long the clip is")
    }

    /**
     * Like android.media.MediaDataSource.readAt: copies up to [size] bytes from [position] into [buffer] at
     * [offset] and returns how many, or -1 at the end of the file. Never returns 0 for size > 0. Blocks at most
     * [deadlineMs]; throws IOException on failure, on time-out, and after [close].
     *
     * It waits only for the block that holds [position]; whatever directly follows it in memory comes along, so
     * a read may return fewer bytes than asked for.
     */
    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (offset < 0 || size < 0 || size > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset $offset, size $size, buffer ${buffer.size}")
        }
        if (position < 0) throw IOException("cannot read at $position")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
        mutex.withLock {
            var want: Want? = null
            try {
                while (true) {
                    if (closed) throw IOException("closed")
                    if (fileSize in 0..position) return -1
                    if (size == 0) return 0
                    if (cache.containsKey(position / blockSize)) {
                        val n = copy(position, buffer, offset, size)
                        noteRead(position + n)
                        return n
                    }
                    val now = System.nanoTime()
                    if (now - deadline >= 0) throw IOException("timed out waiting for the camera")
                    want?.error?.let { throw it }
                    if (want == null) {
                        want = Want(position / blockSize)
                        wants.add(want)
                        paused = false
                        noteRead(position)
                        startReader()
                    }
                    try {
                        changed.awaitNanos(deadline - now)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("interrupted while waiting for the camera")
                    }
                }
            } finally {
                if (want != null) {
                    wants.remove(want)
                    touched()
                }
            }
        }
    }

    /** How far the data already held in memory reaches from [position] (for a "buffered" indicator). */
    fun bufferedUntil(position: Long): Long = mutex.withLock {
        var p = position
        while (p >= 0 && (fileSize < 0 || p < fileSize)) {
            val block = p / blockSize
            val data = cache[block] ?: break
            val next = block * blockSize + data.size
            if (next <= p) break
            p = if (fileSize >= 0) minOf(next, fileSize) else next
        }
        p
    }

    /**
     * Stops any read in progress at once (callable from any thread) and frees the camera; later reads throw.
     * Waiting reads throw straight away. The open connection is dropped, which breaks a transfer that is under
     * way. Then this waits briefly for the reader to give its turn at the camera back, so that normally the
     * camera is free when this returns.
     */
    override fun close() {
        val thread = mutex.withLock {
            if (closed) return
            closed = true
            touched()
            reader
        }
        conn?.let { disconnect(it) }
        try {
            thread?.join(CLOSE_WAIT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------------------------------------------------------------- the callers' side (under mutex)

    /** Copies from the blocks in memory from [position] on, as far as they reach without a gap. */
    private fun copy(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        var done = 0
        while (done < size) {
            val p = position + done
            val block = p / blockSize
            val data = cache.remove(block) ?: break
            cache[block] = data                       // now the most recently used
            val from = (p - block * blockSize).toInt()
            var n = minOf(size - done, data.size - from)
            if (fileSize >= 0) n = minOf(n.toLong(), fileSize - p).toInt()
            if (n <= 0) break
            System.arraycopy(data, from, buffer, offset + done, n)
            done += n
        }
        return done
    }

    /** A read came in. Read-ahead, if any, follows it. */
    private fun noteRead(next: Long) {
        if (sequential) anchor = next
        lastRead = System.nanoTime()
        touched()
    }

    /** Something changed that the reader, or a waiting read, may have to act on. */
    private fun touched() {
        version++
        changed.signalAll()
    }

    private fun startReader() {
        if (reader != null) return
        reader = Thread({ run() }, "dashlink-remote").apply {
            isDaemon = true
            start()
        }
    }

    // ---------------------------------------------------------------- the reader thread

    private fun run() {
        try {
            while (true) {
                val target = mutex.withLock { decide() }
                if (target == STOP) break
                if (target == NOTHING) {
                    idle()
                    continue
                }
                if (holding && turnBlocks > 0 && Cam.othersWaiting()) {
                    giveWay()
                    continue
                }
                try {
                    if (!reaches(target)) {
                        drop()
                        if (!holding) {
                            takeTurn()
                            continue
                        }
                        if (!open(target)) continue
                    }
                    readBlock()
                } catch (e: IOException) {
                    failed(target, e)
                } catch (e: RuntimeException) {
                    // Whatever goes wrong with one reply fails that reply, not the reader: without the reader,
                    // every later read would wait for nothing until its deadline.
                    failed(target, IOException("the camera's reply could not be read (${Cam.describe(e)})", e))
                }
            }
        } finally {
            drop()
            release()
        }
    }

    /** Under [mutex]: the block to fetch next, or [NOTHING], or [STOP]. */
    private fun decide(): Long {
        seen = version
        if (closed) return STOP
        // Waiting reads come first, and among them one that the open reply is about to deliver anyway.
        var first = NOTHING
        for (w in wants) {
            if (w.error != null || cache.containsKey(w.block) || beyondEnd(w.block)) continue
            if (reaches(w.block)) return w.block
            if (first == NOTHING) first = w.block
        }
        if (first != NOTHING || !sequential || paused) return first
        for (b in anchor / blockSize..(anchor + AHEAD) / blockSize) {
            if (beyondEnd(b)) break
            if (!cache.containsKey(b)) return b
        }
        return NOTHING
    }

    private fun beyondEnd(block: Long) = fileSize >= 0 && block * blockSize >= fileSize

    /** True if the open reply will deliver [block] soon, without a new request. */
    private fun reaches(block: Long): Boolean {
        val p = block * blockSize
        return input != null && p >= at && p < until && p - at <= JUMP
    }

    /**
     * Nothing to fetch. An open playback stream is kept, since the player will soon want more, until someone
     * else waits for the camera or the player has stopped reading (paused). Anything else lets the camera go.
     */
    private fun idle() {
        if (holding) {
            val quiet = mutex.withLock { System.nanoTime() - lastRead > TimeUnit.MILLISECONDS.toNanos(QUIET_MS) }
            if (!stream || input == null || quiet) {
                drop()
                release()
            } else if (Cam.othersWaiting()) {
                giveWay()
            }
        }
        mutex.withLock {
            if (version == seen && !closed) {
                // Holding the camera, the reader has to look at othersWaiting() now and then; otherwise only a
                // change here can give it something to do.
                if (holding) changed.await(POLL_MS, TimeUnit.MILLISECONDS) else changed.await()
            }
        }
    }

    /** Someone else is queued for the camera: let them have it. A read that needs data queues up behind them. */
    private fun giveWay() {
        drop()
        release()
        mutex.withLock { paused = true }
    }

    /** Queues for the camera until it is this reader's turn, or until nothing needs fetching any more. */
    private fun takeTurn() {
        while (true) {
            if (Cam.beginTurn(TURN_SLICE_MS)) {
                holding = true
                turnBlocks = 0
                return
            }
            // A read that timed out meanwhile no longer needs the camera, and neither does a closed file.
            if (mutex.withLock { decide() } < 0) return
        }
    }

    private fun release() {
        if (holding) {
            holding = false
            Cam.endTurn()
        }
    }

    /**
     * Asks for the bytes from the start of [block] on: to the end of the file for playback, the file's tail
     * on its own (see the class comment), one block for thumbnails. False if the camera says the file ends
     * before [block].
     */
    private fun open(block: Long): Boolean {
        val blockStart = block * blockSize
        val carried = partial?.takeIf { it.start == blockStart }
        if (carried == null) partial = null
        val start = if (carried != null) blockStart + carried.filled else blockStart
        if (noSeek && start > 0) throw CannotSeekException()
        val toTail = sequential && fileSize > 0 && blockStart >= fileSize - TAIL
        val last = when {
            !sequential -> (if (fileSize >= 0) minOf(blockStart + blockSize, fileSize) else blockStart + blockSize) - 1
            toTail -> fileSize - 1
            else -> -1L
        }
        val c = Cam.openRange(path, start, last, timeoutMs())
        conn = c
        // close() may have looked for a connection to drop just before this one existed.
        if (closed) throw IOException("closed")
        // Where the reply says its body ends, if it says.
        val end = when (val code = c.responseCode) {
            206 -> {
                val header = c.getHeaderField("Content-Range")
                val range = Cam.contentRange(header)
                    ?: throw IOException("the camera did not say which part of the clip it sent")
                if (range === Cam.unreadable || range.start < 0 || range.end in 0 until range.start ||
                    (range.total >= 0 && maxOf(range.start, range.end) >= range.total)
                ) {
                    throw IOException("the camera sent a Content-Range that makes no sense: $header")
                }
                if (range.start != start) throw IOException("the camera sent bytes from ${range.start} instead of $start")
                learn(range.total)
                val length = c.contentLengthLong
                when {
                    range.end >= 0 -> range.end + 1
                    length >= 0 -> start + length
                    else -> -1L
                }
            }
            200 -> {
                // Range was ignored and the whole file is on its way. From the start that is just what was needed.
                noSeek = true
                if (start > 0) throw CannotSeekException()
                c.contentLengthLong.also { learn(it) }
            }
            416 -> {
                val total = Cam.contentRange(c.getHeaderField("Content-Range"))?.total ?: -1L
                if (fileSize < 0 && total in 0..start) {
                    // Nothing had said how long the file is. Now the camera has: it ends before this block.
                    learn(total)
                    drop()
                    return false
                }
                throw IOException("the camera has no bytes from $start in this clip (HTTP 416)")
            }
            else -> throw Cam.HttpException(code)
        }
        input = c.inputStream
        at = start
        until = when {
            last >= 0 -> last + 1
            fileSize >= 0 -> fileSize
            else -> Long.MAX_VALUE
        }
        announced = end
        stream = last < 0
        tail = toTail
        return true
    }

    /** Reads the next block of the open reply into memory. */
    private fun readBlock() {
        val ins = input ?: return
        // The rest of a block that an earlier reply ended short of, or a new block.
        val carried = partial?.takeIf { it.start + it.filled == at }
        partial = null
        val start = carried?.start ?: at
        var length = minOf(blockSize.toLong(), until - start)
        if (fileSize >= 0) length = minOf(length, fileSize - start)
        val data = carried?.data ?: ByteArray(length.toInt())
        var filled = carried?.filled ?: 0
        val before = filled
        while (filled < data.size) {
            val n = ins.read(data, filled, data.size - filled)
            if (n < 0) break
            filled += n
        }
        at = start + filled
        if (filled < data.size) {
            if (announced > at) throw IOException("the camera stopped sending at byte $at of $announced")
            if (fileSize >= 0 && announced == at && filled > before) {
                // Less than was asked for, but where the reply said it would end: ask for the rest at once.
                partial = Partial(start, data, filled)
                drop()
                return
            }
            if (fileSize >= 0) throw IOException("the camera's reply ended at byte $at, before byte ${start + data.size}")
            // Nothing has said how long the file is, and the reply ended where it meant to: so does the file.
            mutex.withLock {
                fileSize = at
                if (filled > 0) put(start / blockSize, data.copyOf(filled))
                touched()
            }
            drop()
            return
        }
        mutex.withLock {
            put(start / blockSize, data)
            touched()
        }
        strikes = 0
        turnBlocks++
        if (at >= until || at == fileSize) drop()
    }

    /** Under [mutex]: keeps [data] as [block] and drops the least recently used blocks the cache has no room for. */
    private fun put(block: Long, data: ByteArray) {
        cache.remove(block)
        cache[block] = data
        if (tail) pinned.add(block)
        val oldest = cache.keys.iterator()
        while (cache.size > maxBlocks && oldest.hasNext()) {
            if (oldest.next() !in pinned) oldest.remove()
        }
    }

    /** The camera said how long the file is. Believed only while nothing else has said so. */
    private fun learn(total: Long) {
        if (total < 0) return
        mutex.withLock {
            if (fileSize < 0) {
                fileSize = total
                touched()
            }
        }
    }

    /**
     * Fetching [target] went wrong. A transfer that broke part-way, after its reply had been accepted, is tried
     * once more with a new request: a link can drop for a moment, and a camera may well end a stream that was
     * left waiting. Anything else, or a second break before another block has come in, goes to the reads
     * waiting for [target], and read-ahead stops until a read needs data again.
     */
    private fun failed(target: Long, e: IOException) {
        val again = input != null && strikes == 0
        strikes = if (again) 1 else 0
        drop()
        mutex.withLock {
            if (closed || again) return
            for (w in wants) if (w.block == target && w.error == null) w.error = e
            paused = true
            touched()
        }
    }

    /** Closes the open reply, if any. */
    private fun drop() {
        input = null
        val c = conn ?: return
        conn = null
        disconnect(c)
    }

    private fun disconnect(c: HttpURLConnection) {
        try {
            c.disconnect()
        } catch (e: Exception) {
            // close() and the reader may both drop the same connection; whoever comes second finds it gone
        }
    }

    /**
     * A reply that sends nothing for as long as a read may wait is given up, and with it the turn at the camera.
     * close() relies on disconnect() to break a read that is under way; Android's HttpURLConnection (OkHttp)
     * does that by closing the socket. The JDK's disconnect() waits for the read to end instead, so in JVM tests
     * a transfer that stalls part-way keeps its turn, and close() waits, until this time-out.
     */
    private fun timeoutMs(): Int = deadlineMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /** Longest a single readAt may block. */
        @Volatile
        var deadlineMs = 8_000L

        private const val STREAM_BLOCK = 128 * 1024
        private const val RANDOM_BLOCK = 256 * 1024
        private const val STREAM_CACHE = 24 * 1024 * 1024
        private const val RANDOM_CACHE = 8 * 1024 * 1024

        /** How far playback reads ahead of the player. */
        private const val AHEAD = 6L * 1024 * 1024

        /** A read this far ahead of the open stream is reached by reading on; for anything further a new request is quicker. */
        private const val JUMP = 2L * 1024 * 1024

        /** The end of the file that a read gets on its own request (see the class comment). */
        private const val TAIL = 2L * 1024 * 1024

        /** A playback stream nobody has read from for this long is closed and the camera given back: the player is paused. */
        private const val QUIET_MS = 3_000L

        /** How often a reader that holds an idle stream looks whether someone is waiting for the camera. */
        private const val POLL_MS = 50L

        /** How long one wait for the camera lasts before the reader looks whether it is still needed. */
        private const val TURN_SLICE_MS = 100L

        /** How long close() waits for the reader to give the camera back. */
        private const val CLOSE_WAIT_MS = 300L

        private const val NOTHING = -1L
        private const val STOP = -2L
    }
}

/** The camera answered a request for part of the clip with the whole clip, so it cannot start anywhere but at the beginning. */
internal class CannotSeekException : IOException("the camera cannot seek in this clip")
