package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.hozonauto.localradiomusic.aidl.IRadioMetadataAidlInterface
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * Publishes CarPlay now-playing state to the Nezha media hub
 * (of.mediaserver.hz), which relays it to the instrument cluster's music page.
 *
 * The hub exposes a metadata publishing port (the same one the car's own radio
 * app uses) with no permission, signature or package checks. Binding simply
 * fails on cars without the hub, and every failure path here is silent: this is
 * strictly an add-on for Nezha head units and must never disturb playback
 * anywhere else.
 *
 * All state lives on a dedicated thread; the CarPlayMediaKeys hooks only post.
 */
internal object NezhaMusicPublisher {
    private const val TAG = "DiPlay-NezhaMusic"
    private const val HUB_PACKAGE = "of.mediaserver.hz"
    private const val HUB_SERVICE = "of.mediaserver.hz.service.MediaService"
    private const val HUB_ACTION = "of.radio.hz.aidl.IRadioImgAidlInterface"
    // The hub relays the source type uninterpreted; the cluster files anything
    // named like a music source under its music page.
    private const val SOURCE_TYPE = "QQMusic"
    private const val TICK_MS = 1_000L

    // Lazy: merely loading this object (for example from a media-key teardown path
    // that never publishes) must not spawn the thread. Creating the handler can
    // also fail where no real Looper exists (unit tests), so every entry point
    // below guards its post with runCatching: publishing stays strictly silent.
    private val handler: Handler by lazy {
        val thread = HandlerThread("diplay-nezha-music").apply { start() }
        Handler(thread.looper)
    }

    // Everything below is confined to the handler thread.
    private var appContext: Context? = null
    private var remote: IRadioMetadataAidlInterface? = null
    private var connection: ServiceConnection? = null
    private var binding = false
    private var bindFailed = false
    private var snapshot: CarPlayNowPlaying? = null
    private var baseElapsedRealtime = 0L
    private var artworkId: Int? = null
    private var artworkBytes: ByteArray? = null
    private var pushed = false
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            push()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** A now-playing snapshot arrived; [elapsedAtRealtime] is when its elapsed value was true. */
    fun onNowPlaying(context: Context, update: CarPlayNowPlaying, elapsedAtRealtime: Long) {
        runCatching { handler.post {
            appContext = context.applicationContext
            val previous = snapshot
            snapshot = update
            baseElapsedRealtime = elapsedAtRealtime
            if (update.title == null && update.durationMillis == null) return@post
            // Position-only updates (about twice a second) only refresh the
            // extrapolation base; the ticker republishes once a second.
            val displayChanged = previous == null ||
                previous.title != update.title ||
                previous.artist != update.artist ||
                previous.album != update.album ||
                previous.durationMillis != update.durationMillis ||
                previous.artworkTransferId != update.artworkTransferId ||
                previous.playing != update.playing
            if (displayChanged) push()
            updateTicker()
        } }
    }

    /** Raw artwork bytes for transfer [id], exactly as the iPhone sent them. */
    fun onArtwork(id: Int, bytes: ByteArray) {
        runCatching { handler.post {
            artworkId = id
            artworkBytes = bytes
            if (snapshot?.artworkTransferId == id) push()
        } }
    }

    /** The CarPlay session ended: report stopped once, then let the hub go. */
    fun onSessionEnded(context: Context) {
        runCatching { handler.post {
            stopTicker()
            val last = snapshot
            if (pushed && last != null && last.playing) {
                snapshot = last.copy(playing = false)
                push()
            }
            pushed = false
            snapshot = null
            artworkId = null
            artworkBytes = null
            bindFailed = false
            unbind()
            appContext = context.applicationContext
        } }
    }

    private fun updateTicker() {
        val shouldTick = snapshot?.playing == true && (remote != null || binding)
        if (shouldTick && !ticking) {
            ticking = true
            handler.postDelayed(tick, TICK_MS)
        } else if (!shouldTick) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun currentProgressMillis(np: CarPlayNowPlaying): Long {
        val base = np.elapsedMillis ?: return 0L
        val extrapolated = if (np.playing) {
            base + (SystemClock.elapsedRealtime() - baseElapsedRealtime).coerceAtLeast(0L)
        } else {
            base
        }
        val clamped = np.durationMillis?.let { minOf(extrapolated, it) } ?: extrapolated
        return clamped.coerceIn(0L, Int.MAX_VALUE.toLong())
    }

    private fun push() {
        val np = snapshot ?: return
        if (np.title == null && np.durationMillis == null) return
        if (!ensureBound()) return
        val service = remote ?: return
        val img = artworkBytes?.takeIf { np.artworkTransferId != null && np.artworkTransferId == artworkId }
        try {
            service.of_updateLocalRadioMetadata(
                SOURCE_TYPE,
                np.title.orEmpty(),
                np.artist.orEmpty(),
                (np.durationMillis ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                currentProgressMillis(np).toInt(),
                np.playing,
                0,
                img,
            )
            pushed = true
        } catch (error: Exception) {
            // Dead or vanished hub: drop the stub and let a later update rebind.
            Log.w(TAG, "media hub publish failed", error)
            remote = null
            unbind()
        }
    }

    private fun ensureBound(): Boolean {
        if (remote != null) return true
        if (binding || bindFailed) return false
        val context = appContext ?: return false
        val intent = Intent(HUB_ACTION).apply {
            component = ComponentName(HUB_PACKAGE, HUB_SERVICE)
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                handler.post {
                    if (connection !== this) return@post
                    remote = IRadioMetadataAidlInterface.Stub.asInterface(binder)
                    binding = false
                    push()
                    updateTicker()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                handler.post {
                    if (connection === this) {
                        remote = null
                        binding = false
                    }
                }
            }
        }
        connection = conn
        val started = runCatching {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "media hub bind failed", it)
            false
        }
        if (started) {
            binding = true
        } else {
            // No hub on this car: stay silent for the rest of the session.
            connection = null
            bindFailed = true
        }
        return false
    }

    private fun unbind() {
        val conn = connection ?: return
        connection = null
        binding = false
        remote = null
        runCatching { appContext?.unbindService(conn) }
    }
}
