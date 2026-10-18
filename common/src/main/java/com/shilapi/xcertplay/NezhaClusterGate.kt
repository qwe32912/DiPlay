package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Opens the Nezha cluster's navigation mirror gate, strictly following real guidance.
 *
 * Mirrors the car's own Amap protocol split (verified by decompiling the stock
 * Amap): guidance DATA goes in KEY_TYPE=10001, navigation STATE goes in
 * KEY_TYPE=10019 (EXTRA_STATE 8=start, 9=end) with no data fields.
 *
 * The OEM cluster service enables the NaviDisplay mirror at boot (CLUSTER_READY),
 * so we never need to "open" it — we only must avoid closing it. Sending
 * 10019/9 stops frame forwarding (isNaviMirrorCasting=false), which kills cruise
 * mode after navigation ends. So state 9 is sent only when the presentation is
 * truly hidden (CarPlay disconnected), never on guidance end.
 *
 * Broadcasts are only sent while a presentation is actually shown on the Nezha
 * display, so other cars never see them.
 */
internal object NezhaClusterGate {
    private const val TAG = "DiPlay-NezhaGate"
    private const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val CLUSTER_PACKAGE = "com.ofilm.clusterinterconnection"
    private const val KEY_TYPE_GUIDANCE = 10001
    private const val KEY_TYPE_NAVIGATION = 10019
    private const val STATE_START = 8
    private const val STATE_END = 9

    private var shown = false
    private var gateOpen = false
    private var guidance: ClusterTurnGuidance? = null

    @Synchronized
    fun onPresentationShown(context: Context, display: Display) {
        val size = ClusterMapPresentation.sizeOf(display)
        if (!NezhaClusterDisplay.accepts(display.name, size.x, size.y)) return
        shown = true
        // Do NOT send state here: a bare START with no guidance data puts the
        // cluster into cruise UI and kicks the user off the music page.
        val g = guidance
        if (g != null && !gateOpen) {
            gateOpen = true
            sendState(context, STATE_START)
            sendState(context, STATE_END)
            sendGuidance(context, g)
        }
    }

    @Synchronized
    fun onGuidanceChanged(context: Context, next: ClusterTurnGuidance?) {
        val wasNavigating = guidance != null
        guidance = next
        if (!shown) return
        if (next != null) {
            if (!wasNavigating) {
                // Navigation just started: send state 8 to trigger video, then immediately
                // send state 9 to exit nav mode (per user: 10019/8 causes scaling).
                // Keep sending 10001 for turn data.
                gateOpen = true
                sendState(context, STATE_START)
                sendState(context, STATE_END)
            }
            sendGuidance(context, next)
        } else {
            // Guidance ended: do NOT send state 9 — that would stop the mirror
            // and kill cruise mode. Just stop sending data; the mirror stays up.
            gateOpen = false
        }
    }

    @Synchronized
    fun onPresentationHidden(context: Context) {
        shown = false
        guidance = null
        if (!gateOpen) return
        gateOpen = false
        sendState(context, STATE_END)
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

    /** KEY_TYPE=10019: navigation state only, no data fields (matches stock Amap). */
    private fun sendState(context: Context, state: Int) {
        val intent = Intent(ACTION).apply {
            setPackage(CLUSTER_PACKAGE)
            putExtra("KEY_TYPE", KEY_TYPE_NAVIGATION)
            putExtra("EXTRA_STATE", state)
        }
        runCatching { context.applicationContext.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "cluster state broadcast failed", it) }
    }
}
