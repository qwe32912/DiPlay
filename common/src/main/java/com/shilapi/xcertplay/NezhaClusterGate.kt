package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Opens the Nezha cluster's navigation mirror gate, strictly following real guidance.
 *
 * The OEM cluster service only forwards frames from the NaviDisplay presentation to
 * the physical panel while a navigation session is open, and it opens that session
 * for the standard AutoNavi guidance broadcast — the same broadcast the car's own
 * Amap build sends, with no caller verification. State 8 starts a session (and
 * carries the turn card fields), state 9 ends it.
 *
 * A bare state-8 with no guidance data puts the cluster into cruise UI, which kicks
 * the user off the music page when they are not navigating. So the gate is opened
 * only while the iPhone reports real turn-by-turn guidance, and closed (state 9)
 * as soon as guidance ends or the presentation hides. Broadcasts are only sent
 * while a presentation is actually shown on the Nezha display, so other cars never
 * see them.
 */
internal object NezhaClusterGate {
    private const val TAG = "DiPlay-NezhaGate"
    private const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val CLUSTER_PACKAGE = "com.ofilm.clusterinterconnection"
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
        // Do NOT open the gate here: a bare START with no guidance data puts the
        // cluster into cruise UI and kicks the user off the music page.
        val g = guidance
        if (g != null && !gateOpen) {
            gateOpen = true
            send(context, STATE_START, g)
        }
    }

    @Synchronized
    fun onGuidanceChanged(context: Context, next: ClusterTurnGuidance?) {
        guidance = next
        if (next != null) {
            if (!shown) return
            if (!gateOpen) gateOpen = true
            send(context, STATE_START, next)
        } else if (gateOpen) {
            gateOpen = false
            send(context, STATE_END, null)
        }
    }

    @Synchronized
    fun onPresentationHidden(context: Context) {
        shown = false
        if (!gateOpen) return
        gateOpen = false
        send(context, STATE_END, null)
    }

    private fun send(context: Context, state: Int, guidance: ClusterTurnGuidance?) {
        val intent = Intent(ACTION).apply {
            setPackage(CLUSTER_PACKAGE)
            putExtra("KEY_TYPE", KEY_TYPE_NAVIGATION)
            putExtra("EXTRA_STATE", state)
            if (state == STATE_START && guidance != null) {
                // ClusterTurnGuidance.icon already uses the AMap NEW_ICON vocabulary
                // this broadcast expects; distances are metres on both sides.
                putExtra("NEXT_ROAD_NAME", guidance.road)
                putExtra("NEW_ICON", guidance.icon)
                putExtra("SEG_REMAIN_DIS", guidance.distanceMeters)
                guidance.remainingMeters?.let { putExtra("ROUTE_REMAIN_DIS", it) }
            }
        }
        runCatching { context.applicationContext.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "cluster gate broadcast failed", it) }
    }
}
