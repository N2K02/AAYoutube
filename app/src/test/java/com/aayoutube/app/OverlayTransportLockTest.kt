package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class OverlayTransportLockTest {
    @Test fun playUnlocksOnlyWhenPlaybackBegins() {
        val lock = OverlayTransportLock("play", "video1", false)
        assertFalse(lock.completed("video1", false))
        assertFalse(lock.completed("", false))
        assertTrue(lock.completed("video1", true))
    }

    @Test fun pauseUnlocksOnlyAfterPlayerReportsPaused() {
        val lock = OverlayTransportLock("pause", "video1", true)
        assertFalse(lock.completed("video1", true))
        assertTrue(lock.completed("video1", false))
    }

    @Test fun nextAndPreviousRemainLockedWhileOldVideoIsLoading() {
        for (command in listOf("next", "prev")) {
            val lock = OverlayTransportLock(command, "old", true)
            assertFalse(lock.completed("old", true))
            assertFalse(lock.completed("", false))
            assertTrue(lock.completed("new", false))
            assertTrue(lock.completed("new", true))
        }
    }

    @Test fun unchangedVideoDoesNotPretendThatSkipCompleted() {
        val lock = OverlayTransportLock("next", "old", true)
        assertFalse(lock.completed("old", false))
        assertFalse(lock.completed("old", true))
    }
}
