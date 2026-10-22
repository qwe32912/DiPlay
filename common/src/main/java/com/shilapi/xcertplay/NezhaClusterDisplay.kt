package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInsets

/**
 * Nezha (Hozon) digital cluster. The OEM cluster-interconnection service
 * (com.ofilm.clusterinterconnection) projects a virtual presentation display named
 * "NaviDisplay" at 460x500 toward the instrument panel. Either the name or the
 * measured geometry identifies it; both were verified on the car.
 */
internal object NezhaClusterDisplay {
    const val NAME = "NaviDisplay"
    const val WIDTH = 460
    const val HEIGHT = 500

    /**
     * The Nezha panel shows the whole projected frame: nothing is drawn over the map, unlike
     * the DiLink clusters [com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.SAFE_AREA_PERCENT]
     * was calibrated for (status row, ADAS view and readouts cover most of the panel there).
     * So the base safe area is zero — a full-frame declaration, mirroring DiLink 5.1's
     * no-safe-area approach: together with the centred marker below, the declared safe-area
     * window is 100 % of the stream. That matters because the window's share of the frame and
     * how far the iPhone zooms out move together monotonically, as measured on the car:
     * a 17 % window (v1, DiLink margins) zoomed out farthest, 50 % (v3, marker 50/75) sat in
     * between, and 81 % (v2, margins 5/5/5/5 with the marker at 50/45) was the closest so far.
     */
    val SAFE_AREA_PERCENT = AirPlayInsets(top = 0, bottom = 0, left = 0, right = 0)

    /**
     * Where the car marker sits on the panel, in percent (x from the left, y from the top).
     * Pinned at the centre (50, 50): with a zero base safe area, safeArea() then derives
     * all-zero insets, so the iPhone treats the whole stream as the safe window. Fixed for
     * this display: the persisted marker sliders describe DiLink panels, and their default
     * (50, 45) would declare only a 100 % x 90 % window instead of the full frame. An
     * Amap-style anchor at y=75 has no counterpart in this model — the marker is not sent
     * to the iPhone, it only centres the safe-area rect, so 75 would shrink that rect to
     * the lower half of the frame (the v3 framing the car test rejected).
     */
    const val MARKER_X_PERCENT = 50
    const val MARKER_Y_PERCENT = 50

    fun accepts(name: String?, width: Int, height: Int): Boolean =
        name == NAME || (width == WIDTH && height == HEIGHT)
}
