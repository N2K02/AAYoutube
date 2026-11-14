package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test fun defaultAndValidDurations() {
        assertEquals(15L, SleepTimerPolicy.DEFAULT_MINUTES)
        assertEquals(900_000L, SleepTimerPolicy.durationMillis(15L, false))
        assertEquals(3_600_000L, SleepTimerPolicy.durationMillis(1L, true))
        assertEquals(86_400_000L, SleepTimerPolicy.durationMillis(24L, true))
        assertEquals(86_400_000L, SleepTimerPolicy.durationMillis(1440L, false))
    }

    @Test fun rejectsZeroNegativeOutOfRangeAndOverflow() {
        assertNull(SleepTimerPolicy.durationMillis(0L, false))
        assertNull(SleepTimerPolicy.durationMillis(-1L, true))
        assertNull(SleepTimerPolicy.durationMillis(25L, true))
        assertNull(SleepTimerPolicy.durationMillis(1441L, false))
        assertNull(SleepTimerPolicy.durationMillis(Long.MAX_VALUE, true))
        assertNull(SleepTimerPolicy.durationMillis(Long.MAX_VALUE, false))
    }

    @Test fun countdownIsMonotonicAndFormatsCorrectly() {
        assertEquals(900_000L, SleepTimerPolicy.remainingMillis(901_000L, 1_000L))
        assertEquals(0L, SleepTimerPolicy.remainingMillis(901_000L, 902_000L))
        assertEquals("15:00", SleepTimerPolicy.formatRemaining(900_000L))
        assertEquals("01:01:01", SleepTimerPolicy.formatRemaining(3_661_000L))
        assertEquals("00:01", SleepTimerPolicy.formatRemaining(1L))
    }

    @Test fun timerIntegratedIntoExistingSettingsWithoutNewService() {
        fun src(path: String) = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val screens = src("app/src/main/java/com/aayoutube/app/Screens.kt")
        val timer = src("app/src/main/java/com/aayoutube/app/SleepTimer.kt")
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(screens.contains("Tab.SleepTimer -> SleepTimerPage()"))
        assertTrue(screens.contains("Tab.Features, Tab.SleepTimer"))
        assertTrue(timer.contains("WebHost.cmd(\"pause\")"))
        assertTrue(timer.contains("handler.removeCallbacks(tick)"))
        assertFalse(manifest.contains("SleepTimerService"))
    }
}
