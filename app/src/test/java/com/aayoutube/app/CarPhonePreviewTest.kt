package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class CarPhonePreviewTest {
    @Test fun outstandingCaptureBuffersAreBounded() {
        assertTrue(CaptureBudget.mayStart(0, 0, 0))
        assertFalse("one in flight, not yet stuck", CaptureBudget.mayStart(1, 1_000, 1_500))
        assertTrue("one stuck request may be replaced", CaptureBudget.mayStart(1, 1_000, 2_000))
        assertFalse("never more than the cap", CaptureBudget.mayStart(CaptureBudget.MAX_OUTSTANDING, 1_000, 99_000))
    }
    @Test fun healthSampleHasBoundedResolution() {
        assertEquals(64 to 36, PlayerTransitionPolicy.captureSize(1920, 1080, 64))
    }
}
