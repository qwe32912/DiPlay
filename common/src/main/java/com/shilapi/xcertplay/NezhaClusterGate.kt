package com.shilapi.xcertplay

import android.content.Context
import android.view.Display

/**
 * Nezha cluster gate: intentionally does nothing.
 *
 * Protocol verification (2026-10-18, via AmapSniffer APK capturing native Amap):
 * The stock Amap NEVER sends KEY_TYPE=10001 or KEY_TYPE=10019/8. It only sends
 * 12205 (location), 10066 (slope), 10019/40 (status), and 13022. Navigation info
 * is rendered directly in the map video and mirrored via Presentation/LVDS.
 * The 10019/8 + 10001 broadcasts trigger a legacy turn-card UI that conflicts
 * with the CarPlay video geometry. Cruise mode (no broadcasts) displays perfectly,
 * so navigation must also send nothing.
 */
internal object NezhaClusterGate {
    @Synchronized
    fun onPresentationShown(context: Context, display: Display) {
        // Intentionally empty: no broadcasts needed.
    }

    @Synchronized
    fun onGuidanceChanged(context: Context, next: com.shilapi.xcertplay.hud.ClusterTurnGuidance?) {
        // Intentionally empty: guidance is in the video stream.
    }

    @Synchronized
    fun onPresentationHidden(context: Context) {
        // Intentionally empty.
    }
}
