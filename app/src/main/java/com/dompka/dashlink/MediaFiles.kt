package com.dompka.dashlink

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Files on the phone. A clip is downloaded straight into its final place in shared storage
 * (Movies/DashLink, photos in Pictures/DashLink) as a "pending" MediaStore item: hidden from the
 * Gallery until it is complete, then made visible in one step. No second copy is ever made, so a clip
 * needs its own size in free space and nothing more, and an unfinished clip can be continued later -
 * even by a new process, because its address is saved with the queue (Job.dest).
 */
internal class MediaFiles(private val ctx: Context) : Runner.Store {

    private class Place(val collection: Uri, val dir: String, val mime: String?)

    private val resolver: ContentResolver get() = ctx.contentResolver

    private val videos get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val images get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val movieDir = Environment.DIRECTORY_MOVIES + "/DashLink"
    private val pictureDir = Environment.DIRECTORY_PICTURES + "/DashLink"
    private val otherDir = Environment.DIRECTORY_DOWNLOADS + "/DashLink"

    private fun place(name: String): Place {
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith(".mp4") -> Place(videos, movieDir, "video/mp4")
            lower.endsWith(".mov") -> Place(videos, movieDir, "video/quicktime")
            lower.endsWith(".ts") -> Place(videos, movieDir, "video/mp2t")
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> Place(images, pictureDir, "image/jpeg")
            else -> Place(downloads, otherDir, null)
        }
    }

    override fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE / 2          // unknown: let the write itself find out
    }

    override fun has(clip: Cam.Clip): Boolean {
        try {
            val p = place(clip.name)
            val where = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            resolver.query(
                p.collection, arrayOf(MediaStore.MediaColumns._ID), where,
                arrayOf(clip.name, clip.size.toString(), p.dir + "%"), null
            )?.use { return it.count > 0 }
        } catch (e: Exception) {
            // cannot tell: download it
        }
        return false
    }

    private enum class Row { PENDING, PUBLISHED, GONE }

    /**
     * What has become of an item this app created. Throws if MediaStore cannot be asked right now -
     * "could not ask" must never be mistaken for "gone", or a good part would be thrown away.
     */
    private fun row(uri: Uri): Row {
        val args = Bundle()
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        val cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), args, null)
            ?: throw Cam.StorageException("shared storage did not answer")
        cursor.use {
            if (!it.moveToFirst()) return Row.GONE
            return if (it.getInt(0) == 1) Row.PENDING else Row.PUBLISHED
        }
    }

    override fun published(job: Job): Boolean {
        val uri = job.dest?.let { Uri.parse(it) } ?: return false
        val done = try {
            row(uri) == Row.PUBLISHED
        } catch (e: Exception) {
            false               // open() will ask again and report the trouble properly
        }
        if (done) {
            job.dest = null
            Jobs.save()
        }
        return done
    }

    private fun create(name: String): Uri {
        val p = place(name)
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, p.dir)
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        if (p.mime != null) values.put(MediaStore.MediaColumns.MIME_TYPE, p.mime)
        return resolver.insert(p.collection, values) ?: throw Cam.StorageException("could not create $name in shared storage")
    }

    private fun openForWriting(uri: Uri): Runner.Out {
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw Cam.StorageException("could not open $uri")
        try {
            return FdOut(pfd)
        } catch (e: Exception) {
            try {
                pfd.close()
            } catch (ignored: Exception) {
                // nothing more to do
            }
            throw e
        }
    }

    override fun open(job: Job): Runner.Out {
        val name = job.clip.name
        try {
            var old = job.dest?.let { Uri.parse(it) }
            if (old != null && (old.scheme != ContentResolver.SCHEME_CONTENT || old.authority != MediaStore.AUTHORITY)) {
                // Not an address MediaStore ever gave out (a damaged saved queue): there is nothing to continue.
                old = null
                job.dest = null
            }
            if (old != null) {
                when (row(old)) {
                    Row.PENDING -> try {
                        return openForWriting(old)
                    } catch (e: Exception) {
                        // The entry is there but its file cannot be written: replace it below.
                        discard(job)
                    }
                    // Already a finished file (see published()): it is not this job's to overwrite or delete.
                    Row.PUBLISHED -> {
                        job.dest = null
                        Jobs.save()
                    }
                    // Expired after a week, or removed by the user: start this clip over.
                    Row.GONE -> {
                        job.dest = null
                        Jobs.save()
                    }
                }
            }
            val uri = create(name)
            job.dest = uri.toString()
            Jobs.save()
            return openForWriting(uri)
        } catch (e: Cam.StorageException) {
            throw e
        } catch (e: Exception) {
            throw Cam.StorageException("could not store $name on the phone (${Cam.describe(e)})", e)
        }
    }

    override fun publish(job: Job) {
        val name = job.clip.name
        val uri = job.dest?.let { Uri.parse(it) } ?: throw Cam.StorageException("$name has no file to publish")
        try {
            val ready = ContentValues()
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, ready, null, null) < 1) throw Cam.StorageException("could not publish $name")
        } catch (e: Cam.StorageException) {
            throw e
        } catch (e: Exception) {
            throw Cam.StorageException("could not publish $name (${Cam.describe(e)})", e)
        }
        job.dest = null
        Jobs.save()
    }

    override fun discard(job: Job) {
        val uri = job.dest?.let { Uri.parse(it) } ?: return
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            // sweep() gets it next time
        }
        job.dest = null
        Jobs.save()
    }

    /**
     * Deletes this app's unfinished files that no queued job will continue: left by a crash, by Android
     * killing the app, or by jobs stopped before they ever ran. Only pending items inside the DashLink
     * folders are touched, and MediaStore only ever shows this app its own. Returns how many were removed.
     */
    fun sweep(keep: Set<String>): Int {
        // Compared by row number, not by the spelling of the address.
        val keepIds = HashSet<Long>()
        for (k in keep) {
            try {
                keepIds.add(ContentUris.parseId(Uri.parse(k)))
            } catch (e: Exception) {
                // not one of ours
            }
        }
        var removed = 0
        for ((collection, dir) in listOf(videos to movieDir, images to pictureDir, downloads to otherDir)) {
            try {
                val args = Bundle()
                args.putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                )
                args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$dir%"))
                args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                val stale = ArrayList<Uri>()
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id !in keepIds) stale.add(ContentUris.withAppendedId(collection, id))
                    }
                }
                for (uri in stale) {
                    try {
                        if (resolver.delete(uri, null, null) > 0) removed++
                    } catch (e: Exception) {
                        // next time
                    }
                }
            } catch (e: Exception) {
                // shared storage is not available right now
            }
        }
        // Version 0.1 kept unfinished files in the app's own folder; nothing reads them any more.
        try {
            ctx.getExternalFilesDir(null)?.let { File(it, "partial").deleteRecursively() }
        } catch (e: Exception) {
            // not important
        }
        return removed
    }

    /** A seekable file handed out by MediaStore. */
    private class FdOut(pfd: ParcelFileDescriptor) : Runner.Out {
        private val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        private val channel = stream.channel

        init {
            channel.position(channel.size())
        }

        override fun length(): Long = channel.size()

        override fun truncate(length: Long) {
            channel.truncate(length)
            channel.position(length)
        }

        override fun append(buf: ByteArray, count: Int) {
            val bytes = ByteBuffer.wrap(buf, 0, count)
            while (bytes.hasRemaining()) channel.write(bytes)
        }

        override fun close() {
            try {
                channel.force(false)
            } catch (e: Exception) {
                // closing matters more than syncing
            }
            stream.close()
        }
    }
}
