package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    private val cleanerHandler = Handler(Looper.getMainLooper())
    private var backgroundCleanerRunning = false
    private val backgroundCleanerTask = object : Runnable {
        override fun run() {
            if (!backgroundCleanerRunning) return
            runCatching { BackgroundCleaner.clean(this@DiPlaySessionService) }
            if (backgroundCleanerRunning) {
                cleanerHandler.postDelayed(this, BackgroundCleaner.CLEAN_INTERVAL_MILLIS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBackgroundCleaner()
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            // Android 7.x has no notification channels; the priority stands in for the channel importance.
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, CarPlayHostActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText("CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Without it Android stops location updates while another car app (the reversing camera,
            // the car's own map) covers CarPlay, and the iPhone gets no position until DiPlay is back.
            if (AirPlayPersistence.loadLocationReportingEnabled(this) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        startBackgroundCleaner()
        return START_NOT_STICKY
    }

    /** Cleans once right away, then every [BackgroundCleaner.CLEAN_INTERVAL_MILLIS] until stopped. */
    private fun startBackgroundCleaner() {
        // Disabled by user request (2026-10-18): no longer kills background processes.
        return
        // onStartCommand can be called again for a running service; never run two loops.
        if (backgroundCleanerRunning) return
        backgroundCleanerRunning = true
        cleanerHandler.post(backgroundCleanerTask)
    }

    private fun stopBackgroundCleaner() {
        backgroundCleanerRunning = false
        cleanerHandler.removeCallbacks(backgroundCleanerTask)
    }

    override fun onDestroy() {
        stopBackgroundCleaner()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        stopBackgroundCleaner()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val CHANNEL = "diplay_connection"
    }
}
