package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class StandardPipPolicyTest {
    @Test fun regularVideoTransitionRectIsAccepted() {
        assertTrue(StandardPipPolicy.isValidSource(1080, 608, 3f))
        assertTrue(StandardPipPolicy.isValidSource(720, 405, 3f))
    }

    @Test fun tinyMiniAndAlreadyShrunkenPipRectsAreRejected() {
        assertFalse(StandardPipPolicy.isValidSource(224, 126, 1f))
        assertFalse(StandardPipPolicy.isValidSource(440, 248, 2f))
        assertFalse(StandardPipPolicy.isValidSource(1, 1, 3f))
        assertFalse(StandardPipPolicy.isValidSource(720, 200, 3f))
    }

    @Test fun invalidMetricsNeverReachAndroidPipHint() {
        assertFalse(StandardPipPolicy.isValidSource(1080, 608, 0f))
        assertFalse(StandardPipPolicy.isValidSource(1080, 608, Float.NaN))
        assertFalse(StandardPipPolicy.isValidSource(1080, 608, Float.POSITIVE_INFINITY))
        assertFalse(StandardPipPolicy.isValidSource(-100, 500, 2f))
    }
}
