package com.aayoutube.app

/** The main player only owns media while YouTube is displaying a watch/live page. */
internal object PlaybackRoutePolicy {
    fun isPlaybackRoute(path: String?): Boolean =
        path?.startsWith("/watch") == true || path?.startsWith("/live/") == true
}
