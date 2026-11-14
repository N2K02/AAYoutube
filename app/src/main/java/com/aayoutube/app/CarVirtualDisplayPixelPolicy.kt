package com.aayoutube.app

/** A known synthetic color; no user content or video is sampled by the test. */
internal object CarVirtualDisplayPixelPolicy {
    const val RED = 26
    const val GREEN = 143
    const val BLUE = 224

    /** Exact readback can vary slightly by compositor/color space. */
    fun isExpected(red: Int, green: Int, blue: Int): Boolean =
        kotlin.math.abs(red - RED) <= 15 &&
            kotlin.math.abs(green - GREEN) <= 15 &&
            kotlin.math.abs(blue - BLUE) <= 15
}
