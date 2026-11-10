package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CarVideoFrameHealthTest {
    private fun sample(
        ms: Long = 0,
        video: Boolean = true,
        paused: Boolean? = false,
        frames: Int = 100,
        dropped: Int = 0,
        position: Double? = 10.0,
        width: Int = 1920,
        height: Int = 1080,
    ) = CarVideoFrameSample(ms, video, paused, frames, dropped, position, width, height)

    @Test fun doesNotDiagnosePauseOrMissingVideoAsBlackOrStalled() {
        val previous = sample()
        assertEquals(CarVideoFrameHealth.NO_VIDEO,
            CarVideoFrameHealthPolicy.classify(previous, sample(ms = 6000, video = false)))
        assertEquals(CarVideoFrameHealth.NO_VIDEO,
            CarVideoFrameHealthPolicy.classify(previous, sample(ms = 6000, width = 0)))
        assertEquals(CarVideoFrameHealth.PAUSED,
            CarVideoFrameHealthPolicy.classify(previous, sample(ms = 6000, paused = true)))
        assertEquals(CarVideoFrameHealth.PAUSED,
            CarVideoFrameHealthPolicy.classify(previous, sample(ms = 6000, paused = null)))
        assertEquals(CarVideoFrameHealth.AWAITING_BASELINE,
            CarVideoFrameHealthPolicy.classify(null, sample()))
    }

    @Test fun requiresElapsedTimeAndValidVideoCounters() {
        val previous = sample()
        assertEquals(CarVideoFrameHealth.TOO_SOON,
            CarVideoFrameHealthPolicy.classify(previous, sample(ms = 3000)))
        assertEquals(CarVideoFrameHealth.COUNTER_RESET,
            CarVideoFrameHealthPolicy.classify(previous, sample(ms = -1)))
        assertEquals(CarVideoFrameHealth.FRAME_COUNTER_UNAVAILABLE,
            CarVideoFrameHealthPolicy.classify(previous, sample(ms = 6000, frames = -1)))
        assertEquals(CarVideoFrameHealth.AWAITING_BASELINE,
            CarVideoFrameHealthPolicy.classify(sample(paused = true),
                sample(ms = 6000)))
    }

    @Test fun distinguishesFrameProgressFromSuspectedStalls() {
        val previous = sample()
        assertEquals(CarVideoFrameHealth.FRAMES_ADVANCING,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, frames = 240, position = 16.0)))
        assertEquals(CarVideoFrameHealth.DECODER_STALL_SUSPECTED,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, frames = 100, position = 10.0)))
        assertEquals(CarVideoFrameHealth.DECODER_STALL_SUSPECTED,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, frames = 100, position = 16.0)))
        assertEquals(CarVideoFrameHealth.PLAYBACK_STALL_SUSPECTED,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, frames = 210, position = 10.0)))
    }

    @Test fun videoChangeSeekAndCounterWrapStartNewBaseline() {
        val previous = sample()
        assertEquals(CarVideoFrameHealth.COUNTER_RESET,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, frames = 50)))
        assertEquals(CarVideoFrameHealth.COUNTER_RESET,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, dropped = -1)))
        assertEquals(CarVideoFrameHealth.COUNTER_RESET,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, position = 0.0)))
        assertEquals(CarVideoFrameHealth.COUNTER_RESET,
            CarVideoFrameHealthPolicy.classify(previous,
                sample(ms = 6000, position = 300.0)))
    }
}
