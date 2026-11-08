package com.aayoutube.app

/** PiP sizing is owned by Android's WindowManager. This policy only validates
 * the transition source: a tiny mini-player, car window or stale WebView bounds
 * must never be used as the source rectangle of standard 16:9 phone PiP. */
internal object StandardPipPolicy {
    private const val MIN_SOURCE_WIDTH_DP = 240f
    private const val MIN_SOURCE_HEIGHT_DP = 135f

    fun isValidSource(widthPx: Int, heightPx: Int, density: Float): Boolean {
        if (widthPx <= 0 || heightPx <= 0 || !density.isFinite() || density <= 0f) return false
        return widthPx / density >= MIN_SOURCE_WIDTH_DP &&
            heightPx / density >= MIN_SOURCE_HEIGHT_DP
    }
}
