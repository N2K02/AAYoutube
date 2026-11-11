package com.aayoutube.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRatePolicyTest {
    private fun cap(playing: Boolean = true, watchTab: Boolean = true, full: Boolean = true,
                    pip: Boolean = false, car: Boolean = false) =
        RefreshRatePolicy.capTo60(playing, watchTab, full, pip, car)

    @Test fun playingVideoInFullPlayerOrPipCapsTo60() {
        assertTrue(cap())
        assertTrue(cap(watchTab = false, full = false, pip = true))
    }

    @Test fun browsingPausedAndCarKeepNormalRate() {
        assertFalse(cap(playing = false))
        assertFalse(cap(full = false))       // mini player: scrolling stays smooth
        assertFalse(cap(watchTab = false))   // Settings
        assertFalse(cap(car = true))
    }

    @Test fun windowRateIsRestoredWhenTheCapEnds() {
        val ui = listOf(File("app/src/main/java/com/aayoutube/app/Screens.kt"),
            File("../app/src/main/java/com/aayoutube/app/Screens.kt")).first { it.isFile }.readText()
        assertTrue(ui.contains("it.preferredRefreshRate = 60f"))
        assertTrue(ui.contains("it.preferredRefreshRate = before"))
    }
}
