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
}
