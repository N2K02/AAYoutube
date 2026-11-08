package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun pauseAndStopAreReplayedUntilAcknowledged() {
        val intent = TerminalTransportIntent()
        val pause = intent.onCommand(Msg.PAUSE)!!
        assertEquals(Msg.PAUSE, intent.pendingCommand)
        intent.acknowledge(Msg.STOP, pause)
        assertEquals(Msg.PAUSE, intent.pendingCommand)
        intent.acknowledge(Msg.PAUSE, pause)
        assertNull(intent.pendingCommand)
        val stop = intent.onCommand(Msg.STOP)!!
        assertEquals(Msg.STOP, intent.pendingCommand)
        intent.acknowledge(Msg.PAUSE, stop)
        assertEquals(Msg.STOP, intent.pendingCommand)
        intent.acknowledge(Msg.STOP, stop)
        assertNull(intent.pendingCommand)
    }

    @Test fun sameActionAckCannotClearLaterManualPause() {
        val intent = TerminalTransportIntent()
        val old = intent.onCommand(Msg.PAUSE)!!
        intent.onCommand(Msg.PLAY)
        val newer = intent.onCommand(Msg.PAUSE)!!
        assertTrue(newer > old)
        intent.acknowledge(Msg.PAUSE, old)
        assertEquals(Msg.PAUSE, intent.pendingCommand)
        assertEquals(newer, intent.pendingId)
        intent.acknowledge(Msg.PAUSE, newer)
        assertNull(intent.pendingCommand)
    }

    @Test fun laterPlaySelectionOrQuitSupersedesStaleTerminalIntent() {
        val intent = TerminalTransportIntent()
        intent.onCommand(Msg.PAUSE)
        intent.onCommand(Msg.PLAY)
        assertNull(intent.pendingCommand)
        intent.onCommand(Msg.STOP)
        intent.onCommand(Msg.OPEN)
        assertNull(intent.pendingCommand)
        intent.onCommand(Msg.PAUSE)
        intent.onCommand(Msg.SEARCH)
        assertNull(intent.pendingCommand)
        intent.onCommand(Msg.STOP)
        intent.onCommand(Msg.QUIT)
        assertNull(intent.pendingCommand)
    }

    @Test fun survivingPlayerIsNeverReopened() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", playing = true, posMs = 120_000)))
        assertFalse(r.onExistingPlayer(Snap())) // WebView loading; wait for a real ID.
        assertTrue(r.active)
        assertFalse(r.onExistingPlayer(Snap(id = "video", playing = true, posMs = 123_000)))
        assertFalse(r.active)
        assertNull(r.onMissingPlayer())
    }

    @Test fun userPauseDuringLinkFailureMustNeverAutoResumeOnReconnect() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", playing = true)))
        assertFalse(r.onExistingPlayer(Snap(id = "video", playing = false, userPaused = true)))
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

    @Test fun mediaRecoverySurvivesLinkLossWithoutAPlayingSnapshot() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", recovering = true)))
        assertFalse(r.onExistingPlayer(Snap(id = "video", buffering = true)))
        assertTrue(r.active)
        assertTrue(r.onExistingPlayer(Snap(id = "video", playing = false)))
        assertFalse(r.active)
    }

    @Test fun userPauseWhileBufferingCancelsRecovery() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", playing = true)))
        assertFalse(r.onExistingPlayer(Snap(id = "video", buffering = true)))
        assertFalse(r.onExistingPlayer(Snap(id = "video", userPaused = true)))
        assertFalse(r.active)
    }

    @Test fun bufferingExistingPlayerMustNotTriggerPlay() {
        val r = PlaybackRecovery()
        assertTrue(r.onLinkLost(Snap(id = "video", playing = true)))
        assertFalse(r.onExistingPlayer(Snap(id = "video", buffering = true)))
        assertTrue(r.active)
        assertTrue(r.onExistingPlayer(Snap(id = "video", playing = false)))
        assertFalse(r.active)
    }
}
