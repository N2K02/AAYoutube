package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDemotePolicyTest {
    private val video = Snap(id = "abc")

    @Test fun playingOrRecoveringKeepsForeground() {
        assertNull(ForegroundDemotePolicy.delayMs(video.copy(playing = true), false, 0L, 1_000L))
        assertNull(ForegroundDemotePolicy.delayMs(video, true, 0L, 1_000L))
    }

    @Test fun deliberatePauseStopOrEndUsesShortGrace() {
        val g = ForegroundDemotePolicy.USER_STOP_GRACE_MS
        assertEquals(g, ForegroundDemotePolicy.delayMs(video.copy(userPaused = true), false, 0L, 1L))
        assertEquals(g, ForegroundDemotePolicy.delayMs(video.copy(ended = true), false, 0L, 1L))
        assertEquals(g, ForegroundDemotePolicy.delayMs(Snap(), false, 0L, 1L))
    }

    @Test fun spontaneousInterruptionIsBoundedNotForever() {
        val g = ForegroundDemotePolicy.INTERRUPTED_GRACE_MS
        assertTrue(ForegroundDemotePolicy.isInterrupted(video, false))
        assertEquals(g, ForegroundDemotePolicy.delayMs(video, false, 0L, 5_000L))
        assertEquals(g - 60_000L, ForegroundDemotePolicy.delayMs(video, false, 10_000L, 70_000L))
        // Repeated paused reports must not keep resetting the deadline.
        assertEquals(0L, ForegroundDemotePolicy.delayMs(video, false, 10_000L, 10_000L + g + 1))
    }

    @Test fun recoveryIsNotTreatedAsInterruption() {
        assertFalse(ForegroundDemotePolicy.isInterrupted(video, true))
        assertFalse(ForegroundDemotePolicy.isInterrupted(video.copy(userPaused = true), false))
    }

    @Test fun phaseDerivation() {
        assertEquals("user-paused", ForegroundDemotePolicy.phase(video.copy(userPaused = true, playing = true), false))
        assertEquals("playing", ForegroundDemotePolicy.phase(video.copy(playing = true, buffering = true), false))
        assertEquals("recovering", ForegroundDemotePolicy.phase(video, true))
        assertEquals("recovering", ForegroundDemotePolicy.phase(video.copy(recovering = true), false))
        assertEquals("buffering", ForegroundDemotePolicy.phase(video.copy(buffering = true), false))
        assertEquals("interrupted", ForegroundDemotePolicy.phase(video, false))
        assertEquals("idle", ForegroundDemotePolicy.phase(Snap(), false))
    }

    @Test fun cpuPercent() {
        assertEquals(-1L, ForegroundDemotePolicy.cpuPercent(0L, 0L, 60_000L, 5_000L))
        assertEquals(25L, ForegroundDemotePolicy.cpuPercent(1_000L, 0L, 61_000L, 15_000L))
        assertEquals(0L, ForegroundDemotePolicy.cpuPercent(1_000L, 9_000L, 61_000L, 5_000L))
        assertEquals(-1L, ForegroundDemotePolicy.cpuPercent(5_000L, 0L, 5_000L, 10L))
    }
}
