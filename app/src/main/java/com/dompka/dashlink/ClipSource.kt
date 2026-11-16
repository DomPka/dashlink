package com.dompka.dashlink

import android.media.MediaDataSource
import java.io.IOException

/**
 * A clip on the camera as Android's media classes want to read it (MediaPlayer, MediaMetadataRetriever): every
 * read goes to [file], which fetches it over HTTP Range on the camera link. The media framework calls these
 * methods on its own threads, never inside Cam.session, which is what RemoteFile needs.
 *
 * Closing this source closes [file]; the framework does that when the player or retriever is released.
 */
internal class ClipSource(private val file: RemoteFile) : MediaDataSource() {

    /** Blocks until the camera has sent the bytes (at most RemoteFile.deadlineMs), throws IOException otherwise. */
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        file.readAt(position, buffer, offset, size)

    /** The clip's length, or -1 when even the camera cannot say (the framework then reads until the end). */
    override fun getSize(): Long = try {
        file.length()
    } catch (e: IOException) {
        -1L
    }

    override fun close() = file.close()
}
