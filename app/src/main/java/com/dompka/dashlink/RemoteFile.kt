package com.dompka.dashlink

import java.io.Closeable
import java.io.IOException

/*
 * CONTRACT (to be implemented): a file on the camera's SD card, read at any position through HTTP Range
 * requests on the camera link (Cam's opener and read-only guard), for previewing and thumbnailing clips.
 *
 * Every request takes its turn at the camera (Cam's gate) on one internal reader thread, and gives the camera
 * up when [Cam.othersWaiting] says something else needs it. With [sequential] set (playback), it keeps one
 * open-ended "Range: bytes=N-" connection and reads ahead; otherwise (thumbnails) it fetches aligned blocks
 * with "bytes=a-b". It verifies 206 + Content-Range on every reply and never trusts a reply that starts
 * elsewhere.
 */
internal class RemoteFile(val path: String, knownSize: Long, private val sequential: Boolean) : Closeable {

    /** The file's length (from the list, else from the first reply's Content-Range). Throws if it cannot be learned. */
    @Throws(IOException::class)
    fun length(): Long = TODO("contract")

    /**
     * Like android.media.MediaDataSource.readAt: copies up to [size] bytes from [position] into [buffer] at
     * [offset] and returns how many, or -1 at the end of the file. Never returns 0 for size > 0. Blocks at most
     * [deadlineMs]; throws IOException on failure, on time-out, and after [close].
     */
    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = TODO("contract")

    /** How far the data already held in memory reaches from [position] (for a "buffered" indicator). */
    fun bufferedUntil(position: Long): Long = position

    /** Stops any read in progress at once (callable from any thread) and frees the camera; later reads throw. */
    override fun close() {
        TODO("contract")
    }

    companion object {
        /** Longest a single readAt may block. */
        @Volatile
        var deadlineMs = 8_000L
    }
}
