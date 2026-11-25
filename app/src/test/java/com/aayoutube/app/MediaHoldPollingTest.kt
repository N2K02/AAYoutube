package com.aayoutube.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaHoldPollingTest {
    @Test fun activeAudioUsesSlowerFallback() {
        assertEquals(2000L, OtherMediaHoldPolicy.pollDelay(true, 0L, 5000L))
    }

    @Test fun quietPollApproachesResumeDeadline() {
        assertEquals(1000L, OtherMediaHoldPolicy.pollDelay(false, 5000L, 5000L))
        assertEquals(250L, OtherMediaHoldPolicy.pollDelay(false, 5000L, 6750L))
        assertEquals(OtherMediaHoldPolicy.Step.RESUME,
            OtherMediaHoldPolicy.step(false, 4000L, 5000L, 7000L))
    }

    @Test fun activeAudioResetsQuietWindow() {
        assertEquals(0L, OtherMediaHoldPolicy.quietSince(5000L, true, 6500L))
    }
}
