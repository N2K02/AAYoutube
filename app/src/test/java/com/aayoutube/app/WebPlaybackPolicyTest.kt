package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class WebPlaybackPolicyTest {
    @Test fun carProjectionAlwaysKeepsTheRendererActive() {
        for (playing in listOf(true, false)) {
            for (background in listOf(true, false)) {
                assertTrue(WebPlaybackPolicy.preserveRenderer(
                    projectionVisible = true, inPip = false, playing = playing,
                    backgroundPlay = background))
            }
        }
    }

    @Test fun activePipDoesNotRequireBackgroundAudioSetting() {
        assertTrue(WebPlaybackPolicy.preserveRenderer(false, true, true, false))
    }

    @Test fun normalBackgroundPlaybackIsOptInAndIdlePagesMayFreeze() {
        assertTrue(WebPlaybackPolicy.preserveRenderer(false, false, true, true))
        assertFalse(WebPlaybackPolicy.preserveRenderer(false, false, false, true))
        assertFalse(WebPlaybackPolicy.preserveRenderer(false, false, true, false))
        assertFalse(WebPlaybackPolicy.preserveRenderer(false, false, false, false))
    }
}
