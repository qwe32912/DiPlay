package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Frees memory for the software-decoded CarPlay video on the memory-tight Nezha head unit
 * by asking Android to kill the background processes of other apps.
 *
 * System apps are never touched, and the packages the car relies on while driving (the
 * launcher, System UI, the media hub that forwards Now Playing to the cluster, the cluster
 * interconnection service and the car's own navigation) are whitelisted explicitly as well,
 * so they stay protected even if one of them loses its system flag. Killing a
 * background process is only a hint: Android restarts anything it still needs.
 */
internal object BackgroundCleaner {
    private const val TAG = "BackgroundCleaner"

    /** How often the cleanup repeats while a session is running. */
    const val CLEAN_INTERVAL_MILLIS = 5 * 60_000L

    /** Packages that must never be killed, even when they are not flagged as system apps. */
    val WHITELISTED_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "of.mediaserver.hz",
        "com.ofilm.clusterinterconnection",
        "com.autonavi.minimap",
    )

    /** Every package of the car maker itself is off limits. */
    const val HOZONAUTO_PACKAGE_PREFIX = "com.hozonauto."

    /**
     * Whether [packageName] must be left alone: this app itself, any system app, an
     * explicitly whitelisted package, or any package of the car maker.
     */
    fun shouldSkip(packageName: String, flags: Int, ownPackage: String): Boolean =
        packageName == ownPackage ||
            flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            packageName in WHITELISTED_PACKAGES ||
            packageName.startsWith(HOZONAUTO_PACKAGE_PREFIX)

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
