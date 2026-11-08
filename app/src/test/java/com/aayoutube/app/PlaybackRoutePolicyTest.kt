package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackRoutePolicyTest {
    @Test fun watchAndLiveRoutesKeepPlaybackIdentity() {
        assertTrue(PlaybackRoutePolicy.isPlaybackRoute("/watch"))
        // Uri.path never contains the query string: match exactly /watch.
        assertFalse(PlaybackRoutePolicy.isPlaybackRoute("/watch?v=sample"))
        assertTrue(PlaybackRoutePolicy.isPlaybackRoute("/live/abc123"))
    }

    @Test fun watchVideoSwitchClearsOldMetadataButSameVideoReloadDoesNot() {
        assertTrue(PlaybackRoutePolicy.shouldClearPrevious("/watch", "new", "old"))
        assertFalse(PlaybackRoutePolicy.shouldClearPrevious("/watch", "old", "old"))
        assertFalse(PlaybackRoutePolicy.shouldClearPrevious("/watch", null, "old"))
        assertFalse(PlaybackRoutePolicy.shouldClearPrevious("/watch", "new", ""))
    }

    @Test fun spaNavigationAndNonVideoPathsClearOldState() {
        assertTrue(PlaybackRoutePolicy.shouldClearPrevious("/", null, "old"))
        assertTrue(PlaybackRoutePolicy.shouldClearPrevious("/results", null, "old"))
        assertTrue(PlaybackRoutePolicy.shouldClearPrevious("/watching", "new", "old"))
        assertFalse(PlaybackRoutePolicy.isPlaybackRoute("/watching"))
        assertFalse(PlaybackRoutePolicy.shouldClearPrevious("/live/liveId", null, "old"))
    }

    @Test fun navigationAwayFromPlaybackInvalidatesPreviousMediaSession() {
        for (path in listOf("/", "/results", "/feed/subscriptions", "/shorts/abc123", "/account", null))
            assertFalse("stale video must be discarded on $path", PlaybackRoutePolicy.isPlaybackRoute(path))
    }
}
