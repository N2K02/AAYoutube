package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class CarWindowPlaybackIntentTest {
    @Test fun resumedOrRecoveringStreamRestartsOnNewCarWindow() {
        val playing = Snap(id = "video", playing = true)
        val recovering = Snap(id = "video", recovering = true)
        val dropped = Snap(id = "video")
        assertTrue(CarWindowPlaybackIntent.shouldAutoplay(playing, false, false))
        assertTrue(CarWindowPlaybackIntent.shouldAutoplay(recovering, false, false))
        assertTrue(CarWindowPlaybackIntent.shouldAutoplay(dropped, true, false))
        assertTrue(CarWindowPlaybackIntent.shouldAutoplay(dropped, false, true))
        assertFalse(CarWindowPlaybackIntent.shouldAutoplay(dropped, false, false))
    }

    @Test fun manualPauseAndEndedVideoAlwaysBeatRecoveryIntent() {
        val paused = Snap(id = "video", userPaused = true, recovering = true)
        val ended = Snap(id = "video", ended = true, recovering = true)
        assertFalse(CarWindowPlaybackIntent.shouldAutoplay(paused, true, true))
        assertFalse(CarWindowPlaybackIntent.shouldAutoplay(ended, true, true))
        assertFalse(CarWindowPlaybackIntent.shouldAutoplay(Snap(recovering = true), true, true))
    }
}
