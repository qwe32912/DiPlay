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
     * Near-zero margins keep the persisted marker defaults (50/45) near the centre of the safe
     * area while the safe area spans almost the full panel, so the iPhone does not shrink the
     * map into the small uncovered centre window the DiLink margins would describe.
     */
    val SAFE_AREA_PERCENT = AirPlayInsets(top = 5, bottom = 5, left = 5, right = 5)

    fun accepts(name: String?, width: Int, height: Int): Boolean =
        name == NAME || (width == WIDTH && height == HEIGHT)
}
