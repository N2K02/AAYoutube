package com.aayoutube.app

import org.junit.Assert.*
import org.junit.Test

class PlayerDisplayPolicyTest {
    @Test fun crossDisplayHandoffRecreates() {
        assertTrue(PlayerDisplayPolicy.shouldRecreate(0, 323))
        assertTrue(PlayerDisplayPolicy.shouldRecreate(323, 0))
    }
    @Test fun sameAndUnknownDisplayReuseRenderer() {
        assertFalse(PlayerDisplayPolicy.shouldRecreate(0, 0))
        assertFalse(PlayerDisplayPolicy.shouldRecreate(323, 323))
        assertFalse(PlayerDisplayPolicy.shouldRecreate(null, 323))
        assertFalse(PlayerDisplayPolicy.shouldRecreate(0, null))
    }

    @Test fun phoneToCarBindGetsFreshRenderer() {
        // Phone/background WebView (display 0, no car window) into the car.
        assertEquals("display", PlayerDisplayPolicy.carRecreateReason(0, 620, null, 0x11))
    }

    @Test fun replacementCarWindowOnSameDisplayGetsFreshRenderer() {
        // 14:37 log: Android Auto set up a new Surface and Presentation window
        // (VRI#4) on the SAME display 620. Moving the live renderer there gave
        // audio over a black screen.
        assertEquals("window", PlayerDisplayPolicy.carRecreateReason(620, 620, 0x11, 0x22))
    }

    @Test fun sameCarWindowKeepsTheDecoder() {
        // Compose re-creating the frame inside the same window must not reload.
        assertNull(PlayerDisplayPolicy.carRecreateReason(620, 620, 0x11, 0x11))
        // Fresh renderer created by this car window: nothing to compare yet.
        assertNull(PlayerDisplayPolicy.carRecreateReason(620, 620, null, 0x11))
        // Unknown window token is never a reason to reload.
        assertNull(PlayerDisplayPolicy.carRecreateReason(620, 620, 0x11, null))
        assertNull(PlayerDisplayPolicy.carRecreateReason(null, 620, null, 0x11))
    }

    @Test fun displayChangeWinsOverWindowChange() {
        assertEquals("display", PlayerDisplayPolicy.carRecreateReason(578, 620, 0x11, 0x22))
    }
}
