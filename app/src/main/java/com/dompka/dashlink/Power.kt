package com.dompka.dashlink

import android.app.Activity
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimisation. Samsung in particular stops apps in the background sooner than stock Android,
 * even with a foreground service, unless the app is exempt ("Unrestricted" in the app's Battery settings).
 * This object only detects the situation and opens the system's own screens; it changes nothing itself.
 */
object Power {
    /** Android (and Samsung's device care) leaves this app alone in the background. */
    fun exempt(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        true                // cannot tell: do not nag
    }

    /**
     * The app is set to "Restricted" (Samsung: "deep sleeping"). Android then quietly takes the foreground
     * state away from the download service as soon as the app leaves the screen.
     */
    fun restricted(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
    } catch (e: Exception) {
        false
    }

    /** How eagerly Android limits this app right now (5 = active … 45 = restricted), or -1. */
    fun standbyBucket(ctx: Context): Int = try {
        (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
    } catch (e: Exception) {
        -1
    }

    /** Opens the system's "let this app run in the background?" question, or the nearest screen that exists. */
    fun askForExemption(activity: Activity): Boolean {
        val pkg = Uri.parse("package:" + activity.packageName)
        val tries = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        )
        for (intent in tries) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: Exception) {
                // this phone does not have that screen: try the next one
            }
        }
        return false
    }

    /** Opens this app's page in Settings (permissions, battery). */
    fun openAppSettings(activity: Activity): Boolean = try {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))
        )
        true
    } catch (e: Exception) {
        false
    }

    /** Why Android ended this app the last few times, newest first - for the diagnostics report. */
    fun lastExits(ctx: Context, max: Int = 5): List<String> = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getHistoricalProcessExitReasons(ctx.packageName, 0, max).map { e ->
            val ago = (System.currentTimeMillis() - e.timestamp) / 60000
            "$ago min ago: reason ${e.reason} (${reason(e.reason)}), importance ${e.importance}" +
                (e.description?.let { ", $it" } ?: "")
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun reason(code: Int) = when (code) {
        1 -> "exit self"
        2 -> "signalled"
        3 -> "low memory"
        4 -> "crash"
        5 -> "native crash"
        6 -> "ANR"
        7 -> "initialization failure"
        8 -> "permission change"
        9 -> "excessive resource usage"
        10 -> "user requested"
        11 -> "user stopped"
        12 -> "dependency died"
        13 -> "other (system kill)"
        14 -> "freezer"
        15 -> "package state change"
        16 -> "package updated"
        else -> "unknown"
    }
}
