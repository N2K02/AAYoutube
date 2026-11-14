package com.aayoutube.app

/** Display refresh while a video plays on the phone screen. */
internal object VideoRefreshPolicy {
    const val VIDEO_HZ = 60f

    /** Cap only while the player is actually playing on the Watch tab; the mini
     * player sits over browsing, which keeps smooth 120 Hz scrolling. */
    fun capped(watchTab: Boolean, playing: Boolean, playerMode: WebHost.PlayerMode): Boolean =
        watchTab && playing && playerMode == WebHost.PlayerMode.FULL

    fun preferredRate(capped: Boolean): Float = if (capped) VIDEO_HZ else 0f
}
