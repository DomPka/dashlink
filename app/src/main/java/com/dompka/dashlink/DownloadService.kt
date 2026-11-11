package com.dompka.dashlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Works through the download queue as a foreground service, so it keeps going with the screen off.
 * A dropped link is not an error here: the job waits and continues from the byte it stopped at
 * (the rules for that are in Runner.kt; this class is the Android shell around them).
 */
class DownloadService : Service() {

    companion object {
        private const val NOTE_ID = 1
        private const val CHANNEL = "downloads"

        /** A wake lock is never held longer than this without being renewed by progress. */
        private const val WAKE_MS = 10 * 60 * 1000L

        /** The queue is being worked on right now. */
        @Volatile
        var working = false
            private set

        /** Why the service is not running in the foreground although it should be. Null when all is well. */
        @Volatile
        var foregroundError: String? = null
            private set

        /** Wake lock or Wi-Fi lock currently held (for tests and the diagnostics report). */
        @Volatile
        var locksHeld = false
            private set

        /** A service instance exists right now (between onCreate and onDestroy). */
        @Volatile
        var alive = false
            private set

        /** How many start commands the service has received in this process. */
        @Volatile
        var starts = 0
            private set

        /** One thread does all downloading and all file clean-up, in order. */
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dashlink-downloads") }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("dashlink", Context.MODE_PRIVATE)

        /** Loads the queue a previous run left behind. Call once, when the process starts. */
        fun restore(ctx: Context) {
            val app = ctx.applicationContext
            val p = prefs(app)
            Jobs.restore(p.getString("queue", null), p.getBoolean("working", false))
            p.edit().putBoolean("working", false).apply()
            Jobs.saver = { text -> prefs(app).edit().putString("queue", text).apply() }
        }

        /** Queues [clips] and gets the service going. Returns what Android said if it refused, else null. */
        fun enqueue(ctx: Context, clips: List<Cam.Clip>): String? {
            Jobs.add(clips)
            return start(ctx)
        }

        /**
         * Starts (or continues) work on the queue. Only call while the app is on screen: Android
         * does not let an app in the background start a foreground service.
         */
        fun start(ctx: Context): String? {
            Jobs.paused = null
            Jobs.pausedByLink = false
            Jobs.interrupted = false
            return try {
                ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
                null
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException, SecurityException, …
                Cam.describe(e)
            }
        }

        /** Stops everything. Half-downloaded files are deleted, also those of jobs that never got to run. */
        fun stopAll(ctx: Context) {
            Jobs.cancelAll()
            val app = ctx.applicationContext
            worker.execute {
                try {
                    tidy(MediaFiles(app))
                } catch (t: Throwable) {
                    // nothing to tidy with; the next run sweeps
                }
            }
        }

        /** Marks stopped-while-queued jobs as stopped and removes every unfinished file nobody will continue. */
        private fun tidy(files: MediaFiles) {
            for (j in Jobs.all) {
                if (j.cancel && j.state == Jobs.QUEUED) {
                    files.discard(j)
                    j.state = Jobs.CANCELLED
                }
            }
            files.sweep(Jobs.dests())
            Jobs.save()
        }

        fun savedNames(ctx: Context): Set<String> =
            prefs(ctx).getStringSet("saved", emptySet()) ?: emptySet()

        fun mb(bytes: Long): String = String.format(Locale.US, "%.0f", bytes / 1048576.0)
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastStartId = 0
    private var busy = false                    // a work() run is queued or running; main thread only
    @Volatile private var destroyed = false
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        starts++
        // First thing, always: Android allows only a few seconds between startForegroundService() and this.
        goForeground()
        if (!busy) {
            busy = true
            working = true
            worker.execute { work() }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
    }

    override fun onDestroy() {
        destroyed = true
        alive = false
        dropLocks()
        super.onDestroy()
    }

    private fun goForeground() {
        try {
            startForeground(NOTE_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foregroundError = null
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (started from the background), SecurityException
            // (a requirement of the "connected device" type is not met), … The downloads still run while
            // the app is on screen; the screen says that they are not protected.
            foregroundError = Cam.describe(e)
        }
    }

    private fun work() {
        try {
            prefs(this).edit().putBoolean("working", true).apply()
            holdLocks()
            val files = MediaFiles(this)
            tidy(files)
            val runner = Runner(
                source = Cam::download,
                store = files,
                clock = object : Runner.Clock {
                    override fun now(): Long = SystemClock.elapsedRealtime()
                    override fun sleep(ms: Long) = Thread.sleep(ms)
                },
                linkGone = { Link.gone },
                halt = { destroyed },
                changed = ::jobChanged
            )
            while (!destroyed && Jobs.paused == null) {
                val job = Jobs.next() ?: break
                when (runner.run(job)) {
                    Runner.Result.SAVED -> remember(job.clip.name)
                    Runner.Result.PAUSED -> if (!destroyed) {
                        Jobs.pausedByLink = true
                        Jobs.paused = "Downloads paused: the camera is out of reach. They continue when it is connected again."
                    }
                    Runner.Result.STORAGE -> {
                        Jobs.pausedByLink = false
                        Jobs.paused = "Downloads paused: " + (job.lastError ?: "the phone could not store the file") + "."
                    }
                    else -> Unit
                }
                Jobs.save()
            }
            tidy(files)
        } catch (t: Throwable) {
            // Whatever it was, it must not take the whole app down. The clips stay queued.
            Jobs.pausedByLink = false
            Jobs.paused = "Downloads stopped unexpectedly (${Cam.describe(t)})."
        } finally {
            dropLocks()
            try {
                prefs(this).edit().putBoolean("working", false).apply()
            } catch (t: Throwable) {
                // the flag only feeds a hint on the next start
            }
            // Hand back to the main thread, where onStartCommand also runs, so a job queued right now is not missed.
            main.post { handOver() }
        }
    }

    private fun handOver() {
        busy = false
        if (destroyed) {
            working = false
            return
        }
        if (Jobs.paused == null && Jobs.hasWork()) {
            busy = true
            worker.execute { work() }
            return
        }
        working = false
        // Not plain stopSelf(): if startForegroundService() was called a moment ago and its start has not
        // been delivered yet, stopping now would crash the app ("did not then call startForeground").
        // With the start id, Android ignores the stop in that case and onStartCommand runs next.
        stopSelfResult(lastStartId)
    }

    private fun remember(name: String) {
        val p = prefs(this)
        p.edit().putStringSet("saved", savedNames(this) + name).apply()
    }

    // ---------------------------------------------------------------- locks

    @Synchronized
    private fun holdLocks() {
        try {
            val w = wake ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashlink:downloads")
                .also {
                    it.setReferenceCounted(false)
                    wake = it
                }
            // Always with a time limit, renewed while there is progress: it cannot outlive a hung download.
            w.acquire(WAKE_MS)
        } catch (e: Exception) {
            // without it the download may stall with the screen off, but it still works with the screen on
        }
        try {
            @Suppress("DEPRECATION")
            val l = wifiLock ?: (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dashlink:downloads")
                .also {
                    it.setReferenceCounted(false)
                    wifiLock = it
                }
            l.acquire()
        } catch (e: Exception) {
            // same
        }
        locksHeld = true
    }

    @Synchronized
    private fun dropLocks() {
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // it had already timed out
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // already released
        }
        locksHeld = false
    }

    @Synchronized
    private fun renewWakeLock() {
        if (!locksHeld) return
        try {
            wake?.acquire(WAKE_MS)
        } catch (e: Exception) {
            // keep what we have
        }
    }

    // ---------------------------------------------------------------- notification

    /** Called from the download thread whenever a job moved on; about once a second while bytes flow. */
    private fun jobChanged(job: Job) {
        if (destroyed) return
        renewWakeLock()
        val name = job.clip.name
        val text = when (job.state) {
            Jobs.DOWNLOADING -> "$name  ${mb(job.done)}/${mb(job.total)} MB"
            Jobs.WAITING -> "$name  waiting for the camera…"
            Jobs.SAVING -> "$name  saving…"
            else -> return
        }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification(text))
        } catch (e: Exception) {
            // a missed progress update is not worth a crash
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DashLink")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
