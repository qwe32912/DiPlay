package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Frees memory for the software-decoded CarPlay video on the memory-tight Nezha head unit
 * by asking Android to kill the background processes of other apps.
 *
 * Almost everything preinstalled on this head unit is a system app, and those stock apps
 * are exactly what holds the memory, so a system flag must not exempt an app from the
 * cleanup. Instead only the small set of packages the car cannot drive without is
 * protected: DiPlay itself, the system server, System UI, the launcher, the cluster
 * interconnection service that owns the instrument display, the media hub that forwards
 * Now Playing to the cluster, and the Bluetooth, phone and network stacks. Everything
 * else — including the stock navigation and the car maker's own apps — is cleaned:
 * [ActivityManager.killBackgroundProcesses] cannot kill a process that holds a
 * foreground service, so anything actively in use survives anyway, and Android restarts
 * anything it still needs.
 */
internal object BackgroundCleaner {
    private const val TAG = "BackgroundCleaner"

    /** How often the cleanup repeats while a session is running. */
    const val CLEAN_INTERVAL_MILLIS = 5 * 60_000L

    /** Packages that must never be killed. Every other package is cleaned. */
    val PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.launcher3",
        "com.ofilm.clusterinterconnection",
        "of.mediaserver.hz",
        "com.android.bluetooth",
        "com.android.phone",
        "com.android.networkstack",
    )

    /**
     * Whether [packageName] must be left alone: this app itself, or one of the few
     * protected packages. [flags] is deliberately ignored: whether an app is a system
     * app no longer decides anything (see the class documentation).
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldSkip(packageName: String, flags: Int, ownPackage: String): Boolean =
        packageName == ownPackage || packageName in PROTECTED_PACKAGES

    /**
     * Kills the background processes of every installed app [shouldSkip] does not protect.
     * A package that refuses to be killed does not stop the others.
     *
     * @return how many packages the cleanup was attempted for.
     */
    fun clean(context: Context): Int {
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            Log.w(TAG, "Background cleanup skipped: no ActivityManager")
            return 0
        }
        val installed = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getInstalledApplications(0)
        } catch (error: Exception) {
            Log.w(TAG, "Background cleanup skipped: could not list installed apps", error)
            return 0
        }
        var attempted = 0
        for (app in installed) {
            val packageName = app.packageName ?: continue
            if (shouldSkip(packageName, app.flags, appContext.packageName)) continue
            attempted++
            try {
                activityManager.killBackgroundProcesses(packageName)
            } catch (error: Exception) {
                Log.w(TAG, "Could not kill background processes of $packageName", error)
            }
        }
        Log.i(TAG, "Background cleanup attempted for $attempted package(s)")
        return attempted
    }
}
