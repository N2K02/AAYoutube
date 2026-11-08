package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackRoutePolicyTest {
    @Test fun watchAndLiveRoutesKeepPlaybackIdentity() {
        assertTrue(PlaybackRoutePolicy.isPlaybackRoute("/watch"))
        assertTrue(PlaybackRoutePolicy.isPlaybackRoute("/watch?v=sample"))
        assertTrue(PlaybackRoutePolicy.isPlaybackRoute("/live/abc123"))
    }

    @Test fun navigationAwayFromPlaybackInvalidatesPreviousMediaSession() {
        for (path in listOf("/", "/results", "/feed/subscriptions", "/shorts/abc123", "/account", null))
            assertFalse("stale video must be discarded on $path", PlaybackRoutePolicy.isPlaybackRoute(path))
    }
}
