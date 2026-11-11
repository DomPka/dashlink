package com.dompka.dashlink

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.Date
import kotlin.system.exitProcess

/**
 * Runs once per process, before any screen or service: brings back the download queue of the previous
 * run and keeps a note of the last crash, so the Diagnostics report can say what happened.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, CRASH_FILE).writeText("${Date()} on thread ${thread.name}\n${Log.getStackTraceString(error)}")
            } catch (ignored: Throwable) {
                // the crash itself matters more than the note about it
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        try {
            DownloadService.restore(this)
        } catch (e: Exception) {
            // an unreadable saved queue must not stop the app from starting
        }
    }

    companion object {
        private const val CRASH_FILE = "last-crash.txt"

        /** The stack trace of the most recent crash, or null if there never was one. */
        fun lastCrash(ctx: Context): String? = try {
            File(ctx.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        }
    }
}
