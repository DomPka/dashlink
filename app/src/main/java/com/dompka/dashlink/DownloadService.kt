package com.dompka.dashlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Works through the download queue as a foreground service, so it keeps going with the screen off.
 * A dropped link is not an error here: the job waits and continues from the byte it stopped at.
 */
class DownloadService : Service() {

    class Job(val clip: Cam.Clip) {
        @Volatile var done = 0L
        @Volatile var state = QUEUED
        @Volatile var cancel = false
        @Volatile var bytesPerSec = 0L
        @Volatile var lastError: String? = null
    }

    companion object {
        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        const val WAITING = "waiting for camera"
        const val SAVING = "saving"
        const val SAVED = "saved"
        const val FAILED = "failed"
        const val CANCELLED = "stopped"

        val jobs = CopyOnWriteArrayList<Job>()

        private fun open(j: Job) = j.state == QUEUED || j.state == DOWNLOADING || j.state == WAITING || j.state == SAVING

        fun enqueue(ctx: Context, clips: List<Cam.Clip>) {
            for (c in clips) {
                if (jobs.none { it.clip.path == c.path && open(it) }) jobs.add(Job(c))
            }
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        }

        fun stopAll() {
            for (j in jobs) if (open(j)) j.cancel = true
        }

        fun savedNames(ctx: Context): Set<String> =
            ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE).getStringSet("saved", emptySet()) ?: emptySet()

        fun mb(bytes: Long): String = String.format(Locale.US, "%.0f", bytes / 1048576.0)
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (worker == null) startWorker()
        return START_NOT_STICKY
    }

    private fun next(): Job? = jobs.firstOrNull { it.state == QUEUED && !it.cancel }

    private fun startWorker() {
        worker = thread(name = "dashlink-downloads") { work() }
    }

    private fun work() {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashlink:downloads")
        @Suppress("DEPRECATION")
        val wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dashlink:downloads")
        wake.acquire(6 * 60 * 60 * 1000L)
        wifiLock.acquire()
        try {
            while (true) {
                val job = next() ?: break
                runJob(job)
            }
        } finally {
            if (wifiLock.isHeld) wifiLock.release()
            if (wake.isHeld) wake.release()
            // Hand back to the main thread, where onStartCommand also runs, so a job queued right now is not missed.
            main.post {
                worker = null
                if (next() != null) {
                    startWorker()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun runJob(job: Job) {
        val dir = File(getExternalFilesDir(null), "partial")
        dir.mkdirs()
        val tmp = File(dir, job.clip.name)
        var stalls = 0
        var markTime = SystemClock.elapsedRealtime()
        var markBytes = if (tmp.exists()) tmp.length() else 0L
        while (!job.cancel) {
            val before = if (tmp.exists()) tmp.length() else 0L
            try {
                job.state = DOWNLOADING
                Cam.download(job.clip, tmp, { job.cancel }) { have, total ->
                    job.done = have
                    val now = SystemClock.elapsedRealtime()
                    if (now - markTime >= 1000) {
                        job.bytesPerSec = (have - markBytes) * 1000 / (now - markTime)
                        markTime = now
                        markBytes = have
                        notify("${job.clip.name}  ${mb(have)}/${mb(total)} MB")
                    }
                }
                job.state = SAVING
                publish(tmp, job.clip.name)
                val prefs = getSharedPreferences("dashlink", Context.MODE_PRIVATE)
                prefs.edit().putStringSet("saved", savedNames(this) + job.clip.name).apply()
                job.done = job.clip.size.coerceAtLeast(job.done)
                job.state = SAVED
                return
            } catch (e: Exception) {
                if (job.cancel) break
                job.lastError = e.javaClass.simpleName + ": " + e.message
                job.bytesPerSec = 0
                // Give up only after ~10 minutes with no new bytes at all.
                val after = if (tmp.exists()) tmp.length() else 0L
                stalls = if (after > before) 0 else stalls + 1
                if (stalls > 200) {
                    job.state = FAILED
                    return
                }
                job.state = WAITING
                notify("${job.clip.name}  waiting for the camera…")
                try {
                    Thread.sleep(3000)
                } catch (ie: InterruptedException) {
                    break
                }
                markTime = SystemClock.elapsedRealtime()
                markBytes = after
            }
        }
        job.state = CANCELLED      // the partial file stays, so queuing the clip again resumes it
    }

    /** Moves a finished file into the shared Movies/Pictures folder so the Gallery sees it. */
    private fun publish(tmp: File, name: String) {
        val lower = name.lowercase(Locale.US)
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val collection = when {
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".ts") -> {
                values.put(MediaStore.MediaColumns.MIME_TYPE, if (lower.endsWith(".ts")) "video/mp2t" else "video/mp4")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DashLink")
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> {
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DashLink")
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            else -> {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DashLink")
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        }
        val uri = contentResolver.insert(collection, values) ?: throw IOException("could not create $name in shared storage")
        try {
            val out = contentResolver.openOutputStream(uri) ?: throw IOException("could not open $name in shared storage")
            out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 20) } }
            val ready = ContentValues()
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, ready, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        tmp.delete()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("downloads") == null) {
            nm.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DashLink")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}
