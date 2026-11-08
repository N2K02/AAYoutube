package com.aayoutube.app

/** The main player only owns media while YouTube is displaying a watch/live page. */
internal object PlaybackRoutePolicy {
    fun isPlaybackRoute(path: String?): Boolean =
        path == "/watch" || path?.startsWith("/live/") == true

    /** Invalidate video A's metadata when YouTube navigates to video B. */
    fun shouldClearPrevious(path: String?, incomingVideoId: String?, previousId: String): Boolean {
        if (previousId.isBlank()) return false
        if (!isPlaybackRoute(path)) return true
        return path == "/watch" && !incomingVideoId.isNullOrBlank() &&
            incomingVideoId != previousId
    }
}
