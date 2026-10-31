package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class MediaRetryPolicyTest {
    @Test fun backoffGrowsThenCaps() {
        assertEquals(1_000L, MediaRetryPolicy.delayMs(0))
        assertEquals(2_000L, MediaRetryPolicy.delayMs(1))
        assertEquals(4_000L, MediaRetryPolicy.delayMs(2))
        assertEquals(8_000L, MediaRetryPolicy.delayMs(3))
        assertEquals(16_000L, MediaRetryPolicy.delayMs(4))
        assertEquals(32_000L, MediaRetryPolicy.delayMs(50))
    }

    @Test fun idleSessionStopsRetryingButActivePlaybackKeepsRecovering() {
        assertTrue(MediaRetryPolicy.shouldRetry(3, false))
        assertFalse(MediaRetryPolicy.shouldRetry(4, false))
        assertTrue(MediaRetryPolicy.shouldRetry(4, true))
        assertTrue(MediaRetryPolicy.shouldRetry(60, true))
    }
}
