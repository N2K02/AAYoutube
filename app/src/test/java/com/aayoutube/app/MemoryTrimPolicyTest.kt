package com.aayoutube.app

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimPolicyTest {
    @Test fun releasesHiddenBrowsePageUnderPressure() {
        assertTrue(MemoryTrimPolicy.releaseBrowse(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, browseInUse = false))
        assertTrue(MemoryTrimPolicy.releaseBrowse(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, browseInUse = false))
    }

    @Test fun keepsBrowsePageWhileOnScreenOrForLightTrims() {
        assertFalse(MemoryTrimPolicy.releaseBrowse(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, browseInUse = true))
        assertFalse(MemoryTrimPolicy.releaseBrowse(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, browseInUse = false))
    }
}
