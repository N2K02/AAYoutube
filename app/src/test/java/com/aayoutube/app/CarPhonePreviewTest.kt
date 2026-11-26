package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class CarPhonePreviewTest {
    @Test fun previewOnlyRunsDuringVisibleProjectedPlayback() {
        for (projected in listOf(false, true))
            for (playing in listOf(false, true))
                for (visible in listOf(false, true))
                    for (selected in listOf(false, true))
                        assertEquals(projected && playing && visible && selected,
                            CarPhonePreview.allowed(projected, playing, visible, selected))
    }
    @Test fun previewHasBoundedResolutionAndRate() {
        assertEquals(480 to 270, PlayerTransitionPolicy.captureSize(1920, 1080, 480))
        assertTrue(CarPhonePreview.INTERVAL_MS >= 250L)
    }
}
