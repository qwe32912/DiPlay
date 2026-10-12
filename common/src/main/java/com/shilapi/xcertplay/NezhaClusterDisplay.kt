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
     * The OEM Amap build renders into the same frame with zero insets, so the base safe area is
     * zero too: the panel occludes nothing. The insets the iPhone finally receives come only
     * from where the marker anchor below sits, via the centre-and-shrink geometry of
     * CarPlayClusterDisplay.safeArea.
     */
    val SAFE_AREA_PERCENT = AirPlayInsets(top = 0, bottom = 0, left = 0, right = 0)

    /**
     * Where the car marker sits on the panel, in percent (x from the left, y from the top).
     * Anchored to the OEM Amap calibration for this panel (MultipleScreen.json Carposition
     * 0.75 with the parser's horizontal default 0.5): centred horizontally, three quarters
     * down, so more of the road ahead is visible than behind. Fixed for this display: the
     * persisted marker sliders describe DiLink panels and must not move the Nezha anchor.
     * With a zero base safe area, safeArea() centres the largest possible rect on (50, 75),
     * which for the 384x416 stream yields insets top=208, bottom=0, left=0, right=0.
     */
    const val MARKER_X_PERCENT = 50
    const val MARKER_Y_PERCENT = 75

    fun accepts(name: String?, width: Int, height: Int): Boolean =
        name == NAME || (width == WIDTH && height == HEIGHT)
}
