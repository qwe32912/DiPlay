package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
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
    // The hub forwards the image bytes to the instrument panel on every metadata
    // push, and the panel decodes them. The iPhone sends artwork in whatever size
    // and format the track carries; forwarding that verbatim made some tracks'
    // covers flicker and, on the weakest panels, restart the cluster outright.
    // Normalize everything to a small baseline JPEG before it leaves this process.
    private const val MAX_ARTWORK_DIMENSION = 240
    private const val ARTWORK_JPEG_QUALITY = 85

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

    /** Artwork bytes for transfer [id], normalized before they are ever published. */
    fun onArtwork(id: Int, bytes: ByteArray) {
        runCatching { handler.post {
            artworkId = id
            artworkBytes = normalizeArtwork(bytes)
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

    /**
     * Decodes phone-supplied artwork and re-encodes it as a small baseline JPEG.
     * Returns null when the bytes do not decode (unknown format, truncated
     * transfer): the caller then falls back to the placeholder instead of
     * forwarding bytes the panel cannot handle.
     */
    private fun normalizeArtwork(bytes: ByteArray): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_ARTWORK_DIMENSION * 2 ||
                bounds.outHeight / sampleSize > MAX_ARTWORK_DIMENSION * 2) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: return null
            val scale = minOf(
                1f,
                MAX_ARTWORK_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height),
            )
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }
            val out = ByteArrayOutputStream()
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            if (!ok || out.size() == 0) null else out.toByteArray()
        }.getOrNull()
    }

    private var placeholder: ByteArray? = null

    private fun placeholderBytes(): ByteArray? {
        placeholder?.let { return it }
        val built = runCatching {
            val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            blank.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)
            blank.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
        placeholder = built
        return built
    }

    private fun push() {
        val np = snapshot ?: return
        if (np.title == null && np.durationMillis == null) return
        if (!ensureBound()) return
        val service = remote ?: return
        // Never publish a null image: the hub dereferences img.length in its
        // logging path, so a null image fails the whole push in the hub and
        // leaves this publisher rebinding on every tick. While the real cover
        // is still in flight (or the track has none), publish a valid blank
        // JPEG instead; the real cover replaces it as soon as it arrives.
        val img = artworkBytes?.takeIf { np.artworkTransferId != null && np.artworkTransferId == artworkId }
            ?: placeholderBytes()
            ?: return
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
            // Nullable parameters: the framework never passes null on a car, but
            // test doubles (Robolectric's ShadowInstrumentation) call this with a
            // null name, and a Kotlin non-null parameter would throw before the
            // body runs. A missing binder simply means there is no hub to talk to.
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val serviceBinder = binder ?: return
                runCatching { handler.post {
                    if (connection !== this) return@post
                    remote = IRadioMetadataAidlInterface.Stub.asInterface(serviceBinder)
                    binding = false
                    push()
                    updateTicker()
                } }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                runCatching { handler.post {
                    if (connection === this) {
                        remote = null
                        binding = false
                    }
                } }
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
