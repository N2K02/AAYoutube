package com.aayoutube.app

/**
 * Phone held sideways on a watch page: the video fills the screen, like the YouTube
 * app. Never while Chromium's own fullscreen, PiP, the mini player, another tab or
 * Android Auto owns the player.
 */
internal object LandscapeFillPolicy {
    fun active(
        landscape: Boolean,
        watchTab: Boolean,
        watchPage: Boolean,
        fullPlayer: Boolean,
        inPip: Boolean,
        fullscreen: Boolean,
        car: Boolean,
        /** Swipe down / Back from the filled video shows the watch page and playlist. */
        dismissed: Boolean = false,
    ): Boolean = landscape && watchTab && watchPage && fullPlayer && !inPip && !fullscreen && !car && !dismissed
}
