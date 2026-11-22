package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTransitionPolicyTest {
    private val full = PxRect(0f, 0f, 1000f, 2000f)
    private val mini = PxRect(700f, 1700f, 224f, 126f)

    @Test fun interpolatesBoundsAndClamps() {
        assertEquals(full, PlayerTransitionPolicy.lerp(full, mini, 0f))
        assertEquals(mini, PlayerTransitionPolicy.lerp(full, mini, 1f))
        assertEquals(PxRect(350f, 850f, 612f, 1063f), PlayerTransitionPolicy.lerp(full, mini, 0.5f))
        assertEquals(mini, PlayerTransitionPolicy.lerp(full, mini, 1.7f))
        assertEquals(full, PlayerTransitionPolicy.lerp(full, mini, -1f))
    }

    @Test fun cornersFollowDirection() {
        assertEquals(0f, PlayerTransitionPolicy.cornerDp(true, 0f))
        assertEquals(12f, PlayerTransitionPolicy.cornerDp(true, 1f))
        assertEquals(12f, PlayerTransitionPolicy.cornerDp(false, 0f))
        assertEquals(0f, PlayerTransitionPolicy.cornerDp(false, 1f))
    }

    @Test fun systemOwnedTransitionsAreLeftAlone() {
        assertTrue(PlayerTransitionPolicy.animates(false, false, false, false))
        assertFalse(PlayerTransitionPolicy.animates(true, false, false, false))
        assertFalse(PlayerTransitionPolicy.animates(false, true, false, false))
        assertFalse(PlayerTransitionPolicy.animates(false, false, true, false))
        assertFalse(PlayerTransitionPolicy.animates(false, false, false, true))
    }

    @Test fun captureIsDownscaledAndSafe() {
        assertEquals(720 to 1440, PlayerTransitionPolicy.captureSize(1440, 2880))
        assertEquals(224 to 126, PlayerTransitionPolicy.captureSize(224, 126))
        assertEquals(0 to 0, PlayerTransitionPolicy.captureSize(0, 100))
    }

    @Test fun rapidTransitionPositionsAndCaptureBoundsStayValid() {
        for (step in -10..110) {
            val fraction = step / 100f
            val bounds = PlayerTransitionPolicy.lerp(full, mini, fraction)
            assertTrue(bounds.width > 0f)
            assertTrue(bounds.height > 0f)
            assertTrue(bounds.left >= full.left && bounds.left <= mini.left)
            assertTrue(bounds.top >= full.top && bounds.top <= mini.top)
            val corner = PlayerTransitionPolicy.cornerDp(true, fraction)
            assertTrue(corner in 0f..PlayerTransitionPolicy.MINI_CORNER_DP)
        }
        assertEquals(720 to 1280, PlayerTransitionPolicy.captureSize(1080, 1920))
        assertEquals(0 to 0, PlayerTransitionPolicy.captureSize(-1, 720))
    }

    @Test fun onlyTheVideoBandIsCaptured() {
        assertEquals(PxRect(0f, 0f, 1080f, 607.5f), PlayerTransitionPolicy.videoBand(1080f, 2200f))
        assertEquals(PxRect(0f, 0f, 2200f, 1080f), PlayerTransitionPolicy.videoBand(2200f, 1080f))
    }
}
