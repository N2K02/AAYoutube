package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun survivingPlayerIsNeverReopened() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", playing = true, posMs = 120_000)))
        assertFalse(r.onExistingPlayer(Snap())) // WebView loading; wait for a real ID.
        assertTrue(r.active)
        assertFalse(r.onExistingPlayer(Snap(id = "video", playing = true, posMs = 123_000)))
        assertFalse(r.active)
        assertNull(r.onMissingPlayer())
    }

    @Test fun interruptedPlaybackCanResumeItsExistingPlayerWithoutNavigation() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", playing = true)))
        assertTrue(r.onExistingPlayer(Snap(id = "video", playing = false)))
        assertFalse(r.active)
        assertFalse(r.onExistingPlayer(Snap(id = "video", playing = false)))
    }

    @Test fun pausedPlaybackOrExplicitStopNeverAutomaticallyResumes() {
        val r = PlaybackRecovery()
        assertFalse(r.onLinkLost(Snap(id = "paused", playing = false)))
        assertNull(r.onMissingPlayer())
        assertTrue(r.onLinkLost(Snap(id = "was-playing", playing = true)))
        r.cancel() // PAUSE, STOP, SEARCH, OPEN, Quit
        assertFalse(r.onExistingPlayer(Snap(id = "was-playing", playing = false)))
        assertNull(r.onMissingPlayer())
    }

    @Test fun trulyMissingRendererOnlyReopensPreviousPlayingVideo() {
        val r = PlaybackRecovery()
        assertTrue(r.onRendererGone(Snap(id = "video", playing = true, posMs = 58_000)))
        val previous = r.onMissingPlayer()
        assertEquals("video", previous?.id)
        assertEquals(58_000L, previous?.posMs)
        assertNull(r.onMissingPlayer())
        assertFalse(r.active)
    }

    @Test fun unrelatedNewVideoInvalidatesOldRecoveryIntent() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "old", playing = true)))
        assertFalse(r.onExistingPlayer(Snap(id = "new", playing = false)))
        assertNull(r.onMissingPlayer())
    }

    @Test fun bufferingExistingPlayerMustNotTriggerPlay() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", playing = true)))
        assertFalse(r.onExistingPlayer(Snap(id = "video", buffering = true)))
        assertFalse(r.active)
    }
}
