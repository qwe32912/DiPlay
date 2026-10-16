package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Sends Nezha cluster turn guidance via the car's Amap standard broadcast.
 *
 * The OEM cluster service enables the NaviDisplay mirror at boot (CLUSTER_READY),
 * so no state broadcast is ever needed. We only send KEY_TYPE=10001 guidance data
 * (road name, icon, distances) when turn-by-turn guidance is active. Sending
 * KEY_TYPE=10019 state changes (8/9) triggers the cluster's native nav layout
 * which corrupts the CarPlay video geometry — cruise mode (no broadcast at all)
 * displays perfectly, so navigation must not deviate from that.
 *
 * Broadcasts are only sent while a presentation is actually shown on the Nezha
 * display, so other cars never see them.
 */
internal object NezhaClusterGate {
    private const val TAG = "DiPlay-NezhaGate"
    private const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val CLUSTER_PACKAGE = "com.ofilm.clusterinterconnection"
    private const val KEY_TYPE_GUIDANCE = 10001

    private var shown = false
    private var guidance: ClusterTurnGuidance? = null

    @Synchronized
    fun onPresentationShown(context: Context, display: Display) {
        val size = ClusterMapPresentation.sizeOf(display)
        if (!NezhaClusterDisplay.accepts(display.name, size.x, size.y)) return
        shown = true
        // Do NOT send any state broadcast. The mirror is enabled at boot via
        // CLUSTER_READY; sending 10019/8 triggers the cluster's native nav layout
        // which breaks the CarPlay video geometry. Just send 10001 data when
        // guidance is available.
        val g = guidance
        if (g != null) {
            sendGuidance(context, g)
        }
    }

    @Synchronized
    fun onGuidanceChanged(context: Context, next: ClusterTurnGuidance?) {
        guidance = next
        if (!shown) return
        if (next != null) {
            // Only send 10001 guidance data. Never send 10019/8 or 10019/9 —
            // the mirror gate is boot-enabled, and 10019 state changes corrupt
            // the video layout.
            sendGuidance(context, next)
        }
        // Guidance ended: send nothing. The map stays in cruise layout.
    }

    @Synchronized
    fun onPresentationHidden(context: Context) {
        shown = false
        guidance = null
        // Send nothing on hide. The mirror stays enabled.
    }

    /** KEY_TYPE=10001: turn-by-turn guidance data (road name, icon, distances). */
    private fun sendGuidance(context: Context, guidance: ClusterTurnGuidance) {
        val intent = Intent(ACTION).apply {
            setPackage(CLUSTER_PACKAGE)
            putExtra("KEY_TYPE", KEY_TYPE_GUIDANCE)
            // ClusterTurnGuidance.icon already uses the AMap NEW_ICON vocabulary
            // this broadcast expects; distances are metres on both sides.
            putExtra("NEXT_ROAD_NAME", guidance.road)
            putExtra("NEW_ICON", guidance.icon)
            putExtra("SEG_REMAIN_DIS", guidance.distanceMeters)
            guidance.remainingMeters?.let { putExtra("ROUTE_REMAIN_DIS", it) }
        }
        runCatching { context.applicationContext.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "cluster guidance broadcast failed", it) }
    }
}
