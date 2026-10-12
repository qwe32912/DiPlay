package com.shilapi.xcertplay

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

    fun accepts(name: String?, width: Int, height: Int): Boolean =
        name == NAME || (width == WIDTH && height == HEIGHT)
}
