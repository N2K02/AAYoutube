package com.aayoutube.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogNoisePolicyTest {
    @Test fun graphicsDataspaceSpamIsSuppressed() {
        assertTrue(LogNoisePolicy.isGraphicsNoise("Use dataspace 281083904as it is"))
    }

    @Test fun actionableWarningsAndErrorsArePreserved() {
        assertFalse(LogNoisePolicy.isGraphicsNoise("AudioFocus lost"))
        assertFalse(LogNoisePolicy.isGraphicsNoise("FATAL EXCEPTION"))
        assertFalse(LogNoisePolicy.isGraphicsNoise("Use dataspace allocation failed"))
    }
}
